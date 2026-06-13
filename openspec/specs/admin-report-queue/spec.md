# admin-report-queue Specification

## Purpose

The read-only admin Report Queue (`GET /admin/reports`) — the moderator triage surface that surfaces the `reports` / `moderation_queue` backlog and closes the moderation loop: a report comes in, the auto-hide-at-3-reporters trigger enqueues `moderation_queue`, the moderator opens the queue, and clicks through to the already-shipped `/admin/users` suspend/unban controls. It renders a session-gated (any valid admin session, not role-restricted), keyset-paginated, newest-first table over `reports` with optional `moderation_queue` context (a single representative row via `LEFT JOIN LATERAL`), composable parameterized filters (`status` / `target_type` / `reason_category` / `trigger` / `from`–`to` date range, lenient on malformed input), full HTML-escaping, and HTMX partial-swap with a plain-`GET` fallback — deep-linking each reported target's offending user to the user-moderation action surface. The `GET /admin/reports` listing remains read-only (writes no `admin_actions_log` row, mutates no table); state mutations are confined to the dedicated `/{id}/resolve` POST sub-routes. The in-queue resolution write-back now ships (capability `admin-report-queue-resolution-actions`): `POST /admin/reports/{id}/resolve` transitions a report's bookkeeping status (`actioned`/`dismissed`), and `POST /admin/moderation-queue/{id}/resolve` performs the named enforcement atomically — content un-hide/hide (`is_auto_hidden` on `post`/`reply`), 7-day suspend (reusing the shipped suspend path), permanent ban (owner/admin tier only), and shadow-ban (`is_shadow_banned`, with no user-facing notification as the stealth invariant) — CSRF + write-role-gated, idempotent, each writing exactly one immutable `admin_actions_log` row whose `after_state` records the enforcement effect. Only the "post has edit history" prioritization filter remains deferred (`admin-report-queue-has-edit-history-filter`). It is the moderation loop's read surface plus its in-panel write-back, mirroring how `admin-actions-log-viewer` (read) preceded `admin-user-moderation` (write).

## Requirements
### Requirement: Authenticated GET /admin/reports renders the report-queue table

