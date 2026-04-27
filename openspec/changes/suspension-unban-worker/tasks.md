## 1. New `:infra:oidc` module

- [ ] 1.1 Create `infra/oidc/build.gradle.kts` mirroring the shape of `infra/redis/build.gradle.kts` (Kotlin JVM library, depends on `:core:domain`). Confirm by `diff` that nothing irrelevant from the redis build leaks (no Lettuce / Redis deps).
- [ ] 1.2 Register the new module in `settings.gradle.kts` (add `include(":infra:oidc")` in alphabetical order alongside the other `:infra:*` entries).
- [ ] 1.3 Pick the JWKS / JWT library and pin its version in `gradle/libs.versions.toml`. Default candidates: `com.auth0:jwks-rsa` + `com.auth0:java-jwt`, OR `com.nimbusds:nimbus-jose-jwt`. Choose based on (a) what's already on the JVM classpath if any (grep `gradle/libs.versions.toml` and `**/build.gradle.kts` for existing JWT/JWKS deps, including the existing Supabase HS256 verifier path), and (b) which matches the maintenance posture of the existing `:infra:*` modules. Document the choice in `docs/09-Versions.md` per the version-pinning policy.
- [ ] 1.4 Add the chosen lib(s) as `implementation(libs.<chosen>)` in `infra/oidc/build.gradle.kts`. Add `kotlinx-coroutines-core` for the `suspend fun` interface.
- [ ] 1.5 Create the package directory `infra/oidc/src/main/kotlin/id/nearyou/app/infra/oidc/` (use whichever package root matches the existing `:infra:*` convention — grep `infra/redis/src/main/kotlin/` for the precedent).

## 2. `OidcTokenVerifier` interface in `:core:domain`

- [ ] 2.1 Create `core/domain/src/main/kotlin/id/nearyou/app/core/domain/oidc/OidcTokenVerifier.kt` defining the interface:
   ```kotlin
   interface OidcTokenVerifier {
       suspend fun verify(token: String): VerifiedClaims
   }
   data class VerifiedClaims(val sub: String, val aud: String, val exp: Instant, val iat: Instant?)
   sealed class OidcVerificationException(message: String) : Exception(message) {
       class MissingAuthorization(...) : ...
       class InvalidScheme(...) : ...
       class InvalidToken(...) : ...
       class ExpiredToken(...) : ...
       class AudienceMismatch(...) : ...
   }
   ```
   The exception subtypes' names map 1:1 to the spec's sanitized `error` vocabulary (`missing_authorization`, `invalid_scheme`, `invalid_token`, `expired_token`, `audience_mismatch`). `verify` throws exactly one of these on failure; never returns a "verification failed" sentinel.
- [ ] 2.2 Confirm the interface module does NOT import any vendor JWKS / JWT lib (it stays pure-Kotlin). The vendor types live in `:infra:oidc` only.

## 3. `GoogleOidcTokenVerifier` impl in `:infra:oidc`

