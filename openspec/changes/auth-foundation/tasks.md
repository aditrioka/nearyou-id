## 1. Local dev environment

- [ ] 1.1 Create `dev/docker-compose.yml` with `postgres` (`postgis/postgis:16-3.4`, port 5432, healthcheck via `pg_isready`) and `redis` (`redis:7-alpine`, port 6379) services; both bind credentials/ports from `dev/.env`
- [ ] 1.2 Create `dev/.env.example` with `POSTGRES_USER=postgres`, `POSTGRES_PASSWORD=postgres`, `POSTGRES_DB=nearyou_dev`, plus stub placeholders for `KTOR_RSA_PRIVATE_KEY=`, `SUPABASE_JWT_SECRET=`, `GOOGLE_CLIENT_IDS=`, `APPLE_BUNDLE_IDS=`, `DB_URL=jdbc:postgresql://localhost:5432/nearyou_dev`, `DB_USER=postgres`, `DB_PASSWORD=postgres`, `KTOR_ENV=development`
- [ ] 1.3 Add `dev/.env` to `.gitignore`
- [ ] 1.4 Create `dev/scripts/generate-rsa-keypair.sh` (POSIX sh, uses `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`, base64-encodes, prints `KTOR_RSA_PRIVATE_KEY=<base64>`)
- [ ] 1.5 Create `dev/scripts/seed-test-user.sh` (psql one-liner inserting a row with required NOT-NULLs; takes `--google-id-hash` or `--apple-id-hash` arg; prints generated UUID)
- [ ] 1.6 Create `dev/README.md` documenting: `cp .env.example .env`, generate keys, `docker compose up -d`, `./gradlew flywayMigrate`, seed user, `./gradlew run`
- [ ] 1.7 Smoke test: `docker compose -f dev/docker-compose.yml up -d` then `psql` with the example creds connects successfully

## 2. Module setup

- [ ] 2.1 Create `infra/supabase/build.gradle.kts` applying `id("nearyou.kotlin.jvm")`, depends on `projects.core.domain`, `projects.core.data`, `libs.kotlinx.coroutines.core`, `libs.hikaricp`, `libs.postgresql`
- [ ] 2.2 Add `include(":infra:supabase")` to root `settings.gradle.kts`
- [ ] 2.3 Add `:infra:supabase` as `implementation` dependency in `backend/ktor/build.gradle.kts`
- [ ] 2.4 Add JWT deps to `backend/ktor/build.gradle.kts`: `libs.ktor.serverAuth`, `libs.ktor.serverAuthJwt`, `libs.ktor.clientCore`, `libs.ktor.clientCio`, `libs.ktor.clientContentNegotiation` (extend `gradle/libs.versions.toml` first); also add `nimbus-jose-jwt` for HS256 + JWKS parsing if Ktor's built-in isn't sufficient
- [ ] 2.5 Run `./gradlew :infra:supabase:build` and `./gradlew :backend:ktor:build` — both green

## 3. Secret resolver

- [ ] 3.1 Refactor `backend/ktor/src/main/kotlin/id/nearyou/app/config/Secrets.kt`: keep `secretKey(env, name)` returning the *name*; add `class EnvVarSecretResolver` with `resolve(name: String): String?` reading `getenv(name.uppercase().replace('-', '_'))`
- [ ] 3.2 Add `interface SecretResolver { fun resolve(name: String): String? }` so the staging-bootstrap change can plug in GCP Secret Manager later
- [ ] 3.3 Register `SecretResolver` (env-var impl) in Koin
- [ ] 3.4 Add Kotest spec covering: env var present → returns value; env var missing → returns null; name normalization (`ktor-rsa-private-key` → `KTOR_RSA_PRIVATE_KEY`)

## 4. DB connection wiring

- [ ] 4.1 Create `infra/supabase/src/main/kotlin/id/nearyou/app/infra/db/DataSourceFactory.kt` returning a configured `HikariDataSource` from URL/user/password
- [ ] 4.2 Update `backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt` to read `DB_URL`/`DB_USER`/`DB_PASSWORD` from the environment via `environment.config` and register `DataSource` in the Koin module
- [ ] 4.3 Update `backend/ktor/src/main/resources/application.conf` to declare the three keys with `${?DB_URL}`, etc., substitution
- [ ] 4.4 Smoke test: `./gradlew :backend:ktor:run` against the live local Postgres boots successfully (no exception about missing DB env vars)

## 5. Flyway V2 migration