The system SHALL serve `GET /admin/reports` as an authenticated route, wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block established by the `admin-login` capability, so the session middleware gates it (any valid admin session, matching `admin-actions-log-viewer`'s session gate — the read is NOT role-restricted; the `AdminPrincipal.role` is consumed only by the deferred write actions). On a valid session it SHALL return HTTP 200 with an HTML page that extends the shared admin base layout (per `admin-panel-scaffold`) and renders a table of `reports` rows ordered newest-first (`created_at DESC, id DESC`). Each rendered row SHALL display: `created_at`, `target_type`, `target_id`, `reason_category`, `reason_note`, `status`, and the reporter's identity (resolved from `reports.reporter_id`). The route SHALL be read-only — it SHALL NOT write any `admin_actions_log` row and SHALL NOT mutate any table. Reads of `reports`, `moderation_queue`, `users`, and the content base tables in the admin module are permitted (the admin module is exempt from the `visible_*`-view + block-exclusion lint).

#### Scenario: Authenticated request renders the table with report rows
- **GIVEN** an authenticated admin session AND at least one row exists in `reports`
- **WHEN** the client sends `GET /admin/reports` carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL be Pebble-rendered HTML containing the existing report's `reason_category` and `status`
- **AND** the rendered HTML SHALL contain the base-layout structural sections (header, nav, footer)

#### Scenario: Unauthenticated request redirects to the login page
- **WHEN** a client sends `GET /admin/reports` with no `__Host-admin_session` cookie (or an invalid / expired / revoked / idle-timed-out session)
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** no report-queue content SHALL be served

#### Scenario: Rows are ordered newest-first
- **GIVEN** an authenticated session AND three `reports` rows with strictly increasing `created_at` values
- **WHEN** `GET /admin/reports` is served
- **THEN** the row with the latest `created_at` SHALL appear before the others in the rendered table (`created_at DESC, id DESC` order)

#### Scenario: Empty result renders an empty state with HTTP 200
- **GIVEN** an authenticated session AND no `reports` rows match the (possibly filtered) request
- **WHEN** `GET /admin/reports` is served
- **THEN** the response status SHALL be 200
- **AND** the rendered body SHALL show an empty-state message (never a 404 or 500)

#### Scenario: Reporter is resolved to a human-readable identity
- **GIVEN** an authenticated session AND a report whose `reporter_id` references a `users` row with a known `username`
- **WHEN** `GET /admin/reports` is served
- **THEN** the rendered row SHALL contain the reporter's resolved `username`, not the bare `reporter_id` UUID alone

### Requirement: Keyset pagination over (created_at, id) with a fixed page size

The system SHALL paginate the report queue using a keyset cursor over `(created_at, id)` in descending order with a fixed page size. It SHALL NOT use SQL `OFFSET`. When more rows exist beyond the current page, the system SHALL render an "older" navigation control carrying an opaque cursor encoding the last-displayed row's `(created_at, id)`; following that control SHALL return the next-older page whose first row immediately precedes the cursor in `created_at DESC, id DESC` order. A malformed or absent cursor SHALL be treated as a request for the first (newest) page, never an error.

#### Scenario: Page is capped at the fixed page size
- **GIVEN** an authenticated session AND more `reports` rows than the fixed page size
- **WHEN** `GET /admin/reports` is served with no cursor
- **THEN** the rendered table SHALL contain exactly the page-size number of rows (the newest page)
- **AND** an "older" pagination control SHALL be present

#### Scenario: Following the cursor returns the next-older, non-overlapping page
- **GIVEN** an authenticated session AND two full pages of rows
- **WHEN** the client follows the "older" control's cursor URL
- **THEN** the returned rows SHALL all be strictly older (in `created_at DESC, id DESC` order) than the last row of the first page
- **AND** no row from the first page SHALL reappear on the second page

#### Scenario: Malformed cursor falls back to the first page
- **WHEN** an authenticated client sends `GET /admin/reports?cursor=not-a-valid-cursor`
- **THEN** the response status SHALL be 200
- **AND** the rendered table SHALL show the newest page (the malformed cursor is ignored, not treated as an error)

#### Scenario: Last page omits the older control
- **GIVEN** an authenticated session AND exactly one page or fewer of matching rows
- **WHEN** `GET /admin/reports` is served
- **THEN** no "older" pagination control SHALL be rendered

#### Scenario: Exactly one full page omits the older control (boundary)
- **GIVEN** an authenticated session AND exactly the page-size number of matching rows (no more)
- **WHEN** `GET /admin/reports` is served with no cursor
- **THEN** the rendered table SHALL contain exactly the page-size number of rows
- **AND** no "older" pagination control SHALL be rendered (there is no further page)

#### Scenario: The id tiebreaker prevents skip/duplicate when created_at ties across the page boundary
- **GIVEN** an authenticated session AND two or more `reports` rows sharing an identical `created_at` (e.g. a report and its auto-hide enqueue in one transaction) with distinct `id`, positioned so the page boundary falls between them
- **WHEN** the client loads the first page and then follows the "older" cursor
- **THEN** every tied-`created_at` row SHALL appear exactly once across the two pages (none skipped, none duplicated), because the cursor orders by `(created_at, id) DESC`

#### Scenario: Pagination composes with an active filter
- **GIVEN** an authenticated session AND more than one page of `status = pending` rows (alongside non-pending rows)
- **WHEN** the client loads `GET /admin/reports?status=pending` and follows the "older" cursor with `status=pending` still applied
- **THEN** the second page rows SHALL all be strictly older than the first page's last row AND SHALL all have `status = pending` AND no first-page row SHALL reappear

### Requirement: Composable, index-aligned, parameterized filtering

The system SHALL accept the query parameters `status`, `target_type`, `reason_category`, `trigger`, `from`, and `to`, each filtering the report queue and composing with logical AND. `status` SHALL match `reports.status` exactly (one of `pending` / `actioned` / `dismissed`). `target_type` SHALL match `reports.target_type` exactly. `reason_category` SHALL match `reports.reason_category` exactly. `trigger` SHALL constrain to reports for which a `moderation_queue` row with that `trigger` exists for the same `(target_type, target_id)` (an `EXISTS` predicate over `moderation_queue`, distinct from the display join in the moderation-queue-context requirement). `from` and `to` SHALL bound `created_at` (inclusive lower; `to` inclusive of the whole named day via an exclusive `< to + 1 day` upper bound). All filter values SHALL be applied via parameterized query placeholders — never string-interpolated into SQL.

#### Scenario: Filtering by status returns only matching rows
- **GIVEN** an authenticated session AND `reports` rows with `status` values `pending` and `dismissed`
- **WHEN** `GET /admin/reports?status=pending` is served
- **THEN** every rendered row SHALL have `status = pending`

#### Scenario: Filtering by target_type returns only matching rows
- **GIVEN** an authenticated session AND `reports` rows with `target_type` values `post` and `user`
- **WHEN** `GET /admin/reports?target_type=user` is served
- **THEN** every rendered row SHALL have `target_type = user`

#### Scenario: Filtering by reason_category returns only matching rows
- **GIVEN** an authenticated session AND `reports` rows with differing `reason_category` values
- **WHEN** `GET /admin/reports?reason_category=harassment` is served
- **THEN** every rendered row SHALL have `reason_category = harassment`

#### Scenario: Filtering by trigger constrains to reports with a matching moderation_queue row
- **GIVEN** an authenticated session AND a report whose `(target_type, target_id)` has a `moderation_queue` row with `trigger = auto_hide_3_reports`, AND another report whose target has no `moderation_queue` row
- **WHEN** `GET /admin/reports?trigger=auto_hide_3_reports` is served
- **THEN** only the report whose target has a matching `moderation_queue` row SHALL be rendered

#### Scenario: Date range bounds created_at inclusively of the whole "to" day
- **GIVEN** an authenticated session AND reports created on three different days
- **WHEN** `GET /admin/reports?from=<dayB>&to=<dayB>` is served
- **THEN** only reports with `created_at` within `dayB` (inclusive lower bound, exclusive `< dayB + 1 day` upper bound) SHALL be rendered

#### Scenario: Filters compose with logical AND
- **GIVEN** an authenticated session AND reports spanning multiple `status` and `target_type` values
- **WHEN** `GET /admin/reports?status=pending&target_type=post` is served
- **THEN** every rendered row SHALL have BOTH `status = pending` AND `target_type = post`

#### Scenario: Filter values are parameterized and injection-inert
- **WHEN** an authenticated client sends `GET /admin/reports?status=pending'); DROP TABLE reports;--`
- **THEN** the response status SHALL be 200 (or an empty/normal result) AND the `reports` table SHALL still exist (the value is bound as a parameter, never interpolated into SQL)

### Requirement: Malformed filter inputs are tolerated, never error

The system SHALL handle malformed or out-of-domain filter values leniently — it SHALL return HTTP 200 (with an empty or normal result), never a 4xx/5xx error, when a filter value is unparseable or out of its expected domain. An unparseable `from` / `to` date SHALL be ignored (treated as no date bound). An out-of-enum `status` / `target_type` / `reason_category` / `trigger` value SHALL be applied as a literal predicate (yielding zero matches), not rejected. A `from` later than `to` SHALL yield an empty result, not an error. This mirrors the lenient-on-malformed-input contract of `admin-actions-log-viewer`.

#### Scenario: Unparseable date is ignored
- **WHEN** an authenticated client sends `GET /admin/reports?from=13th-of-never`
- **THEN** the response status SHALL be 200 AND the date filter SHALL be ignored (no date bound applied), not treated as an error

#### Scenario: Out-of-enum filter value yields zero matches, not an error
- **WHEN** an authenticated client sends `GET /admin/reports?status=not-an-enum-value` (or an over-long value)
- **THEN** the response status SHALL be 200 AND the rendered body SHALL show the empty state (the value is bound as a literal that matches no row), never a 400 or 500

#### Scenario: from later than to yields an empty result
- **WHEN** an authenticated client sends `GET /admin/reports?from=<dayC>&to=<dayA>` where `dayC` is after `dayA`
- **THEN** the response status SHALL be 200 AND the rendered body SHALL show the empty state, not an error

### Requirement: moderation_queue context attached as a single representative row

The system SHALL attach `moderation_queue` context to each report via a single representative row selected by `LEFT JOIN LATERAL (… ORDER BY priority ASC, created_at DESC LIMIT 1)` keyed on the report's `(target_type, target_id)`. When a representative row exists, the rendered report row SHALL display its `trigger`, `priority`, and queue `status`. When no `moderation_queue` row exists for the report's target (e.g. a report below the 3-reporter auto-hide threshold), the report row SHALL render without queue context and SHALL NOT error. A target with multiple `moderation_queue` rows (multiple triggers) SHALL produce exactly one display row for each report (no fan-out).

#### Scenario: Report with a moderation_queue row shows trigger, priority, and queue status
- **GIVEN** an authenticated session AND a report whose `(target_type, target_id)` has a `moderation_queue` row with `trigger = auto_hide_3_reports` and `status = pending`
- **WHEN** `GET /admin/reports` is served
- **THEN** that report's rendered row SHALL display the queue `trigger`, `priority`, and queue `status`

#### Scenario: Report without a moderation_queue row renders without queue context
- **GIVEN** an authenticated session AND a report whose `(target_type, target_id)` has NO `moderation_queue` row
- **WHEN** `GET /admin/reports` is served
- **THEN** that report's row SHALL render successfully with no queue context (no trigger/priority) and SHALL NOT error

#### Scenario: Multiple queue triggers for one target do not fan out the report row
- **GIVEN** an authenticated session AND a single report whose target has two `moderation_queue` rows (two distinct `trigger` values)
- **WHEN** `GET /admin/reports` is served
- **THEN** exactly ONE display row SHALL be rendered for that report (the representative queue row, ordered `priority ASC, created_at DESC`), not two

### Requirement: Deep-link to the user-moderation action surface

For each report the system SHALL render a link to the offending user on the existing `admin-user-moderation` surface (`/admin/users?q=<user>`), resolved by `target_type`: `user` → the `target_id` directly; `post` → the post's author; `reply` → the reply's author; `chat_message` → the message sender. Author/sender resolution SHALL use `LEFT JOIN`s so that a hard-deleted target (no matching base row) renders the `target_id` as text WITHOUT a link rather than erroring.

#### Scenario: A user report links directly to that user
- **GIVEN** an authenticated session AND a report with `target_type = user` and `target_id = <U>`
- **WHEN** `GET /admin/reports` is served
- **THEN** the report's row SHALL contain a link to `/admin/users?q=<U>`

#### Scenario: A post report links to the post's author
- **GIVEN** an authenticated session AND a report with `target_type = post` for a post authored by user `<A>`
- **WHEN** `GET /admin/reports` is served
- **THEN** the report's row SHALL contain a link to `/admin/users?q=<A>`

#### Scenario: A reply report links to the reply's author
- **GIVEN** an authenticated session AND a report with `target_type = reply` for a reply authored by user `<A>`
- **WHEN** `GET /admin/reports` is served
- **THEN** the report's row SHALL contain a link to `/admin/users?q=<A>`

#### Scenario: A chat_message report links to the message sender
- **GIVEN** an authenticated session AND a report with `target_type = chat_message` for a message sent by user `<A>`
- **WHEN** `GET /admin/reports` is served
- **THEN** the report's row SHALL contain a link to `/admin/users?q=<A>`

#### Scenario: A hard-deleted target renders target_id without a link
- **GIVEN** an authenticated session AND a report whose target row has been hard-deleted (no matching base row)
- **WHEN** `GET /admin/reports` is served
- **THEN** the report's row SHALL render the `target_id` as text with NO action link and SHALL NOT error

### Requirement: All rendered values are HTML-escaped

The system SHALL HTML-escape every dynamic value rendered into the report-queue page, including the user-controlled `reason_note` and any joined display strings (usernames, etc.). It SHALL NOT render any dynamic value as raw/unescaped HTML.

#### Scenario: reason_note containing HTML is escaped
- **GIVEN** an authenticated session AND a report whose `reason_note` contains `<script>alert(1)</script>`
- **WHEN** `GET /admin/reports` is served
- **THEN** the rendered HTML SHALL contain the escaped form (e.g. `&lt;script&gt;`) and SHALL NOT contain an executable `<script>` element from the `reason_note` value

### Requirement: HTMX partial-swap with a plain-GET fallback

The system SHALL return only the swappable result-fragment element (no full-page `<html>` document wrapper, no base-layout header/footer) when the request carries `HX-Request: true`, and SHALL return the full page extending the base layout for a plain `GET`. Pagination and filter navigation SHALL function under both modes (HTMX-enhanced and plain links).

#### Scenario: HTMX request returns only the result fragment
- **GIVEN** an authenticated admin session
- **WHEN** the client sends `GET /admin/reports` with header `HX-Request: true`
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the swappable result-fragment element
- **AND** the response body SHALL NOT contain the full-page `<html>` document wrapper or the base-layout header/footer

#### Scenario: Plain GET returns the full page
- **GIVEN** an authenticated admin session
- **WHEN** the client sends `GET /admin/reports` with NO `HX-Request` header
- **THEN** the response body SHALL contain the full-page document extending the base layout (header, nav, footer)

### Requirement: The post-edit-history prioritization filter is deferred

This capability SHALL continue to defer the "post has edit history" prioritization filter to the separate change `admin-report-queue-has-edit-history-filter`. The `GET /admin/reports` listing SHALL ignore a `has_edit_history` parameter (no edit-history filtering applied) and respond 200. The report-resolution write-back that was previously bundled into this deferral is NO LONGER deferred — it ships in this change (see the resolution requirements added by `admin-report-queue-resolution-actions`), so this requirement now governs only the edit-history filter.

#### Scenario: The edit-history prioritization filter is absent
- **WHEN** `GET /admin/reports?has_edit_history=true` is served
- **THEN** the parameter SHALL be ignored (no edit-history filtering is applied) AND the response SHALL be 200

### Requirement: The GET /admin/reports listing never mutates state

Serving `GET /admin/reports` (with any filter or cursor parameters) SHALL NOT write any `admin_actions_log` row and SHALL NOT mutate any table — the listing is strictly read-only. State mutations on this capability SHALL be confined to the dedicated POST resolution sub-routes (`POST /admin/reports/{id}/resolve` and `POST /admin/moderation-queue/{id}/resolve`) introduced by this change; the bare collection path `/admin/reports` SHALL expose only `GET`.

#### Scenario: Serving the listing writes no audit row and mutates nothing
- **GIVEN** an authenticated session AND a known count of `admin_actions_log` rows
- **WHEN** `GET /admin/reports` is served (with and without filters/cursor)
- **THEN** the `admin_actions_log` row count SHALL be unchanged
- **AND** no `reports` or `moderation_queue` row SHALL be inserted, updated, or deleted

#### Scenario: The bare collection path exposes only GET
- **GIVEN** an authenticated session
- **WHEN** the client sends `POST /admin/reports` (the bare collection path, not a `/{id}/resolve` sub-route)
- **THEN** the response status SHALL be 405 (Method Not Allowed) — resolution is served by the `/{id}/resolve` sub-routes, not the collection path

### Requirement: Authenticated report-status resolution endpoint

The system SHALL serve `POST /admin/reports/{id}/resolve` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block. On a valid session with a valid CSRF token and a write-capable admin role, it SHALL accept a `decision` form field constrained to `{actioned, dismissed}` and, in ONE database transaction, set `reports.status` to that value, set `reports.reviewed_by` to the acting admin's id, set `reports.reviewed_at` to the current time, and write exactly one `admin_actions_log` row with `action_type = 'report_resolved'` (recording before/after status in `after_state`). The transition SHALL apply only to a report currently in `status = 'pending'` (conditional update; idempotency requirement governs the already-resolved case). This endpoint performs bookkeeping only — it SHALL NOT perform user or content enforcement (that is the moderation-queue resolution endpoint's role).

