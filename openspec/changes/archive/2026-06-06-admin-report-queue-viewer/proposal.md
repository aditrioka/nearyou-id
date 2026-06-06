## Why

User-side report submission (`POST /api/v1/reports`, V9) and the auto-hide-at-3-reporters trigger have shipped, and `admin-user-moderation` (#134) shipped the suspend / unban **action** — but there is **no admin surface that surfaces what to act on**. A moderator today has no way to see the `reports` / `moderation_queue` backlog; the suspend/ban action is a dangling capability with nothing driving it. The Report Queue is the keystone that closes the moderation loop: report comes in → auto-hide enqueues `moderation_queue` → moderator opens the queue → clicks through to the existing `/admin/users` suspend/unban surface. For an 18+ social MVP, a moderator triage view is launch-critical.

This is the first admin **business** read surface after `admin-actions-log-viewer` (#123) and follows the same proven pattern: ship the read-only viewer first, layer the write actions (report resolution) in a fast-follow change — exactly as `admin-actions-log-viewer` (read-only) preceded `admin-user-moderation` (writes).

## What Changes

- **New read-only route `GET /admin/reports`** wired inside the `admin-login` session gate (`authenticate(ADMIN_AUTH_NAME)`; any valid admin session, not role-restricted — matching `admin-actions-log-viewer`), rendering a moderator triage table over the `reports` table with optional `moderation_queue` context.
- **Reports-centric paginated table**, newest-first (`created_at DESC, id DESC`), via a **keyset cursor over `(created_at, id)`** (no SQL `OFFSET`) — mirroring the `admin-actions-log-viewer` pagination contract.
- **Composable, AND-combined, index-aligned, parameterized filters**: `status` (pending/actioned/dismissed), `target_type` (post/reply/user/chat_message), `reason_category` (8-enum), `trigger` (from the joined `moderation_queue`), and a `from`–`to` `created_at` date range (inclusive lower, exclusive `< to + 1 day` upper).
- **`moderation_queue` LEFT JOIN** on `(target_type, target_id)`: a report with a queue row shows `trigger` / `priority` / queue `status`; a report below the 3-reporter auto-hide threshold (no queue row) renders without queue context and never crashes.
- **Deep-link to the existing action surface**: each reported target's author links to `/admin/users?q=<author-uuid>` (for `target_type = 'user'`, directly to `q=<target_id>`), where the shipped suspend/unban controls live.
- **HTML-escapes every rendered value** (especially the user-controlled `reason_note`, the primary XSS surface) and supports **HTMX partial-swap** (HX-Request → fragment) with a plain-`GET` progressive-enhancement fallback.
- **Strictly read-only** (explicit negative-guard requirement): the route writes **zero** `admin_actions_log` rows and mutates no table.
- **Possible single index-only Flyway migration (V19)** if keyset ordering over `(created_at DESC, id DESC)` warrants a dedicated index beyond the shipped `reports_status_idx` — decided in `design.md`. No schema changes (tables + admin FKs shipped at V9 + V16).
- **Explicitly deferred (recorded as spec requirements, not just prose)**: in-queue **resolution write-back** (mark report actioned/dismissed, set `moderation_queue.resolution` + `resolved_by`/`resolved_at`, write `admin_actions_log`) → fast-follow `admin-report-queue-resolution-actions`; in-row Hide/Dismiss/Shadow-ban write actions; the "post has edit history" prioritization filter.

## Capabilities

### New Capabilities
- `admin-report-queue`: the read-only admin Report Queue surface (`GET /admin/reports`) — authenticated, session-gated (not role-restricted) reports triage table with `moderation_queue` join, keyset pagination, composable filters (with lenient malformed-input handling), HTML-escaped HTMX rendering, deep-links to the user-moderation action surface, and the explicit read-only + deferred-resolution requirements.

### Modified Capabilities
<!-- None. The viewer is purely additive: it reads reports + moderation_queue + users (admin-module raw-read exemption) without changing their requirements, and consumes the admin-login / admin-panel-scaffold gate + layout without modifying them. -->

## Impact

- **Code**: `:backend:ktor` `admin` package — new `ReportQueueRoute` + Pebble template(s) + a read-only repository/query over `reports` ⟕ `moderation_queue` (+ `users`/`admin_users` joins for display). Mounted inside the existing `authenticate(ADMIN_AUTH_NAME)` block, under the `app/admin/` package so the admin-module lint exemption applies (the `moderation` package's exemption is narrowly `Report*`-file-scoped and is NOT the right home for this route). Reuses the `admin-panel-scaffold` base layout, the `admin-login` session gate + `AdminPrincipal`, and the keyset-cursor + HTMX-fragment patterns established by `admin-actions-log-viewer`.
- **Schema / migrations**: none required; at most one index-only `V19` migration (decided in design). `reports`, `moderation_queue`, and their `admin_users` FKs already shipped (V9, V16).
- **Lint/invariants**: admin module is exempt from the `visible_*`-view + block-exclusion rules (raw reads of `reports`/`moderation_queue`/`users` permitted, mirroring `admin-user-moderation`). All filter values applied via parameterized JDBC placeholders. No new secret reads, no rate-limit surface, no new `libs.versions.toml` pin.
- **Docs**: aligns with `docs/07-Operations.md` §Core Features "Report Queue" (flips it from DESIGN to partially-shipped at archive time); `docs/02-Product.md` §4 Report System.
- **Follow-ups**: `admin-report-queue-resolution-actions` (write surface) + the "has edit history" filter, both logged to `FOLLOW_UPS.md`.