- [ ] 3.1 Create `infra/oidc/src/main/kotlin/id/nearyou/app/infra/oidc/GoogleOidcTokenVerifier.kt` implementing `OidcTokenVerifier`. Constructor accepts the configured `audience: String` plus a `JwkProvider`-equivalent (whichever lib's cache abstraction was chosen in 1.3) wired to `https://www.googleapis.com/oauth2/v3/certs`.
- [ ] 3.2 The cache MUST support rotation-aware refresh: when the token's `kid` header references a key not in cache, force one JWKS refresh before rejecting (per spec scenario "JWKS rotation forces one refresh before rejection"). Most JWKS libs support this via a `cached(rateLimited())` builder pattern; verify the chosen lib's behavior with a unit test (3.5 below).
- [ ] 3.3 Implement signature + claim verification. On success, return `VerifiedClaims`. On any of the 5 failure classes, throw the corresponding `OidcVerificationException` subtype. The `aud` mismatch check uses exact string equality.
- [ ] 3.4 Use a 60-second `iat` skew tolerance per spec.
- [ ] 3.5 Unit tests (`infra/oidc/src/test/kotlin/id/nearyou/app/infra/oidc/GoogleOidcTokenVerifierTest.kt`):
   - Valid signed JWT with matching audience + future `exp` → returns `VerifiedClaims`.
   - Malformed token → throws `InvalidToken`.
   - Wrong audience → throws `AudienceMismatch`.
   - Expired `exp` → throws `ExpiredToken`.
   - Bad signature → throws `InvalidToken`.
   - Token with `kid` not in cache → forces one JWKS refresh, then either passes (refresh-after-rotation) or throws (kid still unknown).
   Use a test JWKS (generate an RSA keypair in test setup; serve via a stubbed JWKS endpoint or a mock `JwkProvider`). Do NOT hit Google's real JWKS in unit tests.

## 4. `InternalEndpointAuth` Ktor plugin in `:backend:ktor`

- [ ] 4.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/internal/InternalEndpointAuth.kt` exporting a Ktor `RouteScopedPlugin` (or equivalent — match the precedent set by `ClientIpExtractor` from `health-check-endpoints` if it lives at `backend/ktor/src/main/kotlin/.../ClientIpExtractor.kt`).
- [ ] 4.2 The plugin reads `Authorization: Bearer <token>` from the inbound `ApplicationCall`. Missing header → respond `401 {"error": "missing_authorization"}` and short-circuit. Non-`Bearer` scheme → `401 {"error": "invalid_scheme"}`.
- [ ] 4.3 Call the injected `OidcTokenVerifier.verify(token)`. Map the 5 exception subtypes to their `error` strings (`invalid_token`, `expired_token`, `audience_mismatch`). On any failure → respond `401 {"error": "<class>"}` and short-circuit.
- [ ] 4.4 Log every rejection at WARN with the JWT-id (`jti` claim if present, otherwise SHA-256 truncated to 16 hex chars of the raw token bytes) so operators can correlate with Cloud Scheduler invocation logs. The WARN log MUST NOT include the raw token bytes, the JWT claims, or the configured audience value.
- [ ] 4.5 Default-permit: if verification succeeds, the plugin passes through to the route handler. The verified subject (the Cloud Scheduler service-account email) is attached to the call attributes (key `OIDC_SUBJECT`) so handlers can inspect it if needed.

## 5. Mount the plugin on `/internal/*`

- [ ] 5.1 In `backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt`, add a top-level `route("/internal") { install(InternalEndpointAuth) { verifier = ... }; ... }` block in the `routing { ... }` configuration.
- [ ] 5.2 The verifier dependency comes from Koin via `single<OidcTokenVerifier> { GoogleOidcTokenVerifier(audience = secretKey(env, "internal-oidc-audience"), ...) }`. Resolve via `get<OidcTokenVerifier>()` at the install site.
- [ ] 5.3 Boot-time validation: at app startup, read the `internal-oidc-audience` config value via `secretKey(env, "internal-oidc-audience")`. If absent / blank / not a syntactically valid URL, throw an `IllegalStateException` BEFORE the HTTP server begins accepting connections. Match the fail-fast pattern of `auth.supabaseUrl` and `auth.supabaseJwtSecret` in `Application.kt`.
- [ ] 5.4 Confirm via grep that `InternalEndpointAuth` is installed exactly once and only inside the `route("/internal") { ... }` block — NOT at the top-level `install()` (which would gate every endpoint including `/health/*`).
- [ ] 5.5 Confirm via grep that `/health/live` and `/health/ready` are NOT mounted under `route("/internal")`. They remain in the existing `health` route block per the unchanged `health-check` capability.

## 6. `SuspensionUnbanWorker` service

- [ ] 6.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/SuspensionUnbanWorker.kt` (use whichever package root matches the existing admin-area-adjacent code; if there is none, `internal/` next to `InternalEndpointAuth.kt` is fine). Class signature:
   ```kotlin
   class SuspensionUnbanWorker(private val dataSource: DataSource) {
       suspend fun execute(): UnbanResult
   }
   data class UnbanResult(val unbannedCount: Int, val unbannedUserIds: List<UUID>, val durationMs: Long)
   ```
- [ ] 6.2 Implement `execute` via a single SQL statement, executed inside `withContext(Dispatchers.IO) { dataSource.connection.use { conn -> conn.autoCommit = false; ... commit; ... } }`:
   ```sql
   UPDATE users
   SET is_banned = FALSE,
       suspended_until = NULL
   WHERE is_banned = TRUE
     AND suspended_until IS NOT NULL
     AND suspended_until <= NOW()
     AND deleted_at IS NULL
   RETURNING id;
   ```
   Capture the `id` column from each returned row into the `unbannedUserIds` list. `unbannedCount = unbannedUserIds.size`. Wrap the elapsed time around the JDBC call to populate `durationMs`.
- [ ] 6.3 Use `PreparedStatement` (no string interpolation) even though the SQL has no user-supplied parameters — defense in depth + matches existing repository style.
- [ ] 6.4 The whole `execute` returns one `UnbanResult` on success. Any SQL exception propagates uncaught; the route handler in section 7 maps it to a sanitized 500 response.

## 7. `UnbanWorkerRoute`

- [ ] 7.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/UnbanWorkerRoute.kt` exporting `fun Route.unbanWorkerRoute(worker: SuspensionUnbanWorker)` that mounts `post("/unban-worker") { ... }`. (The `/internal` prefix is supplied by the parent `route("/internal")` block in section 5.)
- [ ] 7.2 Inside the handler:
   - Call `worker.execute()` and capture the `UnbanResult`.
   - Log one structured INFO event with EXACTLY these fields and NO others: `event="suspension_unban_applied"`, `unbanned_count`, `unbanned_user_ids` (capped at first 50; if cap hit, also include `unbanned_user_ids_truncated=true`), `duration_ms`. Match the structured-logging style established by `health-check-endpoints` and `fcm-token-registration` (use the SLF4J MDC pattern that those rooms use; grep for the precedent).
   - Respond `200 OK` with body `{"unbanned_count": N}` (use kotlinx-serialization).
- [ ] 7.3 Wrap the handler in a top-level `try/catch (e: Exception)` that maps any thrown exception to a sanitized 500: WARN-log the exception with full context (NOT included in the response body), and respond with `500 {"error": "<classification>"}` where classification is one of `"timeout"`, `"connection_refused"`, `"unknown"` matching the `health-check` D6 vocabulary. Use the same sanitization-helper extension function if one was extracted in `health-check-endpoints`; if not, inline the small classifier and log a follow-up `extract-probe-error-classifier` if the third call site appears.

## 8. Wire Koin + register the route

- [ ] 8.1 In `Application.kt`, add Koin bindings:
   ```kotlin
   single<OidcTokenVerifier> { GoogleOidcTokenVerifier(audience = secretKey(environment, "internal-oidc-audience"), ...) }
   single { SuspensionUnbanWorker(get()) }
   ```
- [ ] 8.2 Inside the `route("/internal") { install(InternalEndpointAuth) { verifier = get<OidcTokenVerifier>() }; ... }` block, call `unbanWorkerRoute(get<SuspensionUnbanWorker>())`.
- [ ] 8.3 Confirm via grep that the route is registered exactly once.

## 9. Unit + integration tests

- [ ] 9.1 Create `backend/ktor/src/test/kotlin/id/nearyou/app/admin/SuspensionUnbanWorkerTest.kt`. Use the existing JDBC-test setup pattern (mirror `FcmTokenRoutesTest.kt` for the test-application + transactional DB rollback shape).
- [ ] 9.2 Test: user with `is_banned=TRUE`, `suspended_until=NOW()-INTERVAL'1 hour'`, `deleted_at IS NULL` → after `worker.execute()`, the row has `is_banned=FALSE`, `suspended_until=NULL`, AND the result includes the user's UUID.
- [ ] 9.3 Test: user with `is_banned=TRUE`, `suspended_until=NOW()+INTERVAL'1 hour'` → row unchanged after `execute()`. `unbanned_count=0`.
- [ ] 9.4 Test: user with `is_banned=TRUE`, `suspended_until IS NULL` (permanent ban) → row unchanged.
- [ ] 9.5 Test: user with `is_banned=TRUE`, `suspended_until=NOW()-INTERVAL'1 day'`, `deleted_at=NOW()-INTERVAL'7 days'` → row unchanged.
- [ ] 9.6 Test: user with `is_banned=FALSE`, `suspended_until IS NULL` → row unchanged (sanity).
- [ ] 9.7 Test: idempotency — invoke `execute()` twice in a row with no intervening changes. First call flips eligible rows. Second call returns `unbanned_count=0` and changes nothing.
- [ ] 9.8 Test: index usage — run `EXPLAIN UPDATE users SET is_banned = FALSE, suspended_until = NULL WHERE ... RETURNING id` (the worker's SQL) against the local Postgres and assert the plan node mentions `users_suspended_idx` (an index scan, NOT a sequential scan). Use the existing `EXPLAIN`-based test pattern if one exists in the test suite, otherwise a one-shot JDBC `EXPLAIN` query parsing the result text for `Index Scan using users_suspended_idx`.
- [ ] 9.9 Create `backend/ktor/src/test/kotlin/id/nearyou/app/admin/UnbanWorkerRouteTest.kt` using `testApplication { ... }`.
- [ ] 9.10 Test: 401 on missing Authorization header.
- [ ] 9.11 Test: 401 on `Authorization: Basic dXNlcjpwYXNz` → body is `{"error": "invalid_scheme"}`.
- [ ] 9.12 Test: 401 on malformed JWT (`Bearer not.a.jwt`) → body is `{"error": "invalid_token"}`.
- [ ] 9.13 Test: 401 on token with bad signature.
- [ ] 9.14 Test: 401 on audience mismatch → body is `{"error": "audience_mismatch"}`.
- [ ] 9.15 Test: 401 on expired `exp` → body is `{"error": "expired_token"}`.
- [ ] 9.16 Test: 200 on valid token + zero eligible users → body is `{"unbanned_count": 0}`.
- [ ] 9.17 Test: 200 on valid token + three eligible users → body is `{"unbanned_count": 3}` AND all three rows have `is_banned=FALSE`, `suspended_until=NULL`.
- [ ] 9.18 Test: structured-log assertion — after a successful unban, the captured SLF4J appender contains exactly one INFO event with `event="suspension_unban_applied"`, `unbanned_count`, and `unbanned_user_ids` fields. Use the existing `TestLoggerAppender` pattern (grep `backend/ktor/src/test/kotlin/` for the precedent).
- [ ] 9.19 Test: log-redaction — trigger a 401 path with a known-test-input JWT string. Capture the SLF4J appender output. Assert the captured log does NOT contain the raw JWT bytes, the configured audience value, or the JWT claims.
- [ ] 9.20 Test: pathological-volume truncation — synthetic test inserts 60 eligible users. Invoke the route. Assert: response `unbanned_count=60`, all 60 flipped, INFO event has `unbanned_user_ids` of length 50, AND `unbanned_user_ids_truncated=true`.
- [ ] 9.21 Test: sanitized 500 — induce a DB failure (e.g., point the test DataSource at a closed connection). Assert response is `500 {"error": "<classification>"}` where classification is one of the fixed vocabulary AND does NOT contain the original SQL exception message or stack trace.
- [ ] 9.22 Test: health endpoints unaffected — confirm `GET /health/live` returns `200` even with no Authorization header (regression guard against accidentally mounting `InternalEndpointAuth` over `/health/*`).
- [ ] 9.23 Test: route is mounted under `/internal/` — confirm `POST /unban-worker` (without the `/internal` prefix) returns `404`, and `POST /internal/unban-worker` is the active path.

## 10. CI verification (local)

- [ ] 10.1 Run `./gradlew ktlintCheck detekt :backend:ktor:test :infra:oidc:test :lint:detekt-rules:test` — all green.
- [ ] 10.2 If `ktlintCheck` flags formatting issues, run `./gradlew ktlintFormat` and re-verify.
- [ ] 10.3 Confirm no Detekt rule warnings introduced. The current rule set per [`lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/) is `BlockExclusionJoinRule`, `CoordinateJitterRule`, `RateLimitTtlRule`, `RawFromPostsRule`, `RawXForwardedForRule`, `RedisHashTagRule`. None should fire on this change — the worker touches `users` only by `id` (not in a viewer-block context, so `BlockExclusionJoinRule` doesn't apply), uses no Redis (no `RedisHashTagRule`), uses no `actual_location` (no `CoordinateJitterRule`), uses no `X-Forwarded-For` (no `RawXForwardedForRule`), uses no rate-limit (no `RateLimitTtlRule`), and the worker's `UPDATE users SET is_banned = ..., suspended_until = ...` does NOT match the `RawFromPostsRule` regex (which targets raw `FROM posts/users/post_replies` reads).

## 11. Pre-deploy: secret + Cloud Scheduler setup (staging)

- [ ] 11.1 Create the GCP Secret Manager slot for staging audience: `gcloud secrets create staging-internal-oidc-audience --project=nearyou-staging --replication-policy=automatic`. Then set the value to the staging Cloud Run service URL: `echo -n "https://api-staging.nearyou.id" | gcloud secrets versions add staging-internal-oidc-audience --data-file=- --project=nearyou-staging` (replace with the actual deployed URL — confirm via `gcloud run services describe nearyou-backend-staging --region=asia-southeast2 --format='value(status.url)'`).
- [ ] 11.2 Grant the Cloud Run runtime service account `roles/secretmanager.secretAccessor` on the new slot — per the gotcha pattern documented in `FOLLOW_UPS.md` § `gcp-secret-manager-iam-grant-on-new-slot`. Command: `gcloud secrets add-iam-policy-binding staging-internal-oidc-audience --project=nearyou-staging --member=serviceAccount:<runtime-sa> --role=roles/secretmanager.secretAccessor` (substitute the runtime SA from the existing staging deploy config).
- [ ] 11.3 In the `deploy-staging.yml` workflow (or the appropriate Cloud Run config layer), add `--update-secrets=INTERNAL_OIDC_AUDIENCE=staging-internal-oidc-audience:latest` to the `gcloud run services update`/`deploy` step.
- [ ] 11.4 Create a dedicated Cloud Scheduler service account for the staging job: `gcloud iam service-accounts create nearyou-unban-worker-scheduler-staging --project=nearyou-staging --display-name="Suspension Unban Worker Scheduler (staging)"`. Grant it `roles/run.invoker` on the staging Cloud Run service.
- [ ] 11.5 Create the staging Cloud Scheduler job: `gcloud scheduler jobs create http nearyou-unban-worker-staging --project=nearyou-staging --location=asia-southeast2 --schedule="0 21 * * *" --time-zone="UTC" --uri="https://api-staging.nearyou.id/internal/unban-worker" --http-method=POST --oidc-service-account-email=<sa-from-11.4> --oidc-token-audience="https://api-staging.nearyou.id" --max-retry-attempts=3 --min-backoff=30s --max-backoff=300s`. Confirm the cron `0 21 * * *` UTC = 04:00 WIB the following calendar day (UTC+7). The audience in the scheduler MUST equal the value in the secret slot (11.1).

## 12. Staging deploy + smoke

- [ ] 12.1 Push the branch and let CI run; confirm green (the section 10 commands all pass on the runner).
- [ ] 12.2 Trigger the staging deploy: `gh workflow run deploy-staging.yml --ref suspension-unban-worker`.
- [ ] 12.3 After deploy completes (`gh run list --workflow=deploy-staging.yml --limit=3`), capture the Cloud Run revision name. Verify boot succeeded by `curl -s https://api-staging.nearyou.id/health/ready` returns `200` (this also verifies the new `internal-oidc-audience` config didn't break startup).
- [ ] 12.4 Insert a synthetic test user via the staging-psql Cloud Run job pattern (see `FOLLOW_UPS.md` § `extract-staging-psql-helper-script` for the `^|^` delimiter trick). The user MUST have `is_banned=TRUE`, `suspended_until=NOW() - INTERVAL '1 hour'`, `deleted_at IS NULL`. Capture the inserted user's UUID for verification.
- [ ] 12.5 Smoke negative 1 (no token): `curl -i -X POST https://api-staging.nearyou.id/internal/unban-worker` → expect `401 {"error": "missing_authorization"}`.
- [ ] 12.6 Smoke negative 2 (bad token): `curl -i -X POST https://api-staging.nearyou.id/internal/unban-worker -H "Authorization: Bearer not.a.jwt"` → expect `401 {"error": "invalid_token"}`.
- [ ] 12.7 Smoke positive 1 (manual scheduler trigger): force the staging Cloud Scheduler job to run now: `gcloud scheduler jobs run nearyou-unban-worker-staging --location=asia-southeast2 --project=nearyou-staging`. Wait ~5 seconds. Verify via Cloud Logging: filter for the staging Cloud Run service + `event="suspension_unban_applied"`. Expect one INFO event with `unbanned_count=1` and `unbanned_user_ids=[<test-user-uuid>]`.
- [ ] 12.8 SQL-verify via the staging-psql Cloud Run job: `SELECT id, is_banned, suspended_until FROM users WHERE id = '<test-user-uuid>'`. Expect `is_banned=FALSE`, `suspended_until=NULL`.
- [ ] 12.9 Smoke positive 2 (idempotency): trigger the scheduler job again. Wait 5s. Cloud Logging: expect a second INFO event with `unbanned_count=0`. SQL: row from 12.8 unchanged.
- [ ] 12.10 Smoke negative 3 (future window): insert a synthetic user with `suspended_until=NOW() + INTERVAL '1 hour'`. Trigger the scheduler. Verify the user's row is UNCHANGED. (Cloud Logging will show `unbanned_count=0` if no other eligible rows exist — that's the success signal.)
- [ ] 12.11 Cleanup: `DELETE FROM users WHERE id IN ('<test-user-uuid-1>', '<test-user-uuid-2>')` via the staging-psql Cloud Run job.
- [ ] 12.12 If any smoke step fails, fix and push to the same branch (do NOT open a new PR — the same-PR-iteration rule per [`CLAUDE.md`](CLAUDE.md) § Delivery workflow). Re-run sections 10 + 12.

## 13. PR title + body refresh at phase boundaries

- [ ] 13.1 At the start of `/opsx:apply`: retitle the PR via `gh pr edit <pr> --title 'feat(internal): suspension-unban-worker'` (per [`openspec/project.md`](openspec/project.md) § Change Delivery Workflow — the proposal-phase title `docs(openspec): propose suspension-unban-worker` becomes `feat(internal): ...` when implementation begins).
- [ ] 13.2 Update the PR body via `gh pr edit <pr> --body "$(cat <<'EOF' ... EOF)"` to reflect the implementation status: list the new module (`:infra:oidc`), the endpoint, the OIDC plugin, the test coverage, the staging-smoke result, the Cloud Scheduler config. Mirror the body shape of [PR #54](https://github.com/aditrioka/nearyou-id/pull/54) (`health-check-endpoints`).
- [ ] 13.3 At archive time (in `/opsx:archive`): update the body once more with the post-merge state (squash-commit SHA, archive directory path, capability spec sync — `internal-endpoint-auth/spec.md` and `suspension-unban-worker/spec.md` move to `openspec/specs/`).
