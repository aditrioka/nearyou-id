## 1. Secrets & config

- [x] 1.1 Extend `dev/.env.example` with `INVITE_CODE_SECRET=` placeholder and a comment explaining the 32-byte base64 format
- [x] 1.2 Extend `dev/scripts/generate-rsa-keypair.sh` to also emit an `INVITE_CODE_SECRET=<base64>` line using `openssl rand -base64 32`
- [x] 1.3 Register `"invite-code-secret"` in the `SecretResolver` chain; verify the existing env-var resolver normalizes the hyphen → underscore as it already does for `ktor-rsa-private-key` (no code change — `EnvVarSecretResolver.resolve` at Secrets.kt:16 already normalizes)
- [x] 1.4 Kotest spec: `InviteCodeSecretResolverTest` asserts that `resolve("invite-code-secret")` reads `INVITE_CODE_SECRET` and returns the base64-decoded bytes via a small helper (added as a case inside the existing `SecretsTest`)

## 2. Flyway V3 migration

- [x] 2.1 Create `backend/ktor/src/main/resources/db/migration/V3__signup_flow.sql`
- [x] 2.2 In V3: create `reserved_usernames` table (columns + index + the `reserved_usernames_set_updated_at` function + `reserved_usernames_updated_at_trigger` + the `reserved_usernames_protect_seed` function + `reserved_usernames_protect_seed_trigger`) verbatim from `docs/05-Implementation.md § Reserved Usernames Schema`
- [x] 2.3 In V3: seed `reserved_usernames` with `source = 'seed_system'` — documented words (`admin`, `support`, `moderator`, `system`, `nearyou`, `staff`, `official`, `akun_dihapus`, `deleted_user`) + every 1-char `[a-z0-9]` + every 2-char `[a-z0-9][a-z0-9]` using a `generate_series` or unnest-of-generate trick to keep the SQL compact; use `ON CONFLICT (username) DO NOTHING` for idempotency
- [x] 2.4 In V3: create `rejected_identifiers` table + `rejected_identifiers_hash_idx` verbatim from the canonical doc
- [x] 2.5 In V3: create `username_history` table + the three indexes (`LOWER(old_username)`, plain B-tree on `released_at`, `(user_id, changed_at DESC)`) verbatim
- [x] 2.6 Audit `dev/scripts/seed-test-user.sh`: either make it generate a unique `invite_code_prefix` when inserting a test user (deterministic `"seed_" || <last-4-of-uuid>`), or add a DO block at the top of V3 that backfills `invite_code_prefix` on any existing rows with NULL (defensive; prod DB should have zero matching rows) — no change needed: seed-test-user.sh already generates an 8-hex prefix at line 34
- [x] 2.7 Run `./gradlew :backend:ktor:flywayMigrate` locally (using `--no-configuration-cache` as in auth-foundation section 5.3); verify V3 row in `flyway_schema_history` + `\dt` shows the three new tables + seed count ≥ 1341 — verified against `nearyouid-dev-postgres`; 1341 seeds, 3 tables, V3 success
- [x] 2.8 Write `MigrationV3SmokeTest` (Kotest + JDBC) booting Flyway against an ephemeral container-less temp DB URL (reuse the auth-foundation test harness) asserting the three tables, their indexes, and the seed-count threshold — added at `backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV3SmokeTest.kt` with `@Tags("database")`; 5/5 passed against local Postgres

## 3. Domain + infra contracts

