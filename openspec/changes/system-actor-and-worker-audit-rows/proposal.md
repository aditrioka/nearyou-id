## Why

The `suspension-unban-worker` flips `is_banned = FALSE` for users whose time-bound suspension has elapsed, but records each unban only in an ephemeral structured INFO log (~30-day Cloud Logging retention). [`docs/05-Implementation.md:235`](../../../docs/05-Implementation.md) prescribes "Audit log inserted per unban," and the `admin_actions_log` table now exists (shipped in V16 + V17). Worker-initiated moderation actions therefore have no permanent, queryable audit trail — and because `admin_actions_log.admin_id` is `NOT NULL`, there is no actor to attribute a machine action to. [`V16__admin_users.sql:24-26`](../../../backend/ktor/src/main/resources/db/migration/V16__admin_users.sql) explicitly defers the prerequisite — a `system` sentinel admin user — to this change by name.

## What Changes

- **NEW `system` sentinel admin user**, seeded via a V18 Flyway migration: deterministic UUID `54b53072-540e-3eb8-b8e9-343e71f28176` (= `UUID.nameUUIDFromBytes("system")`), `is_active = FALSE`, `role = 'read_only'`, non-login `password_hash` literal, no TOTP. It is the canonical attribution target for machine-initiated `admin_actions_log` rows, and is structurally un-loginable.
- **`suspension-unban-worker` writes one immutable `admin_actions_log` row per unbanned user**, in the SAME transaction as the existing `UPDATE ... RETURNING id` (atomicity: a failed audit INSERT rolls back the unban). Row shape: `admin_id` = sentinel UUID, `action_type = 'system_unban_applied'`, `target_type = 'user'`, `target_id = <user_id>`, `reason = 'suspension_elapsed'`, `before_state = {"is_banned": true, "suspended_until": "<ISO-8601>"}`, `after_state = {"is_banned": false, "suspended_until": null}`. The existing structured INFO log is retained (audit row + INFO log coexist). Idempotency holds: a retry flips zero rows, so it writes zero audit rows.
- **Regression coverage**: (a) a forced `admin_actions_log` INSERT failure rolls back the user UPDATE; (b) the seeded sentinel cannot authenticate via `POST /admin/login`.

**Scope reconciliation (verified against shipped code — recorded so review does not re-expand it):**

- This covers **only `suspension-unban-worker`** — it is the sole shipped moderation worker (`find *Worker*.kt` → `SuspensionUnbanWorker.kt` only). The privacy-flip / hard-delete / FCM-cleanup / notifications-purge peer workers the originating follow-up anticipated have **not shipped**. `POST /internal/apple/s2s-notifications` is a user-initiated Apple webhook, not a moderation worker — excluded.
- **No `password_hash IS NULL` auth-bypass guard** is added (the originating follow-up's plan). It is moot: `admin_users.password_hash` is `TEXT NOT NULL`, and `AdminUserRepository.findActiveByEmail` already filters `WHERE email = ? AND is_active = TRUE`, so an `is_active = FALSE` sentinel is never loaded for verification. No CHECK constraint or admin-login code change.

## Capabilities

### New Capabilities
- `system-actor`: the deterministic `system` sentinel admin user — its fixed identity (UUID derived from `UUID.nameUUIDFromBytes("system")`), security posture (`is_active = FALSE`, non-login `password_hash`, `read_only` role), and role as the canonical `admin_actions_log.admin_id` attribution target for machine-initiated admin actions.

### Modified Capabilities
- `suspension-unban-worker`: adds a requirement that the worker writes one immutable `admin_actions_log` row per unban, in the same transaction as the user `UPDATE`; revises the structured-INFO-log requirement's closing note that previously deferred audit rows.

## Impact

- **Schema**: new `V18` Flyway migration — data-seed only (one `INSERT INTO admin_users`; no DDL). Flyway-portable; runs clean in CI integration Postgres like V16.
- **Code**: `backend/ktor/.../admin/SuspensionUnbanWorker.kt` (+ `UnbanWorkerRoute.kt` wiring) — add the in-transaction `admin_actions_log` INSERT and a `SYSTEM_ACTOR_ID` Kotlin constant. No admin-login code change.
- **Specs**: new `specs/system-actor/spec.md`; delta `specs/suspension-unban-worker/spec.md`.
- **Tests**: `SuspensionUnbanWorker` audit-row + atomicity integration tests (real Postgres); a sentinel-cannot-authenticate regression test.
- **Security / audit**: closes the worker-action audit gap; the sentinel is structurally un-loginable (verified against `AdminUserRepository.findActiveByEmail`).
- **Out of scope**: peer workers (none shipped); any admin-login change; the `admin_actions_log` role-level immutability REVOKE (owned by the in-progress `admin-app-revoke-staging-and-prod` follow-up — the worker writes via the main app DB role, which holds INSERT; the REVOKE targets `admin_app` only).
