## Why

The admin panel can *look up* a user and suspend / unban them (`admin-user-moderation`, board frame 5: `GET /admin/users?q=` → `POST /admin/users/{id}/suspend|unban`), but it has **no per-user profile page** — a moderator triaging a report deep-links to `/admin/users?q=<id>` and sees only the bare moderation-state form, with no identity context and no history of what has already been done to (or by) that account. `docs/07-Operations.md` § Core Features ("User Management") specifies the full surface — *"search by username/ID hash, profile + history, actions (warning, suspend 7 days, ban, shadow ban, unban)"* — and the shipped `admin-user-moderation` spec Purpose explicitly reserves it: *"the user search / profile / history browse page is a DISTINCT future capability (`admin-user-management`)"* and *"Future account-state actions (permanent-ban creation, shadow ban, warning) extend this SAME capability with ADDED requirements."*

This change ships that keystone: the per-user **profile + action-history page** (board frame 6, `/admin/users/{id}`), the new **warning** action, and — because frame 6's destructive-quota chip and the just-shipped report-queue resolution change both call for it — the cross-cutting **destructive-action rate limit** (±20/hr per admin) the `admin-user-moderation` Purpose flags as *"the destructive-action-rate-limit target, deferred per `docs/08` § Pre-Launch"* (Pre-Launch #9). The report-queue-resolution change ([archive `admin-report-queue-resolution-actions`](../archive/2026-06-08-admin-report-queue-resolution-actions/proposal.md)) shipped permanent-ban + shadow-ban enforcement and explicitly noted *"full enforcement raises the urgency of the `admin-destructive-action-rate-limit` follow-up — recommend sequencing it next."* This is that next step, and it completes the in-panel moderation hub other admin surfaces hang off.

