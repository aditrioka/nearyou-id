## Why

Push notifications are a Phase 2 feature, but the **client-to-server token-registration handshake** is a Phase 1 prerequisite ([`docs/08-Roadmap-Risk.md:90`](docs/08-Roadmap-Risk.md) Phase 1 item 18). Without a `POST /api/v1/user/fcm-token` endpoint and a `user_fcm_tokens` table, no Phase 2 push-send path can target a device, and no Phase 3.5 stale-token cleanup worker has rows to scan. The schema and endpoint contract are pre-specified in canonical docs ([`docs/05-Implementation.md:1372-1400`](docs/05-Implementation.md) FCM Token Registration § + [`docs/04-Architecture.md:515-527`](docs/04-Architecture.md)) but have never been built. Shipping the registration capability now — independent of the Phase 2 send path and the Phase 3.5 cleanup worker — closes the Phase 1 gap with minimal blast radius and unblocks the next Phase 2 cycle when push-send work begins.

## What Changes

- New Flyway migration `V14__user_fcm_tokens.sql` adding the `user_fcm_tokens` table — schema columns / types / indexes / FK / UNIQUE matching the canonical schema at [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md), with two **additive** DB-level CHECK constraints (defense in depth, see `design.md` D9):
  - `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
  - `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
  - `platform VARCHAR(8) NOT NULL CHECK (platform IN ('android', 'ios'))`
  - `token TEXT NOT NULL CHECK (char_length(token) BETWEEN 1 AND 4096)` *(additive — mirrors the app-side body cap at the DB)*
  - `app_version TEXT CHECK (app_version IS NULL OR char_length(app_version) <= 64)` *(additive — mirrors the app-side cap at the DB)*
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  - `last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  - `UNIQUE (user_id, platform, token)`
  - Indexes: `user_fcm_tokens_user_idx ON (user_id)`, `user_fcm_tokens_last_seen_idx ON (last_seen_at)`
- New endpoint `POST /api/v1/user/fcm-token` — JWT-required, accepts `{ token, platform, app_version }`, upserts on the `(user_id, platform, token)` UNIQUE constraint, refreshes `last_seen_at = NOW()` on every call (the authoritative freshness signal per [`docs/05-Implementation.md:1394`](docs/05-Implementation.md)). Returns `204 No Content` on success (resolved per `design.md` D5).
- Validation (three layers — handler / DTO / DB CHECK):
  - DTO declares `token` and `platform` as **non-nullable** required fields. Missing either → deserialization fails → `400 {"error": "malformed_body"}`.
  - Handler: `platform` ∈ {`android`, `ios`} case-sensitive (else `400 "invalid_platform"`); `token` non-empty after trim (else `400 "empty_token"`); `app_version` optional, max 64 chars (else `400 "app_version_too_long"`).
  - DB CHECK: token length 1–4096; app_version length ≤ 64. Defense in depth against non-route writes.
  - **Transport-layer body cap of 4 KB** (distinct from the field-level [`ContentLengthGuard`](backend/ktor/src/main/kotlin/id/nearyou/app/guard/ContentLengthGuard.kt) which is for authored-text fields). Enforced via Ktor's `RequestBodyLimit` plugin OR a defensive `contentLength()` check at the handler top — see `design.md` D10.
  - **WARN-log redaction**: the raw `token` value is NEVER written to logs (FCM tokens are device-addressed credentials — see `design.md` D11).
- New capability `fcm-token-registration` (per the precedent of one capability per endpoint family — `health-check`, `premium-search`, `client-ip-extraction`).
- **Module placement**: this change creates a new `user/` package under [`backend/ktor/src/main/kotlin/id/nearyou/app/`](backend/ktor/src/main/kotlin/id/nearyou/app/) (sibling of `auth/`). The User module per [`docs/04-Architecture.md:129`](docs/04-Architecture.md) owns FCM token registration, but the package doesn't yet exist (existing siblings: `auth/`, `block/`, `common/`, `config/`, `dev/`, `engagement/`, `follow/`, `guard/`, `health/`, `lint/`, `moderation/`, `notifications/`, `post/`, `search/`, `timeline/`). Rationale for `user/` over folding into `auth/`: signup + JWT issuance are the auth lifecycle; FCM token registration is user-profile state. See `design.md` § Constraints.
- Tests cover: happy-path insert; idempotent re-registration of the same `(user_id, platform, token)` triple refreshes `last_seen_at` without creating a duplicate row; same user can register multiple tokens (e.g., one Android + one iOS for cross-device); the same token registered by a different `user_id` produces a separate row (the UNIQUE is per-`(user_id, platform, token)`, not per-`token` — supports family-shared devices, see `design.md` D1); platform CHECK rejects `web` / empty / mixed-case `Android`; missing JWT → `401`; missing required fields → `400 "malformed_body"`; malformed JSON → `400 "malformed_body"`; ON DELETE CASCADE verified by deleting a user and asserting tokens gone; DB CHECK on token length (4097-char direct insert rejected); DB CHECK on app_version length (65-char direct insert rejected); WARN logs redact the raw token; structured log emits `created` (insert vs update) AND `user_token_count` (per-user row count for the D3 tripwire); no new rate-limit category (see `design.md` D3).

**Out of scope** (explicit non-goals — each will land in a separate change cycle):
- The send-side FCM Admin SDK integration (Phase 2 push infrastructure — [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) Phase 2 item 7).
- The 404/410 immediate-delete path on send (Phase 2 — needs the send path first; the `DELETE FROM user_fcm_tokens WHERE ...` SQL itself is a one-liner, but the call site lives inside the FCM-send helper that does not yet exist).
- The stale-30-day weekly cleanup worker at `/internal/cleanup` ([`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) Phase 3.5 item 10 — needs `/internal/*` OIDC middleware, also not yet shipped).
- APNs `.p8` key setup and the iOS Notification Service Extension (Phase 3 mobile work).
- Mobile-side FCM SDK token-refresh callback wiring (Phase 3 mobile work — the endpoint contract is the boundary).
- Per-user token-cap enforcement (e.g., max N tokens per user). Documented as an open question; default is no cap, relying on `ON DELETE CASCADE` from `users` and the future stale-cleanup worker. See `design.md` D2.