#### Scenario: A pending report is marked actioned
- **GIVEN** an authenticated write-role admin session with a valid CSRF token AND a `reports` row with `status = 'pending'`
- **WHEN** the client sends `POST /admin/reports/{id}/resolve` with `decision=actioned`
- **THEN** that report's `status` SHALL become `actioned` AND `reviewed_by` SHALL be the acting admin's id AND `reviewed_at` SHALL be set
- **AND** exactly one `admin_actions_log` row with `action_type = 'report_resolved'` SHALL be written

#### Scenario: A pending report is marked dismissed
- **GIVEN** an authenticated write-role admin session with a valid CSRF token AND a `pending` report
- **WHEN** the client sends `POST /admin/reports/{id}/resolve` with `decision=dismissed`
- **THEN** that report's `status` SHALL become `dismissed` AND `reviewed_by` + `reviewed_at` SHALL be set AND exactly one `report_resolved` audit row SHALL be written

### Requirement: Authenticated moderation-queue resolution endpoint performs the named enforcement

The system SHALL serve `POST /admin/moderation-queue/{id}/resolve` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block. On a valid session with a valid CSRF token and a sufficiently-roled admin (per the role-gating requirement), it SHALL accept a `resolution` form field constrained to the enforcement subset `{keep, hide, suspend_author_7d, ban_author, shadow_ban_author}` and, in ONE database transaction, set `moderation_queue.status = 'resolved'` + `resolution` + `resolved_by` + `resolved_at`, **perform the enforcement that resolution names** (per the content-resolution and author-resolution requirements), and write exactly one `admin_actions_log` row with `action_type = 'moderation_queue_resolved'` whose `after_state` records both the `resolution` value and the enforcement effect. The transition SHALL apply only to a queue row currently in `status = 'pending'`. The values `delete`, `accept_flagged_username`, and `reject_flagged_username` are OUT OF SCOPE and SHALL be rejected (see the malformed/out-of-scope requirement): `delete` is not in the in-row action set (`docs/07-Operations.md` § Core Features lists Hide/Dismiss/Suspend/Ban/Shadow-ban), and the username resolutions are owned by the future Premium Username Change Oversight feature.