**Priority note.** The declared `openspec/project.md` § Mobile-First to Full-Demo priority biases picks toward mobile. This admin pick is an **explicit operator override** (2026-06-13): the entire mobile critical-path live menu (#1–#5 + FCM token registration) is already claimed by concurrent sessions (PRs #245–#250), so the operator chose to advance the admin surface in parallel rather than queue behind a claimed mobile pick.

## What Changes

- **NEW capability `admin-user-management`** — `GET /admin/users/{id}`, the per-user profile + history page, authenticated inside `authenticate(ADMIN_AUTH_NAME)`, viewable by any admin role, extending the shared admin base layout (`admin-panel-scaffold`), HTMX-aware with a plain-`GET` no-JS fallback (mirrors the audit-log viewer's render pattern):
  - **Profile block** — user UUID, `username`, `display_name`, `is_premium`, `created_at` (account age), `private_profile_opt_in`, and current moderation state (`is_banned`, `suspended_until`, `is_shadow_banned`). Admin-module reads of `users` are permitted (standing `visible_*`/block-exclusion lint exemption); all queries are parameterized JDBC.
  - **Action-history view** — the target user's `admin_actions_log` rows (`target_type = 'user' AND target_id = {id}`, newest-first, same columns the audit-log viewer renders: time / admin / action / reason / `before_state`→`after_state` disclosure) merged with `username_history` (`user_id = {id}`: `old_username`→`new_username`, `changed_at`). Read-only.
  - **Action controls** — surfaces the EXISTING suspend / unban controls (the actions live in `admin-user-moderation`) plus the NEW warning control, and the **live destructive-quota chip** ("N/20 this hour").
  - **Robust input handling** — a malformed / non-UUID `{id}` path segment → safe 4xx / inline, never a 500, no mutation; a `{id}` resolving to no user → inline empty-state, 200, not 404 (mirrors the `admin-user-moderation` path-parse + non-resolving-query requirements).
  - The frame-5 lookup result (`users-result.peb`) deep-links its hit to `/admin/users/{id}`.

- **NEW capability `admin-destructive-action-rate-limit`** — a shared per-admin cap: an admin who has performed ≥ 20 destructive actions in the trailing hour is rejected on the 21st, with **no state mutation and no `admin_actions_log` row** for the rejected attempt. The **destructive set** is the user-punitive actions: warning, suspend, permanent ban, shadow ban (NOT unban, content keep/hide, dismiss, or login). **Substrate: COUNT over `admin_actions_log`** (the immutable audit trail IS the rate-limit ledger) — no new table, no Redis coupling (design D2). The same COUNT backs the page's informational quota chip.

- **MODIFIED `admin-user-moderation`** (ADDED requirements) —
  - **Warning action** `POST /admin/users/{id}/warn`: authenticated, role-gated (`owner`/`admin`/`moderator`), CSRF-gated. In ONE transaction writes exactly one immutable `admin_actions_log` row (`action_type = 'user_warned'`, attributed to the human admin, NEVER the `system` sentinel) AND one sanitized notification reusing the existing `account_action_applied` `notifications.type` with `body_data.action_type = 'warning'` (mirrors the suspend-notification insert). The admin's free-text reason is **audit-only — never echoed** to the warned user. The warning does NOT mutate any `users` moderation column (no ban/suspend).
  - **Rate-limit enforcement** on this capability's destructive handlers (suspend, permanent-ban unban, warn): each enforces `admin-destructive-action-rate-limit` before mutating.

- **MODIFIED `admin-report-queue`** (ADDED requirement) — the destructive moderation-queue resolutions (`suspend_author_7d`, `ban_author`, `shadow_ban_author`) enforce `admin-destructive-action-rate-limit`; non-destructive resolutions (`keep`, `hide`, report `decision` bookkeeping) are NOT capped.

- **No new Flyway migration** (verified): `admin_actions_log.action_type` is `VARCHAR(64)` with no CHECK (V16) → `user_warned` needs no migration; the warning notification reuses `account_action_applied` (already in the `notifications.type` CHECK, V10); the rate limiter COUNTs over the existing `admin_actions_log` (`admin_actions_type_idx` on `(action_type, created_at DESC)`, V16/V17); `username_history` exists (V3); the `users` moderation columns exist (V2).

## Capabilities

### New Capabilities
- `admin-user-management` — the per-user profile + action-history browse page (`GET /admin/users/{id}`).
- `admin-destructive-action-rate-limit` — the shared per-admin ±20/hr destructive-action cap.

### Modified Capabilities
- `admin-user-moderation` — ADDS the `warning` action and rate-limit enforcement on its destructive handlers.
- `admin-report-queue` — ADDS rate-limit enforcement on its destructive moderation-queue resolutions.

## Out of Scope (explicit deferrals — each → a `follow-up` issue, label `follow-up` + `admin`)

- **Standalone permanent-BAN creation action** on the user page — permanent ban is already reachable via report-queue resolution (`ban_author`); adding it as a standalone `/admin/users/{id}` action is additive. Fast-follow.
- **Standalone shadow-BAN action** on the user page — same: shipped via report-queue resolution; the frame-6 caption itself tags shadow-ban as *"surface standalone; aksinya sendiri sudah shipped via resolusi report queue."* Fast-follow.
- **Premium Username Change Oversight actions** (username override / 30-day handle release) — the DISTINCT Premium-username admin capability (`docs/07` § Premium Username Change Oversight, `docs/08` Phase 4 #24). This change only **reads** `username_history` for the history view; no oversight writes.

## Impact

- **Code:** `backend/ktor/.../admin/routes/AdminUserModerationRoute.kt` (add the `{id}` profile GET + the `/warn` POST), a new `admin/usermanagement/` profile read repository (identity + state + merged history), `admin/moderation/UserModerationRepository.kt` (warn transaction), a new shared `admin/ratelimit/DestructiveActionRateLimiter.kt` (the COUNT guard), `admin/reportqueue/ReportResolutionRepository.kt` (apply the guard to destructive resolutions), `admin/auth/AdminAuditLogger.kt` (`user_warned` writer), `AdminModule.kt` (wiring).
- **Templates:** new `user-profile.peb` + `user-history-table.peb` (frame-6 layout; history table styled per frame 7); `users-result.peb` (deep-link to `/admin/users/{id}`); the quota chip.
- **Schema / migrations:** none.
- **Dependencies:** none (no `gradle/libs.versions.toml` change).
- **Invariants touched:** admin-session CSRF mandatory on `/warn`; `AdminRoleGate` write-role on `/warn`; human-admin attribution (never the `system` sentinel) on the audit row; the audit-only-reason discipline (free-text reason never echoed to the user); admin-module exemption from the `visible_*`/block-exclusion lint (raw `users`/`admin_actions_log`/`username_history` reads); Pebble autoescape on every rendered value.
- **Follow-ups created:** standalone permanent-ban action, standalone shadow-ban action, Premium Username Change Oversight (each filed at archive time per the deferrals above).
