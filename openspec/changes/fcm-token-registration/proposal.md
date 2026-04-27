## Why

Push notifications are a Phase 2 feature, but the **client-to-server token-registration handshake** is a Phase 1 prerequisite ([`docs/08-Roadmap-Risk.md:90`](docs/08-Roadmap-Risk.md) Phase 1 item 18). Without a `POST /api/v1/user/fcm-token` endpoint and a `user_fcm_tokens` table, no Phase 2 push-send path can target a device, and no Phase 3.5 stale-token cleanup worker has rows to scan. The schema and endpoint contract are pre-specified in canonical docs ([`docs/05-Implementation.md:1372-1400`](docs/05-Implementation.md) FCM Token Registration § + [`docs/04-Architecture.md:515-527`](docs/04-Architecture.md)) but have never been built. Shipping the registration capability now — independent of the Phase 2 send path and the Phase 3.5 cleanup worker — closes the Phase 1 gap with minimal blast radius and unblocks the next Phase 2 cycle when push-send work begins.

## What Changes

- New Flyway migration `V14__user_fcm_tokens.sql` adding the `user_fcm_tokens` table verbatim from [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md):
  - `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
  - `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
  - `platform VARCHAR(8) NOT NULL CHECK (platform IN ('android', 'ios'))`
  - `token TEXT NOT NULL`
  - `app_version TEXT`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  - `last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  - `UNIQUE (user_id, platform, token)`
  - Indexes: `user_fcm_tokens_user_idx ON (user_id)`, `user_fcm_tokens_last_seen_idx ON (last_seen_at)`
- New endpoint `POST /api/v1/user/fcm-token` — JWT-required, accepts `{ token, platform, app_version }`, upserts on the `(user_id, platform, token)` UNIQUE constraint, refreshes `last_seen_at = NOW()` on every call (the authoritative freshness signal per [`docs/05-Implementation.md:1394`](docs/05-Implementation.md)). Returns `204 No Content` on success.
- Validation: `platform` ∈ {`android`, `ios`} (anything else → `400` with a fixed-vocabulary error string `"invalid_platform"`); `token` non-empty after trim (empty → `400` with `"empty_token"`); `app_version` optional, max 64 chars (longer → `400` with `"app_version_too_long"`). Body length cap follows the project content-length-guard convention (request body ≤ 4 KB — FCM tokens are typically <300 chars but registration tokens for v1 API can be ~163 chars; 4 KB leaves generous headroom without enabling abuse).
- New capability `fcm-token-registration` (per the precedent of one capability per endpoint family — `health-check`, `premium-search`, `client-ip-extraction`).
- Tests cover: happy-path insert; idempotent re-registration of the same `(user_id, platform, token)` triple refreshes `last_seen_at` without creating a duplicate row; same user can register multiple tokens (e.g., one Android + one iOS for cross-device); the same token registered by a different `user_id` produces a separate row (the UNIQUE is per-`(user_id, platform, token)`, not per-`token` — supports family-shared devices, see decision in `design.md`); platform CHECK rejects `web` / empty / mixed-case `Android`; missing JWT → `401`; malformed JSON body → `400`; ON DELETE CASCADE verified by deleting a user and asserting tokens gone; rate-limit shared with the existing per-user write rate-limit if one is in place (no new rate-limit category — see `design.md` D3).

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

- **Code**:
  - `backend/ktor/src/main/resources/db/migration/V14__user_fcm_tokens.sql` — new Flyway migration with the table + two indexes.
  - `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRoutes.kt` — new Ktor route handler in the user module (per [`docs/04-Architecture.md:129`](docs/04-Architecture.md), the User module owns FCM token registration).
  - `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRepository.kt` — repository wrapping the upsert SQL (`INSERT ... ON CONFLICT (user_id, platform, token) DO UPDATE SET last_seen_at = NOW(), app_version = EXCLUDED.app_version`).
  - `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRequest.kt` — `@Serializable` request DTO with the three fields.
  - `backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt` — Koin wiring for `FcmTokenRepository`; route registration via `fcmTokenRoutes(...)` inside the JWT-protected route block (matches the existing user-route pattern).
  - `backend/ktor/src/test/kotlin/id/nearyou/app/user/FcmTokenRoutesTest.kt` — full test coverage matrix described above.
- **CI/CD**: none. The migration runs through the existing `nearyou-migrate-staging` Cloud Run Job pre-deploy.
- **Configuration**: none new.
- **Dependencies**: none new. Uses the existing JDBC + HikariCP + Ktor + JWT auth stack.
- **Observability**: a single `INFO`-level log line per successful registration (`fcm_token_registered user_id=<...> platform=<...> created=<true|false>` where `created` distinguishes initial-insert from `last_seen_at`-refresh — useful for Phase 2 push-send debugging). No new metrics; the existing JWT-fail and request-error counters cover failure modes.
- **Risk**: low. Endpoint is JWT-gated (no unauthenticated surface), single-table write with idempotent upsert semantics (no race-condition surface — `INSERT ... ON CONFLICT DO UPDATE` is atomic), and the schema has a clean `ON DELETE CASCADE` from `users` (no GC complexity). Largest blast-radius vector is a misconfigured `platform` validation accepting an unknown value into the DB CHECK constraint, which would fail at `INSERT` time with a Postgres CHECK violation — caught by the test matrix and surfaced as a `500` not a corruption (the request handler should pre-validate to return `400`, but the DB is the second line of defense).