#### Scenario: A pending queue item is resolved and the resolution is recorded
- **GIVEN** an authenticated write-role admin session with a valid CSRF token AND a `moderation_queue` row with `status = 'pending'` for a `post` target
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution=hide`
- **THEN** that queue row's `status` SHALL become `resolved` AND `resolution` SHALL be `hide` AND `resolved_by` + `resolved_at` SHALL be set
- **AND** exactly one `admin_actions_log` row with `action_type = 'moderation_queue_resolved'` SHALL be written whose `after_state` records `resolution = hide`

### Requirement: Content resolutions perform the visibility change

When the resolution is a content action and the queue row's `target_type ∈ {post, reply}`, the system SHALL perform the visibility change in the same transaction as the queue resolution: `keep` SHALL set the target's `is_auto_hidden = FALSE` (restoring visibility — the inverse of the V9-era application-level auto-hide writer in `ReportService`, NOT a DB trigger), and `hide` SHALL set `is_auto_hidden = TRUE`. The toggle SHALL apply to both `posts` (`target_type = 'post'`) and `post_replies` (`target_type = 'reply'`). For a `keep`/`hide` resolution whose `target_type ∈ {user, chat_message}` (no `is_auto_hidden` column), the resolution SHALL be rejected with a message and SHALL perform no write (a content action is not applicable to a user/chat target).

#### Scenario: keep restores visibility of an auto-hidden post
- **GIVEN** a `moderation_queue` row for a `post` whose `is_auto_hidden = TRUE`
- **WHEN** the queue row is resolved with `resolution=keep`
- **THEN** that post's `is_auto_hidden` SHALL become `FALSE` in the same transaction as the queue resolution

#### Scenario: hide hides a reply target
- **GIVEN** a `moderation_queue` row for a `reply` (`target_type = 'reply'`) whose `is_auto_hidden = FALSE`
- **WHEN** the queue row is resolved with `resolution=hide`
- **THEN** that reply's `post_replies.is_auto_hidden` SHALL become `TRUE`

#### Scenario: A content resolution on a user target is rejected
- **GIVEN** a `moderation_queue` row with `target_type = 'user'`
- **WHEN** the queue row is resolved with `resolution=keep` (or `hide`)
- **THEN** the resolution SHALL be rejected with a message AND no `is_auto_hidden` write SHALL occur AND no queue mutation SHALL occur (a content action is not applicable to a user target)

### Requirement: Author resolutions perform user-state enforcement on the resolved author

When the resolution is an author action (`suspend_author_7d`, `ban_author`, `shadow_ban_author`), the system SHALL resolve the offending author from the queue row's target (`post`→author, `reply`→author, `user`→self, `chat_message`→sender, using `LEFT JOIN`s) and perform the enforcement on that author in the same transaction as the queue resolution. `suspend_author_7d` SHALL apply the shipped 7-day suspension (reusing the `admin-user-moderation` suspend path: `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '7 days'`, plus the sanitized `account_action_applied` notification, plus the soft-deleted/already-permanently-banned guards). `ban_author` SHALL apply a permanent ban (`is_banned = TRUE`, `suspended_until = NULL`) plus the `account_action_applied` notification. `shadow_ban_author` SHALL set `users.is_shadow_banned = TRUE`. When the author cannot be resolved (hard-deleted target), the resolution SHALL be rejected with no write. When a suspend guard rejects (target soft-deleted, or already permanently banned), the resolution SHALL be rejected and the queue SHALL NOT be marked resolved.

#### Scenario: suspend_author_7d suspends the post's author
- **GIVEN** a `moderation_queue` row for a `post` authored by user `A` (not banned, not deleted)
- **WHEN** the queue row is resolved with `resolution=suspend_author_7d`
- **THEN** user `A` SHALL have `is_banned = TRUE` AND `suspended_until` ≈ NOW() + 7 days AND an `account_action_applied` notification SHALL be inserted for `A`
- **AND** the queue row SHALL be `resolved` with `resolution = suspend_author_7d` AND exactly one `moderation_queue_resolved` audit row SHALL be written

#### Scenario: ban_author permanently bans the resolved author
- **GIVEN** an owner/admin session AND a `moderation_queue` row whose resolved author is user `A` (not banned)
- **WHEN** the queue row is resolved with `resolution=ban_author`
- **THEN** user `A` SHALL have `is_banned = TRUE` AND `suspended_until = NULL` (permanent) AND an `account_action_applied` notification SHALL be inserted for `A`

#### Scenario: shadow_ban_author sets the shadow-ban flag
- **GIVEN** a `moderation_queue` row whose resolved author is user `A` (`is_shadow_banned = FALSE`)
- **WHEN** the queue row is resolved with `resolution=shadow_ban_author`
- **THEN** user `A` SHALL have `is_shadow_banned = TRUE`

#### Scenario: A hard-deleted target whose author cannot be resolved is rejected
- **GIVEN** a `moderation_queue` row whose target row has been hard-deleted (no resolvable author)
- **WHEN** the queue row is resolved with an author resolution (e.g. `ban_author`)
- **THEN** the resolution SHALL be rejected with a message AND no `users` mutation AND no queue mutation SHALL occur

#### Scenario: suspend on an already-permanently-banned author is rejected without resolving the queue
- **GIVEN** a `moderation_queue` row whose resolved author is already permanently banned (`is_banned = TRUE`, `suspended_until = NULL`)
- **WHEN** the queue row is resolved with `resolution=suspend_author_7d`
- **THEN** the suspend SHALL be rejected (no downgrade) AND the queue row SHALL remain `pending` AND no audit row SHALL be written

#### Scenario: An author action on a user target resolves to that user itself
- **GIVEN** a `moderation_queue` row with `target_type = 'user'` and `target_id = <U>` (`U.is_shadow_banned = FALSE`)
- **WHEN** the queue row is resolved with `resolution=shadow_ban_author`
- **THEN** user `<U>` itself SHALL have `is_shadow_banned = TRUE` (the author of a `user` target is the user itself; a `chat_message` target resolves to its sender analogously)

### Requirement: ban_author is restricted to the owner/admin tier

Issuing a permanent ban via `resolution=ban_author` SHALL require the owner or admin role, mirroring the existing rule that lifting a permanent ban is owner/admin-only (`admin-user-moderation`). An admin whose role is `moderator` (otherwise write-capable) SHALL be rejected when selecting `ban_author`, with no `users` mutation and no queue mutation. The tier check SHALL be enforced inside the resolution transaction so the rejection writes nothing.

#### Scenario: A moderator cannot ban via resolution
- **GIVEN** an authenticated `moderator`-role admin with a valid CSRF token AND a `pending` queue row
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution=ban_author`
- **THEN** the request SHALL be rejected AND user state SHALL be unchanged (`is_banned` not set) AND the queue row SHALL remain `pending` AND no audit row SHALL be written

