## ADDED Requirements

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

### Requirement: moderation_queue context attached as a single representative row

The system SHALL attach `moderation_queue` context to each report via a single representative row selected by `LEFT JOIN LATERAL (… ORDER BY priority ASC, created_at DESC LIMIT 1)` keyed on the report's `(target_type, target_id)`. When a representative row exists, the rendered report row SHALL display its `trigger` and `priority`. When no `moderation_queue` row exists for the report's target (e.g. a report below the 3-reporter auto-hide threshold), the report row SHALL render without queue context and SHALL NOT error. A target with multiple `moderation_queue` rows (multiple triggers) SHALL produce exactly one display row for each report (no fan-out).

#### Scenario: Report with a moderation_queue row shows trigger and priority
- **GIVEN** an authenticated session AND a report whose `(target_type, target_id)` has a `moderation_queue` row with `trigger = auto_hide_3_reports`
- **WHEN** `GET /admin/reports` is served
- **THEN** that report's rendered row SHALL display the queue `trigger` and `priority`

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

### Requirement: The Report Queue route is strictly read-only

Serving `GET /admin/reports` (with any filter or cursor parameters) SHALL NOT write any `admin_actions_log` row and SHALL NOT mutate any table. This change SHALL NOT mount any mutation route under the report-queue surface.

#### Scenario: Serving the page writes no audit row and mutates nothing
- **GIVEN** an authenticated session AND a known count of `admin_actions_log` rows
- **WHEN** `GET /admin/reports` is served (with and without filters/cursor)
- **THEN** the `admin_actions_log` row count SHALL be unchanged
- **AND** no `reports` or `moderation_queue` row SHALL be inserted, updated, or deleted

### Requirement: Report resolution write-back and the edit-history filter are explicitly deferred

This change SHALL NOT provide any surface to resolve reports or mutate `moderation_queue` state. Specifically, it SHALL NOT render report-resolution controls (mark actioned / dismissed) that post to a resolution endpoint, SHALL NOT set `reports.status` / `reports.reviewed_by` / `reports.reviewed_at` or `moderation_queue.resolution` / `resolved_by` / `resolved_at`, and SHALL NOT expose a `trigger`-independent "post has edit history" prioritization filter. The deferred resolution write-back (status transitions + `moderation_queue` resolution + `admin_actions_log` audit row, CSRF + role-gated) SHALL ship as the separate change `admin-report-queue-resolution-actions`, and the "has edit history" filter as a follow-up; both SHALL be tracked in `FOLLOW_UPS.md`.

#### Scenario: No report-resolution control is rendered
- **GIVEN** an authenticated session AND a `pending` report
- **WHEN** `GET /admin/reports` is served
- **THEN** the report's `status` SHALL be displayed read-only
- **AND** the row SHALL NOT contain a resolution control (no "mark actioned" / "dismiss" form posting to a resolution endpoint)

#### Scenario: No resolution route is mounted by this change
- **WHEN** a client sends a state-changing request (POST / PATCH) to a report-resolution path under the report-queue surface
- **THEN** no such route is mounted by this change (the request does not resolve to a report-resolution handler introduced here)

#### Scenario: The edit-history prioritization filter is absent
- **WHEN** `GET /admin/reports?has_edit_history=true` is served
- **THEN** the parameter SHALL be ignored (no edit-history filtering is applied in this change) AND the response SHALL be 200