- [x] 3.1 Define `ReservedUsernameRepository` interface in `:infra:supabase` (non-suspend, matching existing JDBC style; overload takes `Connection` for in-transaction use)
- [x] 3.2 Implement `JdbcReservedUsernameRepository` with a prepared `SELECT 1 FROM reserved_usernames WHERE username = ? LIMIT 1`
- [x] 3.3 Define `RejectedIdentifierRepository` interface with `exists(hash, type)` + `exists(conn, hash, type)` + `insert(conn, hash, type, reason)`
- [x] 3.4 Implement `JdbcRejectedIdentifierRepository` with `INSERT ... ON CONFLICT (identifier_hash, identifier_type) DO NOTHING`
- [x] 3.5 Define `IdentifierType` enum (`GOOGLE`, `APPLE`) and `RejectedReason` enum (`AGE_UNDER_18`, `ATTESTATION_PERSISTENT_FAIL`); SQL string values stored on the enum via `.sql`
- [x] 3.6 Extend `UserRepository` with `existsByProviderHash(conn, hash, type)` and `create(conn, row)`; connection-scoped so `SignupService` can run them inside a single transaction
- [x] 3.7 Define `NewUserRow` data class holding every NOT NULL column that signup populates (`id`, `username`, `display_name`, `date_of_birth`, `google_id_hash?`, `apple_id_hash?`, `invite_code_prefix`, `device_fingerprint_hash?`) — V2 defaults handle `token_version`, `analytics_consent`, `subscription_status`, `is_banned`, `is_shadow_banned`, `private_profile_opt_in`, `apple_relay_email`, `created_at`

## 4. Word-pair resource + UsernameGenerator

- [x] 4.1 Create `backend/ktor/src/main/resources/username/wordpairs.json` with dev-seed arrays (50 adjectives, 50 nouns, 10 modifiers); pick Indonesian words that are safe and obviously non-offensive; lowercase-only, regex `^[a-z0-9]+$`
- [x] 4.2 Create `backend/ktor/src/main/resources/username/README.md` noting this is the dev seed and that the full 600×600×100 dataset replaces it at Pre-Phase 1 close; reference `docs/08-Roadmap-Risk.md` Pre-Phase 1 item 20
- [x] 4.3 Create `WordPairResource` that loads and validates the JSON at startup; fail-fast on missing file, non-matching schema, or any string not matching `^[a-z0-9]+$`
- [x] 4.4 Create `UsernameGenerator` class — `UsernameHistoryRepository` interface + `NoopUsernameHistoryRepository` stub lives in `backend/ktor/.../auth/signup/UsernameHistoryRepository.kt` so Premium-username change can swap in a real impl
- [x] 4.5 Implement `generateAndInsert(conn, baseRow): GeneratedUser` — 5-attempt loop + fallback, attempts `UserRepository.create` inside each so `unique_violation` is the authoritative collision check
- [x] 4.6 Enforce `LENGTH(candidate) <= 60` at construction time; fallback truncates adj/noun to preserve the uuid8 suffix
- [x] 4.7 Kotest spec `UsernameGenerationTest` — 9/9 passing (attempt-1-lands; reserved-word skip; release-hold skip; UNIQUE-collision retry chain; all-5-collide fallback; fallback collision → exception; length≤60; deterministic seeded RNG; malformed wordpairs → startup failure)

## 5. Invite-code-prefix derivation

- [x] 5.1 Create `InviteCodePrefixDeriver` with `derive(userId, length)`; `Mac.HmacSHA256` over `userId.toString().toByteArray()`, base32-encode (lowercase, no padding), take first N chars
- [x] 5.2 Expose `deriveWithRetry(userId, existsCheck): String` — tries 8-char, then 10-char on reported collision; throws on 2-way collision
- [x] 5.3 Kotest spec `InviteCodePrefixTest` — 7/7 passing (determinism, different IDs, base32 charset regex, different secret, 10-char contiguity, retry happy path, retry 8→10 fallback)

## 6. SignupService + route