#### Scenario: An owner or admin can ban via resolution
- **GIVEN** an authenticated `admin`-role session with a valid CSRF token AND a `pending` queue row whose resolved author is not banned
- **WHEN** the client sends `resolution=ban_author`
- **THEN** the author SHALL be permanently banned AND the queue row SHALL be `resolved`

### Requirement: shadow_ban_author writes no user-facing notification (stealth invariant)

Because a shadow ban is, by design, invisible to the affected user, resolving with `resolution=shadow_ban_author` SHALL NOT insert any `notifications` row for the author (no `account_action_applied`, no other type). The action SHALL still write its `admin_actions_log` row for accountability. By contrast, `suspend_author_7d` and `ban_author` SHALL insert the sanitized `account_action_applied` notification.

#### Scenario: Shadow-ban inserts no notification
- **GIVEN** a `moderation_queue` row whose resolved author is user `A` AND a known count of `A`'s `notifications` rows
- **WHEN** the queue row is resolved with `resolution=shadow_ban_author`
- **THEN** `A`'s `notifications` row count SHALL be unchanged (no notification inserted) AND `A`'s `is_shadow_banned` SHALL be `TRUE` AND a `moderation_queue_resolved` audit row SHALL be written

#### Scenario: Suspend inserts the account_action_applied notification
- **GIVEN** a `moderation_queue` row whose resolved author is user `A`
- **WHEN** the queue row is resolved with `resolution=suspend_author_7d`
- **THEN** exactly one `account_action_applied` notification SHALL be inserted for `A`

