## Context

Build infrastructure landed in the previous change (`bf09f43`): Ktor skeleton, Koin, Flyway with V1 placeholder, ktlint, CI. There is no `users` table yet, no DB connection, no JWT issuer, no local DB to run against. Every subsequent feature (posts, chat, follows) needs a `sub` claim and a real DB. This change is the bridge from "Ktor responds 200 on /health" to "Ktor signs in real users against a real Postgres."

The architecture doc commits us to RS256 access tokens (15 min) + 30-day refresh tokens with reuse detection, `users.token_version` for instant revocation, Google/Apple ID-token verification, and a Supabase-compatible HS256 token for chat. The implementation doc gives canonical schemas and rotation logic. We follow them with the cuts noted in proposal.md (no signup, no Redis cache, plain Postgres-only local dev).

## Goals / Non-Goals

**Goals:**
- A locally-runnable backend that accepts a real Google or Apple ID token (or a stubbed JWKS in tests) and issues working access + refresh tokens.
- `users` and `refresh_tokens` schemas as canonically defined, applied via `V2__auth_foundation.sql`.
- Refresh-token rotation with reuse detection that revokes the entire family + bumps `token_version`.
- `/health/ready` upgraded to actually probe the DB.
- Local dev is one `docker compose up` away from working.
- The auth surface is complete enough for a chat change to add WSS without re-touching backend auth.

**Non-Goals:**
- Signup / user creation. Existing users only (seeded via SQL for tests).
- Mobile UI for sign-in.
- Device attestation, age gate, `rejected_identifiers`, analytics consent, FCM tokens, username generation, rate limiting, suspension worker, Apple S2S deletion handlers, `/internal/cleanup` job.
- Redis cache for `token_version` (DB hit per request acceptable at MVP volume).
- Cloud Run deploy, Cloud Run Jobs, GCP Secret Manager wiring (env-var fallback only).
- RLS test suite (chat-shaped; lands with chat).
- Anomaly-detection metrics (Sentry / Slack alerting) and JWT key-rotation tooling.
- Supabase CLI for local dev (we use plain Postgres+PostGIS; revisit when chat lands).

## Decisions

### 1. Local dev stack: plain Postgres+PostGIS + Redis, not Supabase CLI

Architecture doc § Deployment > dev says Supabase CLI for local parity. But:
- Auth bypasses Supabase Auth (Ktor verifies Google/Apple directly).
- Realtime / PostgREST aren't called by the backend.
- PostGIS is the only Supabase component we actually depend on (later, for spatial queries).

`postgis/postgis:16-3.4` boots in ~3 seconds vs Supabase CLI's full stack (~30 seconds + auth/realtime/postgrest noise). Smaller mental load, faster feedback.

**Tradeoff:** when chat lands and we want to test against real Supabase Realtime locally, we'll need to re-introduce the Supabase CLI (or stub the WSS server). Acceptable cost; that's a single later change. Confirmed with user.

`redis:7-alpine` is included in compose now even though no code path uses it yet — adding a service later costs another change. Idle cost is negligible.

### 2. `:infra:supabase` is just an interface module in this change

Per architecture's dependency-isolation pattern, all DB access goes through `:infra:supabase`. This change adds:
- Interface `UserRepository` (lookup by id / by `google_id_hash` / by `apple_id_hash`; bump `token_version`).
- Interface `RefreshTokenRepository` (insert, find by hash, mark used, revoke family).
- Concrete implementations using HikariCP + JDBC live in the SAME module for now (no separate `:infra:supabase-impl`). When we swap to Supabase Realtime SDK or jOOQ, that's a follow-up split.

**Alternative considered:** put repos directly in `:backend:ktor`. Rejected — would couple every backend feature to JDBC, blocking the swap-to-jOOQ / swap-to-Neon options the architecture doc explicitly preserves.

### 3. RS256 keys: env-var-PEM in dev, GCP Secret Manager later

`secretKey(env, name)` already exists as a name-resolver. We add a `SecretResolver` that takes the resolved name and returns the value:
- Dev: `getenv("KTOR_RSA_PRIVATE_KEY")` (base64-encoded PEM); error if missing.
- Future (staging/prod): GCP Secret Manager client. Out of scope here.

The keypair is loaded once at startup, parsed, and held in memory. Public key is exposed at `/.well-known/jwks.json` with the configured `kid` (defaults to `"dev-1"` for dev).

`dev/scripts/generate-rsa-keypair.sh` creates a fresh 2048-bit RSA pair, base64-encodes the PEM, and prints the line ready for paste into `dev/.env`. Run-once-per-developer.

### 4. Refresh-token storage: hash-only, family_id grouping

Token row stores `token_hash` (SHA-256 of the raw token bytes) and `family_id`. Raw token never persisted (only ever returned to client at issuance). On rotation, the new token gets the same `family_id`; reuse detection scans by `family_id`.

