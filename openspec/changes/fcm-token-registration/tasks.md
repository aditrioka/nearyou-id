## 1. Flyway migration

- [ ] 1.1 Create `backend/ktor/src/main/resources/db/migration/V14__user_fcm_tokens.sql` with the canonical schema verbatim from [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md): `user_fcm_tokens` table with columns `id`, `user_id` (FK to `users(id)` ON DELETE CASCADE), `platform` (CHECK ∈ {`android`, `ios`}), `token` (TEXT NOT NULL), `app_version` (TEXT, nullable), `created_at` (DEFAULT NOW()), `last_seen_at` (DEFAULT NOW()), UNIQUE on `(user_id, platform, token)`.
- [ ] 1.2 Add the two indexes from canonical: `user_fcm_tokens_user_idx` on `(user_id)` and `user_fcm_tokens_last_seen_idx` on `(last_seen_at)`.
- [ ] 1.3 Verify the migration file applies cleanly against a fresh local database (`./gradlew :backend:ktor:flywayMigrate` against the local Supabase or Docker Postgres) and that `\d user_fcm_tokens` shows the expected column set, CHECK constraint, UNIQUE constraint, and both indexes.
- [ ] 1.4 Add an integration test under `backend/ktor/src/test/kotlin/id/nearyou/app/user/UserFcmTokensSchemaTest.kt` that asserts: (a) inserting a row with `platform = 'web'` fails with a Postgres CHECK violation; (b) two inserts with the same `(user_id, platform, token)` triple fail the second with a unique-constraint violation; (c) deleting the parent user cascades-deletes the FCM token rows.

## 2. Request DTO