- [ ] 5.1 Create `backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql` containing the `users` table (every canonical column from `docs/05-Implementation.md` § Users Schema, including the 18+ CHECK), the documented indexes, and the `refresh_tokens` table + indexes
- [ ] 5.2 Append the `realtime.messages` RLS policy inside a `DO $$ IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'realtime') THEN ... END IF; $$;` block so it no-ops on plain Postgres. Inside the block use `DROP POLICY IF EXISTS participants_can_subscribe ON realtime.messages;` then `CREATE POLICY participants_can_subscribe ...` (Postgres has no `CREATE POLICY IF NOT EXISTS`, so DROP+CREATE is the idempotent pattern)
- [ ] 5.3 Run `DB_URL=… DB_USER=… DB_PASSWORD=… ./gradlew :backend:ktor:flywayMigrate` against local Postgres — succeeds
- [ ] 5.4 Verify via psql: `\dt public.users` and `\dt public.refresh_tokens` both present; `\d public.users` shows the 18+ CHECK; `flyway_schema_history` has rows for V1 and V2

## 6. JWT issuer + JWKS endpoint

- [ ] 6.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/auth/jwt/RsaKeyLoader.kt` reading the base64-PEM env var, parsing it with `KeyFactory`, and exposing `privateKey: RSAPrivateKey`, `publicKey: RSAPublicKey`, `kid: String` (defaults `"dev-1"`)
- [ ] 6.2 Create `JwtIssuer` with `issueAccessToken(userId: UUID, tokenVersion: Int): String` (RS256, 15-min TTL, claims `sub`/`iat`/`exp`/`token_version`, header `kid`)
- [ ] 6.3 Create `JwksController` exposing `GET /.well-known/jwks.json` returning `{ "keys": [ { kty: "RSA", kid, use: "sig", alg: "RS256", n, e } ] }` with `Cache-Control: public, max-age=3600`
- [ ] 6.4 Wire JWKS route in `Application.module()`
- [ ] 6.5 Kotest spec `JwtIssuerTest`: round-trip (issue → parse → assert claims); kid header present; `expires_in == 900`
- [ ] 6.6 Kotest spec `JwksControllerTest`: response body parses; key's modulus/exponent match the issuer's public key

## 7. Refresh-token storage + rotation

- [ ] 7.1 Define `data class RefreshTokenRow(...)` and `interface RefreshTokenRepository` in `:infra:supabase` covering: `insert`, `findByHash`, `markUsed`, `revokeFamily`, `revoke(id)`, `deleteAllForUser`
- [ ] 7.2 Implement `JdbcRefreshTokenRepository` with prepared statements for each. Add a single-line comment immediately above the `INSERT INTO refresh_tokens` prepared statement: `// TODO(attestation): tighten device_fingerprint_hash to NOT NULL once Play Integrity / App Attest lands (see openspec/changes/auth-foundation/design.md Open Questions)`
- [ ] 7.3 Create `RefreshTokenService` in `:backend:ktor`: `issue(userId, deviceFingerprintHash?)` returns the raw token + persists hashed; `rotate(rawToken)` implements 30-second overlap + reuse detection
- [ ] 7.4 On reuse: call `revokeFamily(familyId)`, then `UserRepository.incrementTokenVersion(userId)`, then return error
- [ ] 7.5 Kotest spec `RefreshRotationTest`: happy rotation; re-rotation within 30 s succeeds; re-rotation after 30 s revokes family + bumps `token_version`

## 8. User repository + token-version middleware

- [ ] 8.1 Define `data class UserRow(...)` and `interface UserRepository` in `:infra:supabase` covering: `findById`, `findByGoogleIdHash`, `findByAppleIdHash`, `incrementTokenVersion(userId)`
- [ ] 8.2 Implement `JdbcUserRepository`
- [ ] 8.3 Create `data class UserPrincipal(val userId: UUID, val tokenVersion: Int)` — plain data class, no superinterface (Ktor 3.x removed `io.ktor.server.auth.Principal`); routes retrieve via `call.principal<UserPrincipal>()`
- [ ] 8.4 Configure Ktor `Authentication` JWT verifier (RS256 with the public key from `RsaKeyLoader`) with a `validate { credential -> ... }` block that: parses `sub` and `token_version` from `credential.payload`, calls `UserRepository.findById`, then on any failure path **MUST `call.attributes.put(AuthFailureKey, "<code>")` BEFORE `return@validate null`** (codes: `token_revoked` for version mismatch / missing user, `account_banned` for `is_banned`, `account_suspended` for `suspended_until > NOW()`); returns `UserPrincipal(userId, tokenVersion)` on success
- [ ] 8.5 Define `val AuthFailureKey = AttributeKey<String>("auth.failure_code")`; install a single `challenge { _, _ -> ... }` block that reads `call.attributes.getOrNull(AuthFailureKey) ?: "token_revoked"` (default fallback covers the missing-Authorization-header path where `validate` never ran) and responds with the matching HTTP status (401 for `token_revoked`, 403 for `account_banned` / `account_suspended`) + JSON envelope `{ "error": { "code": "...", "message": "..." } }`
- [ ] 8.6 Kotest spec `AuthMiddlewareTest`: matching version → 200; mismatched version → 401 `token_revoked`; banned user → 403 `account_banned`; future `suspended_until` → 403 `account_suspended`