### Requirement: Resolution writes are session-, CSRF-, and write-role-gated

Both resolution endpoints SHALL enforce, in order, the `admin-login` session gate, then CSRF validation, then the role gate — mirroring `admin-user-moderation`. An unauthenticated (or expired/revoked/idle-timed-out) request SHALL redirect 302 to `/admin/login` and write nothing. A request whose CSRF token is missing or invalid SHALL return 403, emit the `admin_csrf_violation` audit entry, and perform no write. An authenticated admin whose role is not write-capable (`AdminRoleGate.requireWriteRole`) SHALL be rejected with no write. CSRF SHALL be validated BEFORE the role gate.

#### Scenario: Unauthenticated resolution request redirects and writes nothing
- **WHEN** a client sends `POST /admin/reports/{id}/resolve` (or `POST /admin/moderation-queue/{id}/resolve`) with no valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 302 with `Location: /admin/login` AND no `reports` / `moderation_queue` / `users` / `admin_actions_log` row SHALL be written

#### Scenario: Missing or invalid CSRF token is rejected and audited
- **GIVEN** an authenticated admin session
- **WHEN** the client sends a resolution `POST` without a valid CSRF token
- **THEN** the response status SHALL be 403 AND an `admin_csrf_violation` audit entry SHALL be recorded AND no resolution/enforcement write SHALL occur

