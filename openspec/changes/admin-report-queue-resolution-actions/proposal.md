## Why

The read-only Report Queue viewer (`GET /admin/reports`, capability `admin-report-queue`, [PR #154](https://github.com/aditrioka/nearyou-id/pull/154)) just shipped — a moderator can *see* the `reports` / `moderation_queue` backlog but cannot resolve anything in-panel: every row stays `status = pending` forever, and the only way to act is to follow the per-row deep-link to `/admin/users`. `docs/07-Operations.md` § Core Features "Report Queue" describes the deferred in-row actions as **"Hide, Dismiss, Suspend, Ban, Shadow ban — today reachable only via the deep-link."** This change ships those in-row actions **so they perform the moderation enforcement directly**, closing the moderation loop entirely in-panel. The read→write fast-follow mirrors `admin-actions-log-viewer` (read) → `admin-user-moderation` (write). For an 18+ social app, in-panel report resolution is moderation-critical-path.

## What Changes

- **New CSRF + role-gated POST resolution endpoints** under the existing admin gate, mirroring `AdminUserModerationRoute` (CSRF validation first → role gate → atomic repository transaction → one immutable `admin_actions_log` row per action).
- **Report status transition** — `POST /admin/reports/{id}/resolve` sets `reports.status` `pending → actioned | dismissed` + `reviewed_by` + `reviewed_at` (bookkeeping; performs no enforcement).
- **Moderation-queue resolution that PERFORMS enforcement** — `POST /admin/moderation-queue/{id}/resolve` sets `moderation_queue.status = resolved` + `resolution` + `resolved_by` + `resolved_at` and applies the enforcement the resolution names, atomically:
  - `keep` → un-hide the content (`is_auto_hidden = FALSE`); `hide` → hide it (`is_auto_hidden = TRUE`) — for `post`/`reply` targets (the inverse of the V9-era application-level auto-hide writer in `ReportService`).
  - `suspend_author_7d` → **reuse** the shipped 7-day suspend on the resolved author (`is_banned`, `suspended_until`, sanitized `account_action_applied` notification, soft-deleted/already-permabanned guards).
  - `ban_author` → permanently ban the resolved author (`is_banned = TRUE`, `suspended_until = NULL`) + `account_action_applied` notification — **owner/admin only** (mirrors the permanent-ban-unban tier).
  - `shadow_ban_author` → set `is_shadow_banned = TRUE` on the resolved author, writing **no user-facing notification** (stealth invariant) but an `admin_actions_log` row.
  - The offending author is resolved from the target exactly as the read viewer's deep-link does (`post`→author, `reply`→author, `user`→self, `chat_message`→sender).
- **In-row resolution controls** in `reports-table.peb` / `reports.peb` — per-row form (CSRF hidden field + selector), HTMX partial-swap + no-JS fallback, HTML-escaped, with affordances making the **immediate, destructive** nature of Suspend/Ban/Shadow-ban clear.
- **Atomic + idempotent** — enforcement + status + audit (+ notification) commit-or-rollback together; re-resolving a non-`pending` row is a safe no-op (conditional `UPDATE … WHERE status = 'pending'`), which also serializes the concurrent-admin race.
- **No new Flyway migration** — `reports.status` (V9), `moderation_queue.status`/`resolution` (V9), `users.is_banned`/`suspended_until`/`is_shadow_banned` (V2), `posts`/`post_replies.is_auto_hidden` (V4/V8), the `admin_users` FKs (V16), and the free-form `admin_actions_log.action_type VARCHAR(64)` (V16) all exist.
- **Out of scope (rejected by the endpoint):** `resolution = delete` (not in the `docs/07-Operations.md:36` in-row set — `hide` is the canonical in-row content removal; a content-soft-delete surface is a separate change); `accept_flagged_username` / `reject_flagged_username` (+ the `username_flagged` trigger) — owned by the future Premium Username Change Oversight feature; the "post has edit history" filter (`admin-report-queue-has-edit-history-filter`); the per-admin destructive-action rate limit (`admin-destructive-action-rate-limit` — see design Risks: full enforcement raises its urgency).

## Capabilities

### New Capabilities
<!-- None — this extends the existing admin-report-queue surface/route subtree (design D1). Shadow-ban is set here for the first time but as one bounded write under this capability, not a new capability. -->

### Modified Capabilities
- `admin-report-queue`: the deferred-write-back requirement is RENAMED + MODIFIED (only the edit-history filter remains deferred); "strictly read-only" is RENAMED + MODIFIED (the GET listing stays read-only; mutations are confined to the new `/{id}/resolve` POST sub-routes); plus new ADDED requirements for the report-status transition, the enforcement-performing queue resolution, content/author enforcement, the `ban_author` owner/admin tier, the `shadow_ban_author` no-notification stealth invariant, session/CSRF/role gating, atomicity, malformed/out-of-scope/idempotent handling, the no-cascade-to-sibling-reports guard, and the in-row controls.

## Impact

- **Code:** `backend/ktor/.../admin/routes/` (new resolution route alongside `adminReportQueue`), `backend/ktor/.../admin/reportqueue/` (resolution repository — reuses `UserModerationRepository.suspend`; adds permanent-ban + shadow-ban + the content `is_auto_hidden` toggle), `backend/ktor/.../admin/auth/AdminAuditLogger.kt` (new `report_resolved` / `moderation_queue_resolved` writers), `AdminModule.kt` (wiring).
- **Templates:** `reports-table.peb` + `reports.peb` (in-row controls).
- **Schema / migrations:** none (V9 + V16 + V2 + V4 + V8 columns/enums/FKs already shipped).
- **Dependencies:** none (no `gradle/libs.versions.toml` change).
- **Invariants touched:** admin-session CSRF mandatory on the new POSTs; `AdminRoleGate.requireWriteRole` + the `ban_author` owner/admin tier; admin module's standing exemption from the `visible_*`-view + block-exclusion Detekt lint (the enforcement writes raw `reports`/`moderation_queue`/`users`/`posts`/`post_replies`); the shadow-ban stealth invariant (no notification); Pebble autoescape.
- **New enforcement surfaces introduced here:** permanent-ban (mirrors the suspend transaction shape) + shadow-ban (first surface to set `is_shadow_banned`). Suspend is reused, not reimplemented. Flagged in design Risks for scrutiny + because full enforcement raises the urgency of the `admin-destructive-action-rate-limit` follow-up (recommend sequencing it next).
- **Follow-ups:** closes `FOLLOW_UPS.md` `admin-report-queue-resolution-actions`; leaves `admin-report-queue-has-edit-history-filter` + `admin-destructive-action-rate-limit` intact.