- [ ] 2.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRequest.kt` as a `@Serializable` data class with three fields: `token: String`, `platform: String`, `app_version: String? = null` (nullable optional).
- [ ] 2.2 Decide naming convention for the JSON field: align with the existing project convention. If snake_case (per `app_version` in the docs), keep `app_version`; if camelCase (Kotlin idiomatic), use `appVersion` and add a `@SerialName("app_version")` annotation. Verify by grepping the existing `@Serializable` request DTOs in `backend/ktor/src/main/kotlin/id/nearyou/app/**/` to find the established convention.
- [ ] 2.3 Apply consistent annotation to all three fields if the convention requires `@SerialName` mappings.

## 3. Repository (upsert SQL)

- [ ] 3.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRepository.kt` with a single `suspend fun upsert(userId: UUID, platform: String, token: String, appVersion: String?): UpsertResult` method.
- [ ] 3.2 Define `UpsertResult` as a sealed class or enum with two values: `Created` (new row inserted) and `Refreshed` (existing row's `last_seen_at` and `app_version` updated). The boolean equivalent is acceptable if Kotlin idiom is `Boolean created` — match the precedent set by other repository methods in the user module.
- [ ] 3.3 Implement `upsert` via `INSERT INTO user_fcm_tokens (user_id, platform, token, app_version, last_seen_at) VALUES (?, ?, ?, ?, NOW()) ON CONFLICT (user_id, platform, token) DO UPDATE SET last_seen_at = NOW(), app_version = EXCLUDED.app_version RETURNING xmax = 0 AS created`. The `xmax = 0` Postgres trick reliably distinguishes insert from update (xmax is 0 only for newly-inserted rows).
- [ ] 3.4 Use a JDBC `PreparedStatement` with parameter binding (NEVER string concatenation — SQL injection guard).
- [ ] 3.5 Wrap the JDBC call in `withContext(Dispatchers.IO) { ... }` per the existing repository pattern.

## 4. Route handler

- [ ] 4.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRoutes.kt` exporting `fun Route.fcmTokenRoutes(repository: FcmTokenRepository)` that registers `POST /api/v1/user/fcm-token`.
- [ ] 4.2 Inside the handler, extract the `UserPrincipal` via `call.principal<UserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)` (defensive — the auth middleware should already 401 before reaching here, but the principal-null check is a belt-and-suspenders).
- [ ] 4.3 Receive the body via `call.receive<FcmTokenRequest>()` wrapped in a `try/catch` for `BadRequestException` / `JsonConvertException` / `ContentTransformationException` → respond `400 {"error": "malformed_body"}`.
- [ ] 4.4 Validate `platform` ∈ {`android`, `ios`} (case-sensitive lowercase) → on failure respond `400 {"error": "invalid_platform"}`.
- [ ] 4.5 Validate `token.trim().isNotEmpty()` → on failure respond `400 {"error": "empty_token"}`.
- [ ] 4.6 Validate `app_version?.length ?: 0 <= 64` → on failure respond `400 {"error": "app_version_too_long"}`.
- [ ] 4.7 Call `repository.upsert(userId, platform, token.trim(), appVersion)` and capture the result.
- [ ] 4.8 Log a single structured INFO event: `log.info("fcm_token_registered user_id=$userId platform=$platform created=${result.created}")` (or the structured-logging equivalent if the project has SLF4J MDC wiring — match the existing pattern in `backend/ktor/src/main/kotlin/id/nearyou/app/auth/` log lines).
- [ ] 4.9 Respond `204 No Content` on success.
- [ ] 4.10 Wrap the whole handler in a top-level `try/catch (e: Exception)` for any unexpected throwable → log at WARN with full context (`log.warn("fcm_token_registration_failed user_id=$userId", e)`) and respond `500 Internal Server Error` with no body. The catch MUST NOT include `BadRequestException` / `JsonConvertException` / `ContentTransformationException` — those are handled in 4.3.

## 5. Wire Koin + route registration

- [ ] 5.1 In `Application.kt`, add a Koin binding `single { FcmTokenRepository(get()) }` (the `get()` resolves the existing `DataSource`).
- [ ] 5.2 In the JWT-protected route block (the same block that already registers other `/api/v1/user/*` endpoints — grep `Application.kt` for `route("/api/v1/user")` or similar), add `fcmTokenRoutes(get<FcmTokenRepository>())`.
- [ ] 5.3 Confirm the route is NOT registered outside the JWT-protected block (an audit grep `grep -n "fcm" backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt` should show exactly one registration site, inside the auth-required block).
- [ ] 5.4 Verify the body-size cap (4 KB) is in place — if Ktor's `ContentNegotiation` plugin enforces a default body limit, document the value; if no project-wide limit exists, install a route-scoped limit on this endpoint via `install(BodyLimit) { maxBytes = 4096 }` (check the Ktor version — the plugin name varies; if no native plugin exists, do a `call.request.contentLength()?.let { if (it > 4096) return@post call.respond(HttpStatusCode.PayloadTooLarge) }` defensive check at the top of the handler).

## 6. Unit + integration tests

- [ ] 6.1 Create `backend/ktor/src/test/kotlin/id/nearyou/app/user/FcmTokenRoutesTest.kt` using the existing test-application setup pattern (mirror `HealthRoutesTest.kt` for Ktor `testApplication { ... }` shape).
- [ ] 6.2 Test: happy-path new registration → `204` + row exists with `created_at ≈ last_seen_at`.
- [ ] 6.3 Test: re-registration of same `(user_id, platform, token)` → `204` + `SELECT COUNT(*) ...` returns 1 + `last_seen_at` is later than `created_at` + `app_version` is the latest value.
- [ ] 6.4 Test: same user registers Android and iOS tokens → both `204` + `SELECT COUNT(*) WHERE user_id = :u` returns 2.
- [ ] 6.5 Test: same token registered by two different users → both `204` + `SELECT COUNT(*) WHERE token = :t` returns 2.
- [ ] 6.6 Test: `platform = "web"` → `400 {"error": "invalid_platform"}` + zero rows.
- [ ] 6.7 Test: `platform = "Android"` (mixed case) → `400 {"error": "invalid_platform"}` + zero rows.
- [ ] 6.8 Test: `platform` field omitted entirely → `400 {"error": "invalid_platform"}` + zero rows. (Implementation note: if the DTO field is non-nullable, deserialization may fail with `MissingFieldException` first — that maps to `malformed_body` in 4.3. Decide which 400 code is correct and align spec scenario + test. Recommendation: keep `platform` non-nullable in the DTO so missing-field is `malformed_body`; for `platform` value validation use the closed-vocabulary `invalid_platform`. Update the spec scenario at `specs/fcm-token-registration/spec.md` § Validation errors if the deserialization-vs-validation split needs clarifying.)
- [ ] 6.9 Test: empty `token` → `400 {"error": "empty_token"}` + zero rows.
- [ ] 6.10 Test: whitespace-only `token` → `400 {"error": "empty_token"}` + zero rows.
- [ ] 6.11 Test: missing `token` field → `400 {"error": "empty_token"}` (or `malformed_body` per the same DTO non-nullable decision in 6.8 — align both).
- [ ] 6.12 Test: `app_version` of 65 chars → `400 {"error": "app_version_too_long"}` + zero rows.
- [ ] 6.13 Test: malformed JSON body → `400 {"error": "malformed_body"}` + zero rows.
- [ ] 6.14 Test: missing Authorization header → `401 Unauthorized` + zero rows.
- [ ] 6.15 Test: invalid JWT (random string) → `401 Unauthorized` + zero rows.
- [ ] 6.16 Test: `user_id` field in request body is ignored → an authenticated user U_AUTH posting `{"user_id": "<other-uuid>", "token": "abc", "platform": "android"}` produces a row with `user_id = U_AUTH`.
- [ ] 6.17 Test: 4097-byte body → `413 Payload Too Large` + zero rows.
- [ ] 6.18 Test: ON DELETE CASCADE — register a token for user U, delete user U via the existing user-delete path (or directly via SQL if no delete endpoint exists yet), confirm `SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = U` returns 0.
- [ ] 6.19 Test: structured log assertion — capture the SLF4J appender output during a successful registration, assert the log event contains `event="fcm_token_registered"`, `user_id`, `platform`, AND `created` fields. Use the existing structured-log test pattern (grep `backend/ktor/src/test/kotlin/` for `TestLoggerAppender` or similar).

## 7. CI verification (local)

- [ ] 7.1 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` — all green.
- [ ] 7.2 If `ktlintCheck` flags formatting issues, run `./gradlew ktlintFormat` and re-verify.
- [ ] 7.3 Confirm no Detekt rule warnings introduced (especially `BlockExclusionJoinRule`, `RawFromPostsRule`, `ContentLengthGuardRule` — the latter SHOULD recognize the validation in section 4.5 / 4.6 as the length guard for this input endpoint; if it flags the `/api/v1/user/fcm-token` route, the rule may need an allowlist entry — surface to design as a deviation before silently allowlisting).

## 8. Staging deploy + smoke

- [ ] 8.1 Push the branch and let CI run; confirm green.
- [ ] 8.2 Trigger the staging deploy: `gh workflow run deploy-staging.yml --ref fcm-token-registration` (the deploy workflow runs the Flyway migration job pre-app-deploy per [`openspec/project.md`](openspec/project.md) § Change Delivery Workflow).
- [ ] 8.3 After deploy completes (monitor via `gh run list --workflow=deploy-staging.yml --limit=3`), capture the Cloud Run revision name for reference.
- [ ] 8.4 Mint a staging JWT for a test user via the existing `mint-staging-jwt.sh` (or equivalent) script. If no such script exists, document the manual mint procedure and consider it a follow-up — but for THIS smoke, use any working JWT path (existing endpoints' tests likely have a documented mint flow).
- [ ] 8.5 Smoke positive 1 (new registration): `curl -i -X POST https://api-staging.nearyou.id/api/v1/user/fcm-token -H "Authorization: Bearer $STAGING_JWT" -H "Content-Type: application/json" -d '{"token":"smoke-test-android-001","platform":"android","app_version":"0.1.0-staging-smoke"}'` → expect `204`.
- [ ] 8.6 Smoke positive 2 (idempotent refresh): re-issue the same `curl` from 8.5 → expect `204`. Then SQL-verify (via Supabase staging SQL editor or `psql` against the staging connection string): `SELECT created_at, last_seen_at, app_version FROM user_fcm_tokens WHERE token = 'smoke-test-android-001'` — assert exactly one row, `last_seen_at > created_at`, `app_version = '0.1.0-staging-smoke'`.
- [ ] 8.7 Smoke positive 3 (multi-platform same user): `curl -X POST ... -d '{"token":"smoke-test-ios-001","platform":"ios","app_version":"0.1.0-staging-smoke"}'` (same JWT) → expect `204`. SQL-verify: `SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = '<test-user-uuid>'` returns 2.
- [ ] 8.8 Smoke negative 1 (invalid platform): `curl -i -X POST ... -d '{"token":"x","platform":"web"}'` → expect `400 {"error": "invalid_platform"}`.
- [ ] 8.9 Smoke negative 2 (empty token): `curl -i -X POST ... -d '{"token":"","platform":"android"}'` → expect `400 {"error": "empty_token"}`.
- [ ] 8.10 Smoke negative 3 (no JWT): `curl -i -X POST https://api-staging.nearyou.id/api/v1/user/fcm-token -H "Content-Type: application/json" -d '{"token":"x","platform":"android"}'` → expect `401`.
- [ ] 8.11 Cleanup: `DELETE FROM user_fcm_tokens WHERE token IN ('smoke-test-android-001','smoke-test-ios-001')` against staging.
- [ ] 8.12 If any smoke step fails, fix and push to the same branch (do NOT open a new PR — the same-PR-iteration rule per [`CLAUDE.md`](CLAUDE.md) § Delivery workflow). Re-run section 7 + 8.

## 9. PR title + body refresh at phase boundaries

- [ ] 9.1 At the start of `/opsx:apply`: retitle the PR via `gh pr edit <pr> --title 'feat(user): fcm-token-registration'` (per [`openspec/project.md`](openspec/project.md) § Change Delivery Workflow — the proposal-phase title `docs(openspec): propose fcm-token-registration` becomes `feat(user): ...` when implementation begins).
- [ ] 9.2 Update the PR body via `gh pr edit <pr> --body "$(cat <<'EOF' ... EOF)"` to reflect the implementation status: list the migration V-number, the endpoint, the test coverage, the staging-smoke result. Mirror the body shape of [PR #54](https://github.com/aditrioka/nearyou-id/pull/54) (`health-check-endpoints`).
- [ ] 9.3 At archive time (in `/opsx:archive`): update the body once more with the post-merge state (squash-commit SHA, archive directory path, capability spec sync).