#### Scenario: CSRF is validated before the role gate
- **GIVEN** an authenticated admin whose role is NOT write-capable (`read_only`)
- **WHEN** the client sends a resolution `POST` WITHOUT a valid CSRF token
- **THEN** the rejection SHALL be the CSRF rejection (403 + `admin_csrf_violation`), demonstrating CSRF is evaluated before the role gate, AND no write SHALL occur

#### Scenario: A non-write-role admin is gated
- **GIVEN** an authenticated `read_only` admin AND a valid CSRF token
- **WHEN** the client sends a resolution `POST`
- **THEN** the request SHALL be rejected by the role gate AND no `reports` / `moderation_queue` / `users` row SHALL be mutated

### Requirement: Enforcement, resolution, and audit are atomic

For every resolution, the enforcement write(s), the `reports`/`moderation_queue` status write, and the `admin_actions_log` row (plus any `account_action_applied` notification) SHALL commit or roll back together in one transaction. There SHALL be no observable partial state — no enforcement applied without its audit row, no queue marked `resolved` without the enforcement, and no audit row without the state change.

#### Scenario: An audit-write failure rolls back the enforcement and the resolution
- **GIVEN** a `pending` `moderation_queue` row for a `post` whose `is_auto_hidden = TRUE`, AND the audit insert is made to fail (fault injection, as in the `admin-user-moderation` rollback tests)
- **WHEN** the queue row is resolved with `resolution=keep`
- **THEN** the transaction SHALL roll back: the post's `is_auto_hidden` SHALL remain `TRUE`, the queue row SHALL remain `pending`, and no `admin_actions_log` row SHALL be written

### Requirement: Malformed, out-of-scope, and repeat resolution inputs are tolerated and idempotent

The resolution endpoints SHALL tolerate bad input without a 5xx and SHALL be idempotent. A malformed path `{id}` (not a UUID) SHALL return 400 with no write. A `decision`/`resolution` value outside the accepted allowlist SHALL be rejected via a server-side check BEFORE any DB write (never relying on a DB `CHECK` to throw, which would surface as a 5xx): this includes out-of-enum garbage AND the in-enum-but-out-of-scope values `delete`, `accept_flagged_username`, `reject_flagged_username`. Re-resolving an already-resolved (non-`pending`) row SHALL be a safe no-op: the conditional `UPDATE … WHERE status = 'pending'` affects zero rows, so no enforcement is performed, no status changes, no duplicate `admin_actions_log` row is written, and no error is returned (this also serializes the two-admins-resolve-the-same-row race — the loser is a no-op).

#### Scenario: Malformed id yields 400 with no write
- **WHEN** an authenticated write-role admin sends `POST /admin/reports/not-a-uuid/resolve` with a valid CSRF token
- **THEN** the response status SHALL be 400 AND no `reports` / `moderation_queue` / `users` / `admin_actions_log` row SHALL be written

#### Scenario: Out-of-scope resolution value is rejected before any DB write
- **GIVEN** a `moderation_queue` row
- **WHEN** an authenticated write-role admin sends `POST /admin/moderation-queue/{id}/resolve` with `resolution=delete` (or `accept_flagged_username`, or a non-enum garbage value) and a valid CSRF token
- **THEN** the value SHALL be rejected by the server-side allowlist with no `moderation_queue`/`users` mutation and no `admin_actions_log` row AND the response SHALL NOT be a 5xx

#### Scenario: Re-resolving an already-resolved row is a safe no-op
- **GIVEN** a `moderation_queue` row already in `status = 'resolved'` with a recorded `resolution`, and a known `admin_actions_log` count
- **WHEN** an authenticated write-role admin sends `POST /admin/moderation-queue/{id}/resolve` again with a valid CSRF token
- **THEN** no enforcement SHALL be re-performed AND no second `admin_actions_log` row SHALL be written (count unchanged) AND the existing `resolution`/`resolved_by` SHALL be unchanged AND no error SHALL be returned

### Requirement: Resolving a queue item does not cascade to sibling reports

