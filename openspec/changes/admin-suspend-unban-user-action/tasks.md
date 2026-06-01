## 1. Pre-implementation checks

- [ ] 1.1 Confirm NO Flyway migration is needed: `admin_actions_log.action_type` is a free `VARCHAR(64)` (no CHECK enum), `users.is_banned` / `users.suspended_until` + `users_suspended_idx` already exist, and the `account_action_applied` notification type already exists. If any implementation task tempts a `V<N>__*.sql` migration, STOP — that signals a scope misread.
- [ ] 1.2 Pre-implementation library re-check: **N/A** — this change introduces no new pin in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) and activates no previously-unused library (per `openspec/project.md` § Change Delivery Workflow, the re-check is skipped when the change touches only existing + actively-used libraries). Note this one-liner in the first feat commit body.
- [ ] 1.3 Re-read the canonical sources for drift since proposal: `docs/07-Operations.md` § Core Features (User Management) + § Security; `suspension-unban-worker` spec (the worker's `(is_banned, suspended_until) → (FALSE, NULL)` transition + atomicity contract); `admin-login` spec (CSRF middleware + `AdminPrincipal` + `authenticate(ADMIN_AUTH_NAME)`); `system-actor` spec (the sentinel UUID this change must NOT use).

## 2. Repository — `UserModerationRepository`

- [ ] 2.1 Create `UserModerationRepository(dataSource: DataSource)` in the `:backend:ktor` `admin` package (parameterized queries only; the admin module is exempt from the `visible_*`-view + block-exclusion lint, so direct `FROM users` is allowed here).
- [ ] 2.2 `lookup(q: String)`: resolve `q` as a user UUID first (by primary key), else attempt an EXACT `users.username` match; return a small DTO (`id`, `username`, `is_banned`, `suspended_until`, `deleted_at`) or `null`. Parameterized. (Spec: "User lookup resolves by UUID then exact username".)
- [ ] 2.3 `suspend(targetId, actingAdminId, reason, ip, userAgent)`: ONE transaction (`autoCommit = false`) — `SELECT ... FOR UPDATE` the current `is_banned` / `suspended_until` / `deleted_at`; apply the D7 eligibility guard (reject soft-deleted; reject `is_banned = TRUE AND suspended_until IS NULL` permanent ban — no downgrade); on eligible, `UPDATE users SET is_banned = TRUE, suspended_until = NOW() + INTERVAL '7 days'`; INSERT the `user_suspended` audit row + the `account_action_applied` notification row in the SAME transaction; commit. Return a typed outcome (`Applied` / `RejectedSoftDeleted` / `RejectedPermanentBan`). (Specs: suspend apply, suspend reject, audit, notification, atomicity.)
- [ ] 2.4 `unban(targetId, actingAdminId, reason, ip, userAgent)`: ONE transaction — `SELECT ... FOR UPDATE`; if `is_banned = FALSE` → return `NoOpNotBanned` with NO writes (no audit row); else `UPDATE users SET is_banned = FALSE, suspended_until = NULL` + INSERT the `user_unbanned` audit row in the same transaction; commit. Return a typed outcome (`Applied` / `NoOpNotBanned`). (Specs: unban, audit, atomicity.)
- [ ] 2.5 Build `before_state` / `after_state` JSON in Kotlin (kotlinx.serialization) as `{"is_banned": <bool>, "suspended_until": <ISO-8601 Instant | null>}` per design D6; capture the PRIOR `suspended_until` in `before_state` (e.g. the re-suspend reset case).

## 3. Audit-row write (joins the repository transaction)

- [ ] 3.1 Extend `AdminAuditLogger` with `logUserSuspended(...)` / `logUserUnbanned(...)` that accept a caller-supplied `Connection` (so the audit INSERT joins the repo's transaction for atomicity, per design D4) — `action_type` ∈ `{user_suspended, user_unbanned}`, `target_type = 'user'`, `target_id` = target UUID text, `reason` (NULL-tolerant), `before_state` / `after_state`, `ip` (sanitized via `InetSanitizer`), `user_agent`. Keep the existing own-connection `insert(...)` for the standalone login/logout/CSRF events. (Spec: audit, atomicity.)
- [ ] 3.2 Ensure the audit `admin_id` is the acting `AdminPrincipal` UUID and assert in code review it is NEVER the `system` sentinel `54b53072-540e-3eb8-b8e9-343e71f28176`. (Spec: "Audit row attributes to the human admin, never the system sentinel".)

## 4. Notification write (joins the suspend transaction)

- [ ] 4.1 Within `suspend(...)`'s transaction, INSERT one `notifications` row of `type = 'account_action_applied'` for the target user with `body_data = {action_type, reason, suspended_until}` per the `docs/05-Implementation.md` notification catalog + the `in-app-notifications` capability schema. `unban(...)` inserts NO notification. FCM push is out of scope (deferred). (Spec: "Suspend inserts an account_action_applied notification; unban inserts none".)

## 5. Routes — `adminUserModeration`

- [ ] 5.1 Add an `adminUserModeration(repo, auditLogger, csrfHmacKeyProvider)` route extension (new file under `admin/routes/`).
- [ ] 5.2 `GET /admin/users`: render the lookup form; when `q` resolves, render the target's current `is_banned` / `suspended_until` state + suspend/unban controls; branch on the `HX-Request` header (fragment vs full page) mirroring `adminActionsLog`. Non-resolving `q` → inline empty state (200, not 404). (Specs: GET surface, lookup.)
- [ ] 5.3 `POST /admin/users/{id}/suspend`: call `AdminCsrfGate.validateCsrf(call, auditLogger)` FIRST → role gate (5.5) → parse `{id}` as UUID (invalid → safe inline error, not 500) → `repo.suspend(...)` reading `call.principal<AdminPrincipal>()` + `call.clientIp` + `User-Agent` header → render outcome (303 / `HX-Redirect` back to `GET /admin/users?q={id}` on success; informational message on reject). (Specs: suspend, role gate, CSRF.)
- [ ] 5.4 `POST /admin/users/{id}/unban`: same gate order → `repo.unban(...)` → outcome render (success redirect; "user is not banned" message on no-op). (Specs: unban, role gate, CSRF.)
- [ ] 5.5 Add a reusable role-gate helper (allowed = `owner` / `admin` / `moderator`; `read_only` → HTTP 403) checked AFTER `validateCsrf`. This is the first role-gated admin write — make it reusable for future admin writes. (Spec: "State-changing actions are gated to owner / admin / moderator".)

## 6. Templates (Pebble, `templates/admin`)

- [ ] 6.1 Lookup page template extending the shared base layout: `q` form + result region. The suspend/unban forms rely on the authenticated layout's CSRF meta tag + `htmx:configRequest` hook (per `admin-login`) so HTMX POSTs auto-carry `X-CSRF-Token`; include a `_csrf` hidden field as the no-JS fallback. Default-autoescape on (no Pebble `raw`).
- [ ] 6.2 Result fragment template for the HTMX partial swap (the `#...` swappable element returned when `HX-Request: true`).
- [ ] 6.3 Empty-state ("no matching user") + informational outcome messages (suspend-rejected-soft-deleted, suspend-rejected-permanent-ban, unban-no-op-not-banned).

## 7. Wiring

- [ ] 7.1 In `AdminModule.admin(...)`, instantiate `UserModerationRepository(dataSource)` and register `adminUserModeration(...)` INSIDE the `authenticate(ADMIN_AUTH_NAME)` block alongside `adminIndex(...)` and `adminActionsLog(...)`.

## 8. Tests — cover EVERY spec scenario (do not drop any per CLAUDE.md)

- [ ] 8.1 Suspend apply: active user → `is_banned = TRUE`, `suspended_until` ≈ NOW()+7d; duration is server-fixed (client `duration_days` ignored); re-suspend resets the clock + `before_state` records prior expiry.
- [ ] 8.2 Suspend reject: soft-deleted target → no state change / no audit row / no notification; permanently-banned target → unchanged (no downgrade) / no audit row.
- [ ] 8.3 Unban: time-bound-suspended → cleared; permanently-banned → cleared (admin override); not-currently-banned → no-op with NO audit row.
- [ ] 8.4 Atomicity: injected `admin_actions_log` INSERT failure on suspend → user UPDATE rolled back (still `is_banned = FALSE`), no audit row, no notification; success path commits UPDATE + audit + notification together.
- [ ] 8.5 Audit-row shape: `user_suspended` / `user_unbanned`, `target_type = 'user'`, `target_id`, `before_state` / `after_state` = `{is_banned, suspended_until}`, `admin_id` = the acting human admin (asserted NOT equal to the `system` sentinel UUID), `ip` from `CF-Connecting-IP` (clientIp), `user_agent` NULL-tolerant.
- [ ] 8.6 Notification: suspend inserts exactly one `account_action_applied` row for the target; unban inserts zero notification rows.
- [ ] 8.7 GET surface (route/integration): no-`q` → lookup form; resolving `q` → state + controls; unauthenticated → 302 `/admin/login`; `HX-Request` → fragment-only (no `<html>` wrapper).
- [ ] 8.8 Lookup: by UUID; by exact username; non-resolving → empty state (200, not 404); SQL-metacharacter `q` → treated as a literal, `users` table still exists afterward.
- [ ] 8.9 Role gate: `read_only` POST suspend → 403 + target unchanged + no `user_suspended` row; `read_only` POST unban → 403 + still banned + no `user_unbanned` row; `moderator` POST suspend → authorized (target suspended).
- [ ] 8.10 CSRF: suspend with no token → 403 + no state change + no audit row; wrong token → 403; valid token → proceeds.

## 9. Lint + local verification

- [ ] 9.1 Run the full pre-push gate locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (BOTH lint frameworks per CLAUDE.md — CI runs `ktlintCheck` AND `detekt`).
- [ ] 9.2 Confirm no Detekt invariant violations: direct `FROM users` is admin-module-exempt (verify the rule's allowlist covers this path); IP read via `call.clientIp` (no raw `X-Forwarded-For`); CSRF enforced on every state-changing handler.

## 10. Staging smoke (pre-archive, per `openspec/project.md` § Staging deploy timing — stays unchecked until exercised)

- [ ] 10.1 Add `dev/scripts/smoke-admin-suspend-unban-user-action.sh`: log in as the seeded admin (TOTP via `oathtool` per the local-run recipe) → look up a synthetic user → suspend → assert `users.suspended_until` set + one `user_suspended` `admin_actions_log` row + one `account_action_applied` notification → unban → assert `is_banned = FALSE`, `suspended_until = NULL` + one `user_unbanned` row.
- [ ] 10.2 `gh workflow run deploy-staging.yml --ref admin-suspend-unban-user-action` → poll the deploy run → run the smoke script against the branch deploy → tick this section before `/opsx:archive`.

## 11. Docs + follow-ups

- [ ] 11.1 `FOLLOW_UPS.md`: add an `admin-destructive-action-rate-limit` entry — the "20/hour per admin" destructive-action limiter (`docs/07-Operations.md` § Security) is deferred per design D11; capture the Redis-counter-vs-DB-count substrate question for the follow-up's design.
- [ ] 11.2 `docs/07-Operations.md` § Status note (top of file): move "Suspend/Unban Action in Admin #5" from the "remains DESIGN" list to shipped (this change lands it). Update the per-feature User Management framing accordingly.
- [ ] 11.3 No new Gradle module → README module-list sync is N/A (no `dev/module-descriptions.txt` change; `dev/scripts/sync-readme.sh` not needed).
