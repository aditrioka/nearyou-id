## 1. New `:infra:oidc` module

- [ ] 1.1 Create `infra/oidc/build.gradle.kts` mirroring the shape of `infra/redis/build.gradle.kts` (Kotlin JVM library, depends on `:core:domain`). Confirm by `diff` that nothing irrelevant from the redis build leaks (no Lettuce / Redis deps).
- [ ] 1.2 Register the new module in `settings.gradle.kts` (add `include(":infra:oidc")` in alphabetical order alongside the other `:infra:*` entries).
- [ ] 1.3 Add `com.auth0:jwks-rsa` + `com.auth0:java-jwt` as direct dependencies in `gradle/libs.versions.toml` with current stable versions (per `design.md` D10). These libs are ALREADY in tree transitively — Ktor's `io.ktor:ktor-server-auth-jwt-jvm` ([`gradle/libs.versions.toml:58`](gradle/libs.versions.toml)) uses Auth0 internally for its `jwt()` Auth feature. This task promotes them from transitive to direct so `:infra:oidc` has stable, version-pinned access. Verify the transitive presence first via `./gradlew :backend:ktor:dependencies | grep -E 'auth0|jwks-rsa|java-jwt'` — confirm; if grep unexpectedly shows `nimbusds` instead (e.g., a future Ktor release switched its underlying lib), surface as an amend-design and do not silently swap. Document the chosen versions in `docs/09-Versions.md` per the version-pinning policy.
- [ ] 1.4 Add the chosen lib(s) as `implementation(libs.<chosen>)` in `infra/oidc/build.gradle.kts`. Add `kotlinx-coroutines-core` for the `suspend fun` interface.
- [ ] 1.5 Create the package directory `infra/oidc/src/main/kotlin/id/nearyou/app/infra/oidc/` (use whichever package root matches the existing `:infra:*` convention — grep `infra/redis/src/main/kotlin/` for the precedent).
- [ ] 1.6 Add `implementation(projects.infra.oidc)` to [`backend/ktor/build.gradle.kts`](backend/ktor/build.gradle.kts) (alongside lines 20–21 where `:infra:supabase` and `:infra:redis` are wired). Without this, `Application.kt` cannot import `GoogleOidcTokenVerifier` and section 8 will fail to compile.
- [ ] 1.7 Add the OIDC config block to [`backend/ktor/src/main/resources/application.conf`](backend/ktor/src/main/resources/application.conf), mirroring the existing `auth { supabaseUrl = ${?SUPABASE_URL} }` shape at line 23:
   ```
   oidc {
       internalAudience = ${?INTERNAL_OIDC_AUDIENCE}
   }
   ```
   Without this block, `environment.config.property("oidc.internalAudience").getString()` throws `ConfigurationException` regardless of whether the env var is set, and the boot-fail tests 9.25 / 9.26 cannot distinguish "missing env var" from "missing config key".

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

   **Test fixture pattern**: there is no JWKS fixture precedent in the existing test suite, so this task introduces one. Use this approach:
   1. In test setup, generate an RSA-2048 keypair with `java.security.KeyPairGenerator.getInstance("RSA")` (JDK builtin; no extra deps).
   2. Construct test JWTs using `com.auth0:java-jwt` (the same lib `:infra:oidc` uses): `JWT.create().withKeyId("test-kid").withAudience(...).withExpiresAt(...).sign(Algorithm.RSA256(publicKey, privateKey))`.
   3. Stub the `JwkProvider` interface from `com.auth0:jwks-rsa` directly — implement a tiny in-test `JwkProvider` that returns a single `Jwk` constructed from the test public key when asked for `test-kid`, and throws `JwkException` otherwise. No HTTP server, no MockWebServer required for these unit tests.
   4. For the JWKS-rotation scenario, the in-test `JwkProvider` toggles its behavior between calls (initial call: `kid` absent → throws → triggers refresh → second call: `kid` present → returns Jwk).

   Do NOT hit Google's real JWKS in unit tests. Do NOT introduce MockWebServer if the in-process `JwkProvider` stub can carry the test surface.

## 4. `InternalEndpointAuth` Ktor plugin in `:backend:ktor`