Resolving a `moderation_queue` item SHALL NOT auto-transition the `reports` rows that share the same `(target_type, target_id)` — those reports' `status` SHALL remain `pending` until each is explicitly resolved via the report-status endpoint. (Auto-cascade is deliberately deferred; this negative guard documents the v1 behavior so a follow-up has an explicit requirement to MODIFY.)

#### Scenario: Sibling reports stay pending after a queue item is resolved
- **GIVEN** a `moderation_queue` row (`trigger = auto_hide_3_reports`) for a `post` target AND three `pending` `reports` rows for that same `(target_type, target_id)`
- **WHEN** the queue row is resolved with `resolution=hide`
- **THEN** the queue row SHALL become `resolved` AND all three `reports` rows SHALL remain `status = pending`

### Requirement: In-row resolution controls render escaped, HTMX-partial with a no-JS fallback

The report-queue table SHALL render in-row resolution controls — a per-row form carrying the session CSRF token plus a `decision`/`resolution` selector that posts to the resolution endpoint. Because the resolution actions take effect immediately (Suspend/Ban/Shadow-ban are destructive user-state changes), the controls SHALL make the destructive, immediate nature clear (e.g. a confirm affordance / explicit labels) rather than implying a deferred or record-only decision. Every dynamic value rendered into the controls SHALL be HTML-escaped. An `HX-Request: true` request SHALL return only the swappable table fragment; a plain `GET` SHALL return the full page. A successful resolution SHALL re-render the re-queried table fragment for an HTMX request, or 303-redirect back to the (filter-preserving) queue for the no-JS path.

#### Scenario: A resolution control is rendered for a resolvable row
- **GIVEN** an authenticated session AND a `pending` report whose target has a representative `moderation_queue` row
- **WHEN** `GET /admin/reports` is served
- **THEN** that row SHALL render a resolution form (with a CSRF hidden field) posting to the resolution endpoint (inverting the read-only viewer's "no resolution control is rendered")

#### Scenario: Rendered control values are HTML-escaped
- **GIVEN** an authenticated session AND a report whose `reason_note` contains `<script>alert(1)</script>`
- **WHEN** `GET /admin/reports` is served with the resolution controls
- **THEN** the rendered HTML SHALL contain the escaped form (e.g. `&lt;script&gt;`) and SHALL NOT contain an executable `<script>` element from the `reason_note` value

#### Scenario: A successful no-JS resolution redirects back to the queue
- **GIVEN** an authenticated write-role admin submitting a plain `POST` (no `HX-Request` header) that resolves a report with a valid CSRF token
- **WHEN** the resolution succeeds
- **THEN** the response SHALL be a 303 redirect back to `/admin/reports` (preserving any active filters)



### Requirement: Destructive moderation-queue resolutions enforce the per-admin destructive-action cap

The destructive moderation-queue resolutions served by `POST /admin/moderation-queue/{id}/resolve` — `suspend_author_7d`, `ban_author`, and `shadow_ban_author` — SHALL enforce `admin-destructive-action-rate-limit` before performing enforcement: when the acting admin is at or over the cap (20 destructive actions in the trailing hour), the resolution SHALL be rejected with an inline "quota exceeded" state, leaving the `moderation_queue` row in `status = 'pending'`, performing NO author enforcement (no `users` mutation), and writing NO `admin_actions_log` row for the rejected attempt. The non-destructive resolutions (`keep`, `hide`) and the report-status bookkeeping (`POST /admin/reports/{id}/resolve` with `decision` in `{actioned, dismissed}`) are NOT in the destructive set and SHALL NOT be gated by the cap.

#### Scenario: A ban resolution beyond the cap is rejected without enforcement

- **GIVEN** an authenticated `admin` at the destructive-action cap (20 in the trailing hour) AND a `pending` `moderation_queue` row for a `post` target whose author is active
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution = ban_author`
- **THEN** the response SHALL surface a "quota exceeded" state (not a 5xx) AND the queue row SHALL stay `status = 'pending'` AND the author's `is_banned` SHALL be unchanged AND no new `admin_actions_log` row SHALL be written

#### Scenario: A non-destructive resolution is allowed even at the cap

- **GIVEN** an authenticated write-role admin at the destructive-action cap AND a `pending` `moderation_queue` row for a `post` target
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution = hide`
- **THEN** the resolution SHALL apply normally (content `hide` is not destructive) AND the queue row SHALL become `status = 'resolved'`

#### Scenario: Report-status bookkeeping is allowed even at the cap

- **GIVEN** an authenticated write-role admin at the destructive-action cap AND a `pending` `reports` row
- **WHEN** the client sends `POST /admin/reports/{id}/resolve` with `decision = dismissed`
- **THEN** the report-status transition SHALL apply normally (bookkeeping is not destructive)

#### Scenario: A destructive resolution under the cap proceeds

- **GIVEN** an authenticated `admin` with 19 destructive-action rows in the trailing hour AND a `pending` `moderation_queue` row whose author is active
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution = suspend_author_7d`
- **THEN** the suspend enforcement SHALL apply AND the queue row SHALL become `status = 'resolved'` AND exactly one new `admin_actions_log` row SHALL be written
