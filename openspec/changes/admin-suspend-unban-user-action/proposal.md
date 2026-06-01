## Why

The admin panel can authenticate ([`admin-login`](../../specs/admin-login/spec.md)) and read its audit trail ([`admin-actions-log-viewer`](../../specs/admin-actions-log-viewer/spec.md)), but it has **zero write actions** — it cannot take any moderation action on a user account. An 18+ social app cannot launch without the ability to suspend abusive accounts and lift suspensions, so this is the launch-blocking gap on the admin surface. It is the panel's **first admin write action** ("Moderation workflow MVP" — Admin #5 in [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority).

## What Changes

- Add an admin-authenticated **user-moderation surface** under `/admin/` — the panel's first state-changing action — wired inside the existing `authenticate(ADMIN_AUTH_NAME)` block in `AdminModule.kt`.
- **Suspend (7-day)**: an admin sets a target user to `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '7 days'`. This is the same state the daily [`suspension-unban-worker`](../../specs/suspension-unban-worker/spec.md) later auto-lifts on elapse.
- **Manual unban**: an admin sets a target user to `is_banned = FALSE`, `suspended_until = NULL` — the same `(is_banned, suspended_until) → (FALSE, NULL)` transition the worker performs automatically, triggered early by a human (and also able to lift a permanent ban).
- **Minimal target-user lookup** by user UUID (exact-username lookup is a nice-to-have) so the admin can identify whom to act on. The full "search by username/ID hash, profile + history" User Management page ([`docs/07-Operations.md`](../../../docs/07-Operations.md) § Core Features) is a separate, larger change.
- **Audit**: every successful action writes exactly one immutable `admin_actions_log` row attributed to the **acting human admin** (`admin_id` = the admin's own UUID, NOT the `system` sentinel), `action_type ∈ {user_suspended, user_unbanned}`, `target_type = 'user'`, `target_id`, an admin-entered `reason`, `before_state` / `after_state` = `{is_banned, suspended_until}`, `ip` (from `call.clientIp`), `user_agent`. The user `UPDATE` + audit `INSERT` commit **atomically** (audit-fail ⇒ the user UPDATE rolls back).
- **Role-gated**: `owner` / `admin` / `moderator` may act; `read_only` is rejected. This is the **first role-gated admin write**, establishing the pattern (contrast the all-roles-readable actions-log viewer).
- **CSRF-gated**: each state-changing handler calls `AdminCsrfGate.validateCsrf(...)` first, per the established admin CSRF contract.
- **In-app notification on suspend**: insert one `notifications` row of the existing `account_action_applied` type for the suspended user (the documented user-facing signal per the `docs/05-Implementation.md` notification catalog). No notification on manual unban (mirrors `suspension-unban-worker` design D5 — no `account_action_lifted` type exists, and the positive-restoration copy mismatch). *(Flagged for review — see [design.md](design.md) D2.)*
- Extend `AdminAuditLogger` with `logUserSuspended(...)` / `logUserUnbanned(...)`, and add a `UserModerationRepository` for the lookup + atomic UPDATE-and-audit transaction.
- **No Flyway migration** — `admin_actions_log.action_type` is a free `VARCHAR(64)` (no CHECK enum), and `users.is_banned` / `users.suspended_until` + the `users_suspended_idx` partial index already exist.

Explicitly **out of scope** (deferred, NOT dropped): permanent-ban action, shadow-ban (`is_shadow_banned`), warning action, Report-Queue integration (suspend-from-queue), the full User Management search/profile/history page, FCM push on `account_action_applied`, per-admin destructive-action rate-limiting ([`docs/07-Operations.md`](../../../docs/07-Operations.md) § Security: "20/hour per admin"), and WebAuthn (multi-admin period). Each is a future change against this or a related capability — see [design.md](design.md) § Open Questions / decisions.

## Capabilities

### New Capabilities
- `admin-user-moderation`: admin-initiated moderation actions on a user account's ban / suspension state, served under `/admin/` behind the admin session + CSRF + role gate. **This change populates it with two actions** — **suspend (7-day)** and **manual unban** — plus their immutable audit contract and the suspend-side `account_action_applied` notification. The capability is the durable home for admin account-state moderation; future actions (permanent ban, shadow ban, warning) will extend it with ADDED requirements rather than spawning new capabilities.

### Modified Capabilities
<!-- None. This change CONSUMES admin-login (auth/CSRF/principal gate) and writes rows DISPLAYED by admin-actions-log-viewer, and it is a sibling human-triggered path to suspension-unban-worker / system-actor — but it changes no existing capability's requirements. Cross-referenced in design.md, not modified. -->

(none)

## Impact

- **New code** (`:backend:ktor` `admin` package): `UserModerationRepository` (target lookup + atomic suspend/unban `UPDATE` + audit `INSERT` in one transaction); a new authenticated route extension (e.g., `adminUserModeration(...)`) wired in [`AdminModule.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminModule.kt) inside `authenticate(ADMIN_AUTH_NAME)`; Pebble templates (lookup form + current-state display + suspend/unban controls) under `templates/admin`; extension of [`AdminAuditLogger`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/AdminAuditLogger.kt) with `logUserSuspended` / `logUserUnbanned`.
- **DB writes** (no DDL, no migration): `users` (`is_banned`, `suspended_until`); `admin_actions_log` (new free-string `action_type` values `user_suspended` / `user_unbanned`); optional `notifications` (`account_action_applied`). Reads `users` directly — permitted in the admin module, which is exempt from the `visible_*`-view and block-exclusion lint rules (per `CLAUDE.md` § Critical invariants).
- **New routes** (server-rendered HTMX under the `/admin/` subtree; NOT public `/api/v1`): a lookup `GET` + a `POST` suspend + a `POST` unban (exact paths fixed in the spec, e.g. `GET /admin/users`, `POST /admin/users/{id}/suspend`, `POST /admin/users/{id}/unban`).
- **Security invariants touched**: client IP via `call.clientIp` (no raw `X-Forwarded-For`); CSRF on every state-change; admin-FK `ON DELETE SET NULL` already satisfied by the shipped `admin_actions_log` schema.
- **Dependencies**: none new (no `gradle/libs.versions.toml` change).
- **Downstream consumers**: new rows surface in `admin-actions-log-viewer`; a suspended-then-elapsed user is later auto-unbanned by `suspension-unban-worker` (the manual unban is its early-trigger human sibling).