The 30-second overlap window is implemented as: a "used" token is still acceptable if `used_at >= NOW() - INTERVAL '30 seconds'`. Beyond that → reuse → family revocation.

### 5. Token-version check: middleware, no cache

Auth middleware runs inside the Ktor `Authentication` JWT provider's `validate { credential -> ... }` block:
1. JWT signature + standard claims are already verified by Ktor before `validate` runs.
2. Read claim `token_version` from `credential.payload`.
3. `SELECT token_version, is_banned, suspended_until FROM users WHERE id = ?`.
4. Return `null` from `validate` (Ktor → 401) if JWT version != DB version, or if `is_banned`, or if `suspended_until > NOW()`.
5. Otherwise return `UserPrincipal(userId, tokenVersion)`. Routes retrieve via `call.principal<UserPrincipal>()`.

**Ktor 3.x note**: the `io.ktor.server.auth.Principal` marker interface was removed in 3.0 — `UserPrincipal` is a plain data class with no superinterface. `call.principal<T>()` still works; lookup is by type, not by `instanceof Principal`. The error-code differentiation (`token_revoked` vs `account_banned` vs `account_suspended`) can't be expressed by returning `null` from `validate` (Ktor's challenge fires a single 401). The chosen workaround:

1. Define `val AuthFailureKey = AttributeKey<String>("auth.failure_code")`.
2. Inside `validate { credential -> ... }`, on any failure path call `call.attributes.put(AuthFailureKey, "<code>")` **before** `return@validate null`. The failure code is the documented one (`token_revoked` | `account_banned` | `account_suspended`).
3. Install a single `challenge { _, _ -> ... }` block that reads `call.attributes.getOrNull(AuthFailureKey) ?: "token_revoked"`. The `?:` fallback covers the missing-Authorization-header path (no token at all → `validate` never ran → no attribute set → default to `token_revoked`).

Alternative considered: bypass `Authentication` plugin and write a custom route plugin. Cleaner separation but loses the `authenticate { ... }` DSL. Rejected.

One DB hit per authenticated request. At MVP volume (no users yet → tens during seed testing → hundreds in alpha), this is irrelevant. Cache lands when Redis is wired.

### 6. Google / Apple ID-token verification

- **Google**: fetch `https://www.googleapis.com/oauth2/v3/certs` (cache 1h respecting Cache-Control), verify RS256 signature, validate `aud` ∈ allowed client IDs (configured per env), `iss` ∈ {`accounts.google.com`, `https://accounts.google.com`}, `exp > now`, `email_verified` true. Use `sub` as the provider subject.
- **Apple**: fetch `https://appleid.apple.com/auth/keys` (cache 1h), verify, `aud` = bundle ID, `iss` = `https://appleid.apple.com`, `exp > now`. `sub` is the provider subject. Apple emails we don't trust (relay handling lands with email features); store whatever's in the token if non-null.

Provider subject is hashed (`SHA-256`) before stored as `google_id_hash` / `apple_id_hash`. Hashing prevents the raw provider ID from leaking via DB dumps; it's still deterministic for lookup.

JWKS clients live in their own small module-internal classes; their HTTP fetch uses Ktor's HttpClient, no external lib needed.

**Allowed client IDs** are read from `application.conf` via `${?GOOGLE_CLIENT_IDS}` (comma-separated) and `${?APPLE_BUNDLE_IDS}`. Empty in dev unless tests need it.

### 7. Apple S2S endpoint: skeleton only

Endpoint exists with full signature verification + dedup (so the wiring is right when deletion handlers land), but only `email-enabled` / `email-disabled` mutate state. Other event types respond `501 Not Implemented` with body `{ "error": { "code": "not_implemented", "message": "<event_type> deferred to deletion-flows change" } }` so Apple's automated test will see consistent behavior; manual Apple Developer Console registration cannot be completed until those handlers exist. Documented in proposal Flag #4.

### 8. `/health/ready` real probe

Replaces the stub from `backend-bootstrap`:
```kotlin
get("/health/ready") {
    val ok = withTimeoutOrNull(500) {
        dataSource.connection.use { it.createStatement().executeQuery("SELECT 1").next() }
    } ?: false
    if (ok) call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    else call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "degraded"))
}
```
Future deps (Redis, Supabase Realtime HTTP probe per architecture doc) get added by their respective changes — each one bolts a parallel `async { ... }` into `coroutineScope`.

### 9. Migration strategy: V2 lands the full canonical schema

Even though signup is deferred, the V2 migration creates `users` with every canonical column and constraint. Later changes don't have to do `ALTER TABLE users ADD COLUMN ...` for fields that were always going to exist; they just start using them. Defaults (DEFAULT FALSE, DEFAULT '{...}', etc.) make the schema accept any test insertion that supplies the minimum set.

