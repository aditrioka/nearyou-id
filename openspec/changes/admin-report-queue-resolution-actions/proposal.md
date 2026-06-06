## Why

The read-only Report Queue viewer (`GET /admin/reports`, capability `admin-report-queue`, [PR #154](https://github.com/aditrioka/nearyou-id/pull/154)) just shipped — a moderator can *see* the `reports` / `moderation_queue` backlog but cannot resolve anything in-panel: every row stays `status = pending` forever, and the only way to act is to follow the per-row deep-link to `/admin/users` and suspend/unban the author. The moderation loop is half-built. This change ships the **write-back half** — the read→write fast-follow that exactly mirrors how `admin-actions-log-viewer` (read) preceded `admin-user-moderation` (write). For an 18+ social app, in-panel report resolution is moderation-critical-path: you cannot responsibly soft-launch with a queue you can view but never clear.

## What Changes

- **New CSRF + role-gated POST resolution endpoint(s)** under the existing admin gate (`authenticate(ADMIN_AUTH_NAME)`), mirroring `AdminUserModerationRoute` exactly: CSRF validation first, write-role gate second, then an atomic repository transaction, then one immutable `admin_actions_log` row per action.
- **Report status transition** — `reports.status` `pending → actioned | dismissed`, setting `reviewed_by` + `reviewed_at`.
- **Moderation-queue resolution** — `moderation_queue.status` `pending → resolved`, setting the `resolution` value (the 6 content/author values `keep`/`hide`/`delete`/`shadow_ban_author`/`suspend_author_7d`/`ban_author`) + `resolved_by` + `resolved_at`.
- **Intrinsic auto-hide visibility toggle (in scope)** — resolving an `auto_hide_3_reports` queue item with `resolution = keep` restores the target's visibility (`is_auto_hidden = FALSE`, the direct inverse of the V9 BEFORE-INSERT auto-hide trigger that hid it); `resolution = hide` leaves it hidden. This is the one content mutation in scope because it is the symmetric inverse of what the queue itself created.
- **In-row resolution controls** in `reports-table.peb` / `reports.peb` — per-row form (CSRF hidden field + resolution select + submit), HTMX partial-swap with a plain-`GET`/no-JS fallback, mirroring the existing dual-mode render. The read-viewer's "no resolution control is rendered" expectation is inverted in the modified spec.
- **Author enforcement stays out of scope (recorded-only, via deep-link)** — recording `resolution = suspend_author_7d` / `ban_author` / `shadow_ban_author` does **not** mutate the `users` table; the moderator follows the existing `/admin/users` deep-link (suspend/unban already shipped) to enforce. Soft-`delete` content + shadow-ban enforcement have no surface yet → recorded-only, enforcement tracked as a follow-up. A negative-guard scenario asserts `resolution = ban_author` leaves `users` untouched.
- **No new Flyway migration** — `reports.status` (3-enum, V9), `moderation_queue.status`/`resolution` (V9 enums), the `reviewed_by`/`resolved_by` → `admin_users(id) ON DELETE SET NULL` FKs (V16), and the free-form `admin_actions_log.action_type VARCHAR(64)` (no CHECK, V16) all already exist.
- **Out of scope (unchanged):** the "post has edit history" prioritization filter remains deferred to the separate follow-up `admin-report-queue-has-edit-history-filter`; the per-admin destructive-action rate limit remains the separate follow-up `admin-destructive-action-rate-limit`; the username-moderation resolutions `accept_flagged_username` / `reject_flagged_username` (+ the `username_flagged` trigger) are owned by the future Premium Username Change Oversight feature (`docs/07-Operations.md` § Core Features) and are rejected by this endpoint.

## Capabilities

### New Capabilities
<!-- None — this extends the existing admin-report-queue surface/route subtree rather than introducing a new capability (rationale in design.md D1). -->

### Modified Capabilities
- `admin-report-queue`: the requirement "Report resolution write-back and the edit-history filter are explicitly deferred" is **split** — the resolution write-back is no longer deferred (it ships here, with new requirements for the report-status transition, queue resolution, the `keep`/`hide` visibility toggle, CSRF + role gating, the recorded-only/no-enforcement guard, lenient malformed-input handling, idempotent re-resolution, and HTML-escaped HTMX/no-JS controls); the "post has edit history" filter **remains** deferred to `admin-report-queue-has-edit-history-filter`.

## Impact

- **Code:** `backend/ktor/.../admin/routes/` (new resolution route, wired alongside `adminReportQueue` inside the auth block), `backend/ktor/.../admin/reportqueue/` (resolution repository + atomic transaction), `backend/ktor/.../admin/auth/AdminAuditLogger.kt` (new `report_resolved` / `moderation_queue_resolved` audit writers), `AdminModule.kt` (route registration).
- **Templates:** `backend/ktor/src/main/resources/templates/admin/reports-table.peb` + `reports.peb` (in-row resolution controls).
- **Schema / migrations:** none (all columns + enums already shipped at V9 + V16).
- **Dependencies:** none (no `gradle/libs.versions.toml` change).
- **Invariants touched:** admin-session CSRF mandatory on the new POST(s); `AdminRoleGate.requireWriteRole`; admin module's standing exemption from the `visible_*`-view + block-exclusion Detekt lint (the resolution writes raw `reports`/`moderation_queue`/`posts.is_auto_hidden`); Pebble autoescape on all rendered values.
- **Follow-ups unblocked / referenced:** closes `FOLLOW_UPS.md` `admin-report-queue-resolution-actions`; leaves `admin-report-queue-has-edit-history-filter` and `admin-destructive-action-rate-limit` as-is.