## Capabilities

### New Capabilities
- `fcm-token-registration`: `POST /api/v1/user/fcm-token` endpoint + `user_fcm_tokens` schema. Defines the upsert-on-unique contract, `last_seen_at` refresh-on-every-call freshness semantic, the `platform` enum closed-set, validation error vocabulary, and the UNIQUE-key-on-`(user_id, platform, token)` rationale (supports multi-device per user AND family-shared devices across users). Documents the deferred stale-cleanup worker contract (`last_seen_at < NOW() - 30d` weekly delete) and the deferred on-send-failure delete contract (`404`/`410` from FCM → immediate `DELETE` of the specific row) so future changes know the freshness-column and unique-constraint design intent.

### Modified Capabilities
None.

## Impact

- **Code** (creates new `user/` package; this change is the first inhabitant — see `design.md` § Constraints for the package-creation rationale):
  - `backend/ktor/src/main/resources/db/migration/V14__user_fcm_tokens.sql` — new Flyway migration with the table + two indexes + DB CHECKs from D9.
  - `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRoutes.kt` — new Ktor route handler. Installs its own `authenticate(AUTH_PROVIDER_USER) { ... }` block (per the per-route-file precedent at [`engagement/LikeRoutes.kt:42-44`](backend/ktor/src/main/kotlin/id/nearyou/app/engagement/LikeRoutes.kt:42), [`engagement/ReplyRoutes.kt`](backend/ktor/src/main/kotlin/id/nearyou/app/engagement/ReplyRoutes.kt), etc.) — there is **no shared `route("/api/v1/user")` block** today.
  - `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRepository.kt` — repository wrapping the upsert SQL (`INSERT ... ON CONFLICT (user_id, platform, token) DO UPDATE SET last_seen_at = NOW(), app_version = EXCLUDED.app_version RETURNING xmax = 0 AS created, (SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = $1) AS user_token_count`).
  - `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRequest.kt` — `@Serializable` request DTO with `token: String` and `platform: String` non-nullable + `app_version: String? = null` optional.
  - `backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt` — Koin binding `single { FcmTokenRepository(get()) }`; top-level route registration `fcmTokenRoutes(get<FcmTokenRepository>())` (the route file installs its own `authenticate` block internally).
  - `backend/ktor/src/test/kotlin/id/nearyou/app/user/FcmTokenRoutesTest.kt` — full test coverage matrix described above (including DB-CHECK direct-insert tests and WARN-log redaction assertion).
- **CI/CD**: none. The migration runs through the existing `nearyou-migrate-staging` Cloud Run Job pre-deploy.
- **Configuration**: none new.
- **Dependencies**: none new. Uses the existing JDBC + HikariCP + Ktor + JWT auth stack.
- **Observability**: a single `INFO`-level log line per successful registration (`fcm_token_registered user_id=<...> platform=<...> created=<true|false> user_token_count=<n>`). The `created` field distinguishes initial-insert from `last_seen_at`-refresh (Phase 2 push-send debugging). The `user_token_count` field powers the D3 tripwire — log-aggregation panel `count(event="fcm_token_registered") by user_id, time_bucket(1h)` (or any user with `user_token_count` growing pathologically) signals when the deferred per-user rate-limit cap should be wired. No new metrics surface; existing JWT-fail and request-error counters cover failure modes.
- **Risk**: low. Endpoint is JWT-gated (no unauthenticated surface), single-table write with idempotent upsert semantics (no race-condition surface — `INSERT ... ON CONFLICT DO UPDATE` is atomic), and the schema has a clean `ON DELETE CASCADE` from `users` (no GC complexity). Largest blast-radius vector is a misconfigured `platform` validation accepting an unknown value into the DB CHECK constraint, which would fail at `INSERT` time with a Postgres CHECK violation — caught by the test matrix and surfaced as a `500` not a corruption (the request handler should pre-validate to return `400`, but the DB is the second line of defense).