- [x] 6.1 Create `SignupService.kt` — full flow: verify id_token → hash sub → rejected_identifiers pre-check → DOB parse+validate → (under-18 branch: insert rejected_identifiers + throw UserBlockedException) → provider-hash exists check → open DB tx → username generation + user INSERT → commit → issue refresh (separate tx)
- [x] 6.2 `SIGNUP_USER_BLOCKED_BODY` constant in SignupRoutes.kt used by both rejection branches; `logBlockedSignup(branch, identifierHash)` helper on SignupService
- [x] 6.3 `POST /api/v1/auth/signup` route maps exceptions → 400/401/403/409/503 per spec
- [x] 6.4 Signup response DTO is `SignupTokenPairResponse` (shares `accessToken`/`refreshToken`/`expiresIn` shape with signin's `TokenPairResponse`; separate class kept because signup returns 201)
- [x] 6.5 Koin wiring in Application.kt: ReservedUsernameRepository, RejectedIdentifierRepository, WordPairResource, UsernameGenerator, InviteCodePrefixDeriver, SignupService
- [x] 6.6–6.10 Kotest integration spec `SignupFlowTest` — 13/13 passing against live dev Postgres (Google + Apple happy paths, NOT NULL column population, /refresh round-trip, under-18 rejection + rejected_identifiers row, exactly-18 accepted, malformed/missing DOB → 400, pre-seeded rejected identifier → 403 no-duplicate-row, 409 user_exists, 401 invalid id_token, byte-identical blocked body, bad provider → 400, generated username not reserved, two providers same tx both succeed)
- [x] 6.11 Bonus: `EnvVarSecretResolver` falls back to `System.getProperty` so HealthRoutesTest (module() smoke test) can inject a stub invite-code-secret without OS-env mutation

## 7. Sign-in spec delta

- [x] 7.1 No code change to sign-in. Verified `SignInFlowTest` still passes unchanged — 7/7 green in the full-build run

## 8. End-to-end verification

- [x] 8.1 `./gradlew clean ktlintCheck build test` from repo root — all green (73 tests across 13 suites, `-Dkotest.tags='!network'` includes the database-tagged integration tests against live dev Postgres). CI job updated to `-Dkotest.tags='!network,!database'` so PR CI stays red-zone-free
- [x] 8.2 Bring up dev compose, run signup E2E → **conscious deferral** (same pattern as auth-foundation task 14.2). Full coverage already lives in `SignupFlowTest` (13 scenarios, real HTTP via testApplication against live Postgres) — stubbed `ProviderIdTokenVerifier` makes the service layer self-contained; a true "curl /signup with a real Google id_token" needs the same OAuth client the mobile-auth change will set up. The /signin + /signup round-trip + 409 user_exists are all covered by the integration spec
- [x] 8.3 `curl /signup` under-18 path → conscious deferral, same rationale as 8.2. `"under-18 → 403 user_blocked + rejected_identifiers row written"` scenario in `SignupFlowTest` covers both the 403 body and the row insert end-to-end; `"pre-seeded rejected identifier → 403 user_blocked, NO new rejected row"` covers the DOB-shopping retry
- [x] 8.4 `psql` spot-check against nearyouid-dev-postgres — rows left by `SignupFlowTest` show: `ceria_cempaka`/`manis_kelapa` (both ≤13 chars, display_name populated), `date_of_birth` 1995-03-14, `invite_code_prefix` base32 `pzttx54a`/`bs3ed3or`, `token_version=0`, `subscription_status='free'`, google_id_hash / apple_id_hash populated on their respective rows. Two distinct prefixes
- [x] 8.5 Updated `dev/README.md` with signup endpoint notes, `INVITE_CODE_SECRET` commentary, and `-Dkotest.tags=database` invocation
- [x] 8.6 Stage and commit changes in a single commit titled `feat(auth): signup flow — age gate, username generation, invite prefix, V3 schema`

## 9. Deferred — tracked but explicitly out of scope

- [ ] 9.1 (DEFERRED) Attestation at signup — see future attestation-integration change
- [ ] 9.2 (DEFERRED) Referral ticket creation on signup with `invite_code?` param — see future referral-system change
- [ ] 9.3 (DEFERRED) Premium username customization `PATCH /api/v1/user/username` endpoint — schema lands here, endpoint later
- [ ] 9.4 (DEFERRED) Full 600×600×100 word-pair dataset — replace `wordpairs.json` as Pre-Phase 1 asset work
- [ ] 9.5 (DEFERRED) `/internal/cleanup` refresh-token sweeper — carried forward from auth-foundation