## 9. Sign-in endpoint

- [ ] 9.1 Create `GoogleIdTokenVerifier` and `AppleIdTokenVerifier` (both implement `interface ProviderIdTokenVerifier { suspend fun verify(idToken: String): VerifiedIdToken }`); each fetches its provider JWKS via Ktor HttpClient. The in-memory cache MUST respect the response's `Cache-Control: max-age=N` header when present and fall back to a 1-hour default when absent or unparseable
- [ ] 9.2 `aud` allow-list resolved from `application.conf` (`auth.google.audiences`, `auth.apple.audiences`) populated by `${?GOOGLE_CLIENT_IDS}` / `${?APPLE_BUNDLE_IDS}` (comma-separated)
- [ ] 9.3 Create `POST /api/v1/auth/signin` handler: parse body, verify ID token, hash provider sub, look up user, return `{ access_token, refresh_token, expires_in: 900 }` or 404/403 per spec
- [ ] 9.4 Kotest spec `SignInFlowTest` with stubbed `ProviderIdTokenVerifier`: existing user → 200; unknown user → 404 `user_not_found`; banned user → 403; invalid id_token → 401 `invalid_id_token`

## 10. Refresh / logout / logout-all routes

- [ ] 10.1 `POST /api/v1/auth/refresh` (unauthenticated, takes refresh token in body) → calls `RefreshTokenService.rotate`, returns new tokens
- [ ] 10.2 `POST /api/v1/auth/logout` (authenticated, takes refresh token in body) → revokes that token row only
- [ ] 10.3 `POST /api/v1/auth/logout-all` (authenticated) → `RefreshTokenRepository.deleteAllForUser` + `UserRepository.incrementTokenVersion`
- [ ] 10.4 Kotest spec covering all three routes' happy paths and error cases

## 11. Realtime token endpoint

- [ ] 11.1 Create `SupabaseRealtimeTokenIssuer` minting HS256 with `secretKey(env, "supabase-jwt-secret")`-resolved secret, claims `{ sub, role: "authenticated", iat, exp }`, TTL 1 h
- [ ] 11.2 `GET /api/v1/realtime/token` inside `authenticate { ... }`, returns `{ token, expires_in: 3600 }`
- [ ] 11.3 Kotest spec: authenticated call returns valid HS256 token verifying under the configured secret; unauthenticated → 401

## 12. Apple S2S endpoint stub

- [ ] 12.1 `POST /internal/apple/s2s-notifications`: parse Apple JWT envelope, verify signature against Apple JWKS, validate `aud` against bundle id, dedup via `transaction_id` (in-memory dedup is fine in dev; persistent dedup deferred)
- [ ] 12.2 Branch on event type: `email-enabled`/`email-disabled` → update `users.apple_relay_email` flag for matching `apple_id_hash`, return 200; other event types → log warn, return 501 with `not_implemented` body
- [ ] 12.3 Kotest spec covering both branches with stubbed Apple JWKS

## 13. Health probe upgrade

- [ ] 13.1 Inject `DataSource` into `HealthRoutes`; replace `/health/ready` body with the documented `withTimeoutOrNull(500) { SELECT 1 }` pattern
- [ ] 13.2 Update existing `HealthRoutesTest` to cover both branches (real Postgres reachable, simulated unreachable via timeout-zero)

## 14. End-to-end verification

- [ ] 14.1 `./gradlew clean ktlintCheck build test` from repo root — all green
- [ ] 14.2 Bring up `dev/docker-compose.yml`, run `flywayMigrate`, seed a user, manually `curl -X POST .../signin` with a stub Google id_token (using a mocked JWKS via `GOOGLE_JWKS_URL_OVERRIDE` env var if needed) — receives access + refresh
- [ ] 14.3 `curl /health/ready` returns 200 with Postgres up; stop the container, `curl /health/ready` returns 503; restart container, returns 200 again
- [ ] 14.4 Update `dev/README.md` if any step diverged during implementation
- [ ] 14.5 Stage and commit changes in a single commit titled `feat(auth): foundation — JWT, refresh, signin, realtime token, V2 schema, local dev`