Test seeding uses raw INSERTs that satisfy NOT-NULL columns. A `dev/scripts/seed-test-user.sh` helper exists for local dev — generates a UUID, inserts a row with `username = 'tester_<8hex>'`, `date_of_birth = '1990-01-01'`, `invite_code_prefix = '<random8>'`, and your choice of `google_id_hash`. Documented as the dev workaround until signup lands.

### 10. RLS policy in V2 even though chat doesn't exist

The `participants_can_subscribe` policy on `realtime.messages` is created in V2 (idempotent: `CREATE POLICY IF NOT EXISTS`). Two reasons:
- Co-locates the auth-related guard with the auth schema.
- Forces us to confirm the `realtime` schema exists in plain Postgres+PostGIS (it doesn't! — see Risk 3).

Postgres does not support `CREATE POLICY IF NOT EXISTS`. The migration uses `DROP POLICY IF EXISTS participants_can_subscribe ON realtime.messages;` followed by `CREATE POLICY participants_can_subscribe ...` for idempotency, all wrapped in a `DO $$ IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'realtime') $$` block so plain Postgres+PostGIS sees the entire block as a no-op. Re-running `flywayMigrate` against the same Supabase DB drops + recreates the policy with no observable side effects.

## Risks / Trade-offs

- **Stub deletion handlers in S2S endpoint** → Apple Developer Console rejects the registration. **Mitigation:** registration only matters at production launch; deletion-flows change must land before submission. Documented in proposal Flag #4.
- **No Redis cache for token_version** → adds one DB query per authenticated request. **Mitigation:** at MVP volume it's <1 ms overhead. Cache lands with Redis usage in rate-limiting change.
- **`realtime` schema absent in plain Postgres+PostGIS** → V2 RLS policy creation can't fail loudly. **Mitigation:** wrap in `DO $$ IF schema 'realtime' exists $$`. The policy is still in V2 so it applies when run against Supabase. Tradeoff: dev never runs the policy; chat change must verify against a Supabase-shaped DB before merging.
- **Refresh tokens grow unbounded without `/internal/cleanup`** → table bloat. **Mitigation:** zero impact at MVP (no users); flag in proposal as known debt; lands with the deploy-pipeline change.
- **Google/Apple JWKS client cache: 1h means key-rotation lag** → if Google/Apple rotate within a 1h window we may briefly reject valid tokens. **Mitigation:** 1h matches industry standard (auth0, firebase do the same); rare event; clients retry-after-refresh handles it.
- **One `KTOR_RSA_PRIVATE_KEY` env var collides with future kid rotation** → only one kid in dev. **Mitigation:** rotation tooling is post-launch; revisit when needed.
- **`secretKey` resolver only handles env vars** → staging/prod will need GCP Secret Manager. **Mitigation:** the `SecretResolver` interface admits a second implementation; staging-bootstrap change adds it.
- **Compose file in `dev/` may drift from CI** → developers see different behavior than CI. **Mitigation:** CI doesn't run integration tests against Postgres in this change (deferred — `./gradlew test` covers unit tests only via Kotest). When integration tests land, CI gets the same Postgres service container.

## Migration Plan

1. New `:infra:supabase` module; add to `settings.gradle.kts`.
2. Extend `secretKey` to a `SecretResolver` returning *values*, not just names.
3. `dev/docker-compose.yml`, `dev/.env.example`, `dev/scripts/generate-rsa-keypair.sh`, `dev/scripts/seed-test-user.sh`, `dev/README.md`.
4. `application.conf` learns DB connection (URL/user/password from env, all required at boot).
5. Wire HikariCP `DataSource` as a Koin singleton.
6. `V2__auth_foundation.sql` — `users` + `refresh_tokens` + RLS-policy-when-realtime-exists.
7. JWT issuer + verifier + JWKS controller.
8. Repos in `:infra:supabase`.
9. Sign-in / refresh / logout / logout-all routes; auth middleware; `UserPrincipal`.
10. Realtime-token route (HS256).
11. Apple S2S endpoint skeleton.
12. `/health/ready` upgrade.
13. Kotest specs.
14. End-to-end: bring up compose, seed a user, run a fake-Google JWKS test that mints + verifies a token.

**Rollback:** revert the commit; `dev/.env`, generated keys, and the local Postgres volume are dev-only artifacts (gitignored). No production state.

## Open Questions

- Should `refresh_tokens.device_fingerprint_hash` be required (NOT NULL) at insert time even though attestation-derived fingerprints aren't computed yet? (Current decision: nullable; clients pass `device_fingerprint_hash = null`. When attestation lands, we add a CHECK or app-layer validation.)
- Should the auth middleware emit a structured log event on every revocation? (Current decision: yes, info-level structured log; full Sentry/Slack alerting is the observability change's job.)
- Do we want a `POST /api/v1/auth/whoami` debug endpoint that returns the principal? Useful in dev, easy to gate behind `KTOR_ENV != "production"`. (Current: leave out; trivial to add later.)