- [ ] 4.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/internal/InternalEndpointAuth.kt` exporting a Ktor `RouteScopedPlugin` (or equivalent — match the precedent set by `ClientIpExtractor` from `health-check-endpoints` if it lives at `backend/ktor/src/main/kotlin/.../ClientIpExtractor.kt`).
- [ ] 4.2 The plugin reads `Authorization: Bearer <token>` from the inbound `ApplicationCall`. Missing header → respond `401 {"error": "missing_authorization"}` and short-circuit. Non-`Bearer` scheme → `401 {"error": "invalid_scheme"}`.
- [ ] 4.3 Call the injected `OidcTokenVerifier.verify(token)`. Map the 5 exception subtypes to their `error` strings (`invalid_token`, `expired_token`, `audience_mismatch`). On any failure → respond `401 {"error": "<class>"}` and short-circuit.
- [ ] 4.4 Log every rejection at WARN with a token correlation id derived as the first 16 hex chars of `SHA-256(raw token bytes)` so operators can correlate with Cloud Scheduler invocation logs. The WARN log MUST NOT include the raw token bytes, any JWT claim (including `jti` — using any claim would conflict with the no-claims rule), or the configured audience value.
- [ ] 4.5 Default-permit: if verification succeeds, the plugin passes through to the route handler. The verified subject (the Cloud Scheduler service-account email) is attached to the call attributes (key `OIDC_SUBJECT`) so handlers can inspect it if needed.

## 5. Mount the plugin on `/internal/*`

- [ ] 5.1 In `backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt`, add a top-level `route("/internal") { install(InternalEndpointAuth) { verifier = ... }; ... }` block in the `routing { ... }` configuration.
- [ ] 5.2 The verifier dependency comes from Koin via `single<OidcTokenVerifier> { GoogleOidcTokenVerifier(audience = environment.config.property("oidc.internalAudience").getString(), ...) }`. Resolve via `get<OidcTokenVerifier>()` at the install site. **Note**: the audience is a public Cloud Run service URL, NOT a secret, so it is read via plain Ktor `application.conf` (resolved from the `INTERNAL_OIDC_AUDIENCE` env var) rather than `secretKey(env, name)`. The `secretKey()` helper is reserved for genuine secret material per the project's lint-rule semantics; using it for non-secret config muddles that contract and would burn an unnecessary Secret Manager slot per environment.
- [ ] 5.3 Boot-time validation: at app startup, read `oidc.internalAudience` from the Ktor config. If absent / blank / not a syntactically valid URL, throw an `IllegalStateException` BEFORE the HTTP server begins accepting connections AND ensure the JVM exits non-zero (so Cloud Run's startup probe fails and traffic is not flipped to the new revision). Match the fail-fast pattern of `auth.supabaseUrl` and `auth.supabaseJwtSecret` in `Application.kt`.
- [ ] 5.4 Boot-time wiring guard: if `OidcTokenVerifier` construction throws (e.g., the JWKS lib's initial fetch fails or any dependency wiring fails), application boot MUST fail-fast with a JVM exit. The `route("/internal") { ... }` block MUST NOT register if the verifier isn't constructable. Easiest implementation: invoke `get<OidcTokenVerifier>()` once during early startup (before `routing { ... }` mounts) so any construction error surfaces before the route is bound.
- [ ] 5.5 Confirm via grep that `InternalEndpointAuth` is installed exactly once and only inside the `route("/internal") { ... }` block — NOT at the top-level `install()` (which would gate every endpoint including `/health/*`).
- [ ] 5.6 Confirm via grep that `/health/live` and `/health/ready` are NOT mounted under `route("/internal")`. They remain in the existing `health` route block per the unchanged `health-check` capability.

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
- [ ] 7.3 Wrap the handler in a top-level `try/catch (e: Exception)` that maps any thrown exception to a sanitized 500: WARN-log the exception with full context (NOT included in the response body), and respond with `500 {"error": "<classification>"}` where classification is one of `"timeout"`, `"connection_refused"`, `"unknown"` (the handler-level vocabulary documented in `specs/suspension-unban-worker/spec.md` § "Response shape"). **Note**: `health-check-endpoints` (PR #54) did NOT extract a reusable sanitization-helper (verified via grep — no `classify*` / `sanitize*` extension exists in `backend/ktor/src/main/kotlin/`); inline the small classifier here. After this is the second call site, log a `FOLLOW_UPS.md` entry `extract-probe-error-classifier` so the third call site triggers extraction (rule of three).

## 8. Wire Koin + register the route

- [ ] 8.1 In `Application.kt`, add Koin bindings:
   ```kotlin
   single<OidcTokenVerifier> { GoogleOidcTokenVerifier(audience = environment.config.property("oidc.internalAudience").getString(), ...) }
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
- [ ] 9.18 Test: structured-log assertion — after a successful unban, the captured Logback appender contains exactly one INFO event with `event="suspension_unban_applied"`, `unbanned_count`, and `unbanned_user_ids` fields. Use the existing `withLogCapture` Logback `ListAppender<ILoggingEvent>` precedent at [`backend/ktor/src/test/kotlin/id/nearyou/app/user/FcmTokenRoutesTest.kt:168-184`](backend/ktor/src/test/kotlin/id/nearyou/app/user/FcmTokenRoutesTest.kt). If section 9 below uses a different test class layout, mirror that instead.
- [ ] 9.19 Test: log-redaction — pick a known-distinguishable test JWT string (e.g., a hand-crafted JWT signed by the test JWKS keypair containing `aud="https://probe.test/audience-canary"`, `sub="REDACTION_PROBE_SUB"`, `jti="REDACTION_PROBE_JTI"`). Trigger any 401 path with it. Capture the Logback output. Assert the captured log lines do NOT contain (a) the raw bearer token bytes, (b) the literal substring `"audience-canary"` (proves the configured audience value is not echoed), (c) the literal substrings `"REDACTION_PROBE_SUB"` and `"REDACTION_PROBE_JTI"` (proves no JWT claim leaks).
- [ ] 9.20 Test: pathological-volume truncation — synthetic test inserts 60 eligible users. Invoke the route. Assert: response `unbanned_count=60`, all 60 flipped, INFO event has `unbanned_user_ids` of length 50, AND `unbanned_user_ids_truncated=true`.
- [ ] 9.21 Test: sanitized 500 — induce a DB failure (e.g., point the test DataSource at a closed connection). Assert response is `500 {"error": "<classification>"}` where classification is one of `"timeout"` / `"connection_refused"` / `"unknown"` AND does NOT contain the original SQL exception message or stack trace.
- [ ] 9.22 Test: health endpoints unaffected — confirm `GET /health/live` returns `200` even with no Authorization header (regression guard against accidentally mounting `InternalEndpointAuth` over `/health/*`).
- [ ] 9.23 Test: route is mounted under `/internal/` — confirm `POST /unban-worker` (without the `/internal` prefix) returns `404`, and `POST /internal/unban-worker` is the active path.
- [ ] 9.24 Test: no-notification-row regression guard — after a successful unban of N users, assert `SELECT COUNT(*) FROM notifications WHERE user_id IN (...)` for those N users is unchanged from before the worker fired (zero new rows). Backs the spec scenario "No notification row on unban".
- [ ] 9.25 Test: boot fails on missing `oidc.internalAudience` config — start the Ktor application with the env var unset. Assert the `IllegalStateException` is thrown before `embeddedServer.start()` returns AND the exit path leads to a JVM-exit. Mirror the existing fail-fast tests for `auth.supabaseUrl` if any (grep `backend/ktor/src/test/kotlin/` for `IllegalStateException.*supabase` or similar).
- [ ] 9.26 Test: boot fails on blank `oidc.internalAudience` — same shape as 9.25 but with the env var set to the empty string.
- [ ] 9.27 Test: route-level JWKS rotation — mount the route with a `OidcTokenVerifier` whose JWKS cache initially does not contain a specific `kid`. Issue a JWT signed with that `kid`. Verify the route returns `200` per the spec scenario "JWKS rotation refresh resolves the new key" (the verifier-level rotation contract is design.md D2) AND verify a SECOND call with a JWT whose `kid` is still missing post-refresh returns `401 invalid_token` per the sibling scenario "JWKS rotation refresh still does not resolve the kid". This complements unit-test 3.5 with the route-level integration shape.
- [ ] 9.28 Test: blank-token rejection — send `Authorization: Bearer ` (Bearer scheme, empty token bytes after the space). Assert `401 {"error": "invalid_token"}` (the empty string is structurally malformed JWT). This distinguishes `invalid_scheme` (no `Bearer` prefix) from `invalid_token` (Bearer prefix present, payload broken).
- [ ] 9.29 Test: cross-path auth isolation — issue a Supabase HS256 user-token (the kind the existing `/v1/*` routes accept). Send it to `POST /internal/unban-worker`. Assert `401 {"error": "invalid_token"}` (the OIDC verifier rejects it because Google's RS256 JWKS does not include the Supabase HS256 shared secret). Conversely, issue a Google OIDC token and send it to a representative `/v1/*` Supabase-authenticated route (e.g., `POST /api/v1/posts` or any other already-shipped Supabase-auth path). Assert that path returns `401`. Regression guard against the two auth paths accidentally cross-validating.

## 10. CI verification (local)

- [ ] 10.1 Run `./gradlew ktlintCheck detekt :backend:ktor:test :infra:oidc:test :lint:detekt-rules:test` — all green.
- [ ] 10.2 If `ktlintCheck` flags formatting issues, run `./gradlew ktlintFormat` and re-verify.
- [ ] 10.3 Confirm no Detekt rule warnings introduced. The current rule set per [`lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/) is `BlockExclusionJoinRule`, `CoordinateJitterRule`, `RateLimitTtlRule`, `RawFromPostsRule`, `RawXForwardedForRule`, `RedisHashTagRule`. None should fire on this change — the worker touches `users` only by `id` (not in a viewer-block context, so `BlockExclusionJoinRule` doesn't apply), uses no Redis (no `RedisHashTagRule`), uses no `actual_location` (no `CoordinateJitterRule`), uses no `X-Forwarded-For` (no `RawXForwardedForRule`), uses no rate-limit (no `RateLimitTtlRule`), and the worker's `UPDATE users SET is_banned = ..., suspended_until = ...` does NOT match the `RawFromPostsRule` regex (which targets raw `FROM posts/users/post_replies` reads).

## 11. Pre-deploy: env-var + Cloud Scheduler setup (staging)

**Ordering**: section 11 lands BEFORE the code merges to staging. The env-var and the Cloud Scheduler job are inert without the code (the env-var is read by code that doesn't exist yet; the Scheduler job hits a 404 / 401 until the route mounts), so they are safe to wire up first. If the code merges first without the env-var bound, boot fails-fast on the missing `oidc.internalAudience` config (per spec scenario "Boot fails on blank audience config") and Cloud Run rolls back to the prior revision — recoverable but noisy. Land the env-var binding via `deploy-staging.yml` workflow change BEFORE pushing the route.

- [ ] 11.1 Add the `INTERNAL_OIDC_AUDIENCE` env-var binding to `deploy-staging.yml` (or the appropriate Cloud Run config layer). The value is the staging Cloud Run service URL (confirm via `gcloud run services describe nearyou-backend-staging --region=asia-southeast2 --format='value(status.url)'` — typically `https://api-staging.nearyou.id`). Use plain env-var binding (`--set-env-vars=INTERNAL_OIDC_AUDIENCE=https://api-staging.nearyou.id`), NOT a `--update-secrets=` binding — the audience is a public service URL, not secret material. No GCP Secret Manager slot needed.
- [ ] 11.2 Create a dedicated Cloud Scheduler service account for the staging job: `gcloud iam service-accounts create nearyou-unban-worker-scheduler-staging --project=nearyou-staging --display-name="Suspension Unban Worker Scheduler (staging)"`. Grant it `roles/run.invoker` on the staging Cloud Run service.
- [ ] 11.3 Create the staging Cloud Scheduler job: `gcloud scheduler jobs create http nearyou-unban-worker-staging --project=nearyou-staging --location=asia-southeast2 --schedule="0 21 * * *" --time-zone="UTC" --uri="https://api-staging.nearyou.id/internal/unban-worker" --http-method=POST --oidc-service-account-email=<sa-from-11.2> --oidc-token-audience="https://api-staging.nearyou.id" --max-retry-attempts=3 --min-backoff=30s --max-backoff=300s`. Confirm the cron `0 21 * * *` UTC = 04:00 WIB the following calendar day (UTC+7). The audience passed to `--oidc-token-audience` MUST equal the value bound to `INTERNAL_OIDC_AUDIENCE` (task 11.1) — that's the binding the OIDC plugin verifies.

## 12. Staging deploy + smoke

- [ ] 12.1 Push the branch and let CI run; confirm green (the section 10 commands all pass on the runner).
- [ ] 12.2 Trigger the staging deploy: `gh workflow run deploy-staging.yml --ref suspension-unban-worker`.
- [ ] 12.3 After deploy completes (`gh run list --workflow=deploy-staging.yml --limit=3`), capture the Cloud Run revision name. Verify boot succeeded by `curl -s https://api-staging.nearyou.id/health/ready` returns `200` (this also verifies the new `INTERNAL_OIDC_AUDIENCE` env config didn't break startup).
- [ ] 12.4 Insert a synthetic test user via the staging-psql Cloud Run job pattern (see `FOLLOW_UPS.md` § `extract-staging-psql-helper-script` for the `^|^` delimiter trick). The user MUST have `is_banned=TRUE`, `suspended_until=NOW() - INTERVAL '1 hour'`, `deleted_at IS NULL`. Capture the inserted user's UUID for verification.
- [ ] 12.5 Smoke negative 1 (no token): `curl -i -X POST https://api-staging.nearyou.id/internal/unban-worker` → expect `401 {"error": "missing_authorization"}`.
- [ ] 12.6 Smoke negative 2 (bad token): `curl -i -X POST https://api-staging.nearyou.id/internal/unban-worker -H "Authorization: Bearer not.a.jwt"` → expect `401 {"error": "invalid_token"}`.
- [ ] 12.7 Smoke positive 1 (manual scheduler trigger): force the staging Cloud Scheduler job to run now: `gcloud scheduler jobs run nearyou-unban-worker-staging --location=asia-southeast2 --project=nearyou-staging`. Wait ~5 seconds. Verify via Cloud Logging: filter for the staging Cloud Run service + `event="suspension_unban_applied"`. Expect one INFO event with `unbanned_count=1` and `unbanned_user_ids=[<test-user-uuid>]`.
- [ ] 12.8 SQL-verify via the staging-psql Cloud Run job: `SELECT id, is_banned, suspended_until FROM users WHERE id = '<test-user-uuid>'`. Expect `is_banned=FALSE`, `suspended_until=NULL`.
- [ ] 12.9 Smoke positive 2 (idempotency): trigger the scheduler job again. Wait 5s. Cloud Logging: expect a second INFO event with `unbanned_count=0`. SQL: row from 12.8 unchanged.
- [ ] 12.10 Smoke negative 3 (future window): insert a synthetic user with `suspended_until=NOW() + INTERVAL '1 hour'`. Trigger the scheduler. Verify the user's row is UNCHANGED. (Cloud Logging will show `unbanned_count=0` if no other eligible rows exist — that's the success signal.)
- [ ] 12.11 Cleanup: `DELETE FROM users WHERE id IN ('<test-user-uuid-1>', '<test-user-uuid-2>')` via the staging-psql Cloud Run job.
- [ ] 12.12 If any smoke step fails, fix and push to the same branch (do NOT open a new PR — the same-PR-iteration rule per [`CLAUDE.md`](CLAUDE.md) § Delivery workflow). Re-run sections 10 + 12.

<!--
PR title + body refresh at phase boundaries — process work, owned by /opsx:apply and /opsx:archive per openspec/project.md § "PR title and body MUST stay current at every phase boundary". Not tracked as implementation tasks here. The apply / archive skills enforce:
  - At /opsx:apply start: gh pr edit <pr> --title 'feat(internal): suspension-unban-worker'
  - At /opsx:apply progression: PR body refreshed to reflect new module (:infra:oidc), endpoint, plugin, tests, staging smoke, Cloud Scheduler config (mirror PR #54 health-check-endpoints body shape)
  - At /opsx:archive: PR body refreshed once more with post-merge state (squash-commit SHA, archive path, spec sync to openspec/specs/internal-endpoint-auth/ + openspec/specs/suspension-unban-worker/)
-->

