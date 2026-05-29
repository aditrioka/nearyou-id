## ADDED Requirements

### Requirement: Authenticated GET /admin/actions-log renders the audit-log table

The system SHALL serve `GET /admin/actions-log` as an authenticated route, wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block established by the `admin-login` capability so the session middleware gates it. On a valid session it SHALL return HTTP 200 with an HTML page that extends the shared admin base layout (per `admin-panel-scaffold`) and renders a table of `admin_actions_log` rows ordered newest-first (`created_at DESC, id DESC`). Each rendered row SHALL display: `created_at`, the acting admin resolved to a human-readable identity (`admin_users.display_name` and `email`, joined on `admin_id`), `action_type`, `target_type` and `target_id`, `reason`, `ip`, and `user_agent`. The route SHALL be read-only — it SHALL NOT write any `admin_actions_log` row, and it SHALL NOT mutate any table.

#### Scenario: Authenticated request renders the table with audit rows

- **GIVEN** an authenticated admin session AND at least one row exists in `admin_actions_log`
- **WHEN** the client sends `GET /admin/actions-log` carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL be Pebble-rendered HTML that contains the existing audit row's `action_type` value AND the acting admin's `display_name`
- **AND** the rendered HTML SHALL contain the base-layout structural sections (header, nav, footer)

#### Scenario: Unauthenticated request redirects to the login page

- **WHEN** a client sends `GET /admin/actions-log` with no `__Host-admin_session` cookie (or an invalid / expired / revoked / idle-timed-out session)
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** no audit-log table content SHALL be served

#### Scenario: Rows are ordered newest-first

- **GIVEN** an authenticated session AND three `admin_actions_log` rows with strictly increasing `created_at` values
- **WHEN** `GET /admin/actions-log` is served
- **THEN** the row with the latest `created_at` SHALL appear before the others in the rendered table (newest-first `created_at DESC, id DESC` order)

#### Scenario: Acting admin is resolved to a human-readable identity

- **GIVEN** an authenticated session AND an `admin_actions_log` row whose `admin_id` references an `admin_users` row with `display_name = 'Oka'`
- **WHEN** `GET /admin/actions-log` is served
- **THEN** the rendered row SHALL contain `Oka` (the joined `display_name`), not the raw `admin_id` UUID alone

### Requirement: Keyset pagination over (created_at, id) with a fixed page size

The system SHALL paginate the audit log using a keyset cursor over `(created_at, id)` in descending order with a fixed page size. It SHALL NOT use SQL `OFFSET` for pagination. When more rows exist beyond the current page, the system SHALL render an "older" navigation control carrying an opaque cursor encoding the last-displayed row's `(created_at, id)`; following that control SHALL return the next-older page whose first row immediately precedes the cursor in `created_at DESC, id DESC` order. A malformed or absent cursor SHALL be treated as a request for the first (newest) page, never an error.

#### Scenario: Page is capped at the fixed page size

- **GIVEN** an authenticated session AND more `admin_actions_log` rows than the fixed page size
- **WHEN** `GET /admin/actions-log` is served with no cursor
- **THEN** the rendered table SHALL contain exactly the page-size number of rows (the newest page)
- **AND** an "older" pagination control SHALL be present

#### Scenario: Following the cursor returns the next-older, non-overlapping page

- **GIVEN** an authenticated session AND two full pages of rows
- **WHEN** the client follows the "older" control's cursor URL
- **THEN** the returned rows SHALL all be strictly older (in `created_at DESC, id DESC` order) than the last row of the first page
- **AND** no row from the first page SHALL reappear on the second page

#### Scenario: Malformed cursor falls back to the first page

- **WHEN** an authenticated client sends `GET /admin/actions-log?cursor=not-a-valid-cursor`
- **THEN** the response status SHALL be 200
- **AND** the rendered table SHALL show the newest page (the malformed cursor is ignored, not treated as an error)

#### Scenario: Last page omits the older control

- **GIVEN** an authenticated session AND exactly one page or fewer of matching rows
- **WHEN** `GET /admin/actions-log` is served
- **THEN** no "older" pagination control SHALL be rendered (there are no older rows)

### Requirement: Composable, index-aligned filtering

The system SHALL accept the query parameters `action_type`, `admin_id`, `target_type`, `target_id`, `from`, and `to`, each filtering the audit log and composing with logical AND. `action_type` SHALL match exactly. `admin_id` SHALL match the acting admin's UUID exactly. `target_type` SHALL match exactly, and when `target_id` is also present it SHALL further constrain to that target id. `from` and `to` SHALL bound `created_at` (inclusive lower; `to` inclusive of the whole named day via an exclusive `< to + 1 day` upper bound). All filter values SHALL be applied via parameterized query placeholders — never string-interpolated into SQL.

#### Scenario: Filtering by action_type returns only matching rows

- **GIVEN** an authenticated session AND rows with `action_type` values `admin_login_success` and `admin_logout`
- **WHEN** `GET /admin/actions-log?action_type=admin_login_success` is served
- **THEN** every rendered row SHALL have `action_type = admin_login_success`
- **AND** no `admin_logout` row SHALL be rendered

#### Scenario: Filtering by admin_id returns only that admin's rows

- **GIVEN** an authenticated session AND audit rows from two distinct `admin_id` values
- **WHEN** `GET /admin/actions-log?admin_id=<first-admin-uuid>` is served
- **THEN** every rendered row SHALL belong to the first admin

#### Scenario: Filters compose with AND

- **GIVEN** an authenticated session AND rows spanning multiple `action_type` + `admin_id` combinations
- **WHEN** `GET /admin/actions-log?action_type=admin_login_success&admin_id=<uuid>` is served
- **THEN** every rendered row SHALL satisfy BOTH `action_type = admin_login_success` AND `admin_id = <uuid>`

#### Scenario: Date range bounds created_at with inclusive whole-day upper bound

- **GIVEN** an authenticated session AND rows on `2026-05-20`, `2026-05-25`, and `2026-05-30`
- **WHEN** `GET /admin/actions-log?from=2026-05-25&to=2026-05-25` is served
- **THEN** the `2026-05-25` row(s) SHALL be rendered (the whole of 2026-05-25 is included)
- **AND** the `2026-05-20` and `2026-05-30` rows SHALL NOT be rendered

### Requirement: Malformed filter inputs are handled safely without error or injection

The system SHALL tolerate malformed filter inputs without returning a 500 and without executing attacker-controlled SQL. A non-UUID `admin_id`, an unparseable `from` / `to` date, or an over-long `action_type` / `target_type` value SHALL cause that single filter to be ignored (lenient parse) while the remaining valid filters still apply. Because all values are bound as query parameters, a value containing SQL metacharacters SHALL be treated as a literal filter value, never as SQL.

#### Scenario: Non-UUID admin_id is ignored, other filters still apply

- **WHEN** an authenticated client sends `GET /admin/actions-log?admin_id=not-a-uuid&action_type=admin_login_success`
- **THEN** the response status SHALL be 200
- **AND** the rendered rows SHALL be filtered by `action_type = admin_login_success` (the invalid `admin_id` is ignored)

#### Scenario: SQL-metacharacter filter value is treated as a literal

- **WHEN** an authenticated client sends `GET /admin/actions-log?action_type=%27%3B+DROP+TABLE+admin_actions_log%3B--` (URL-encoded `'; DROP TABLE admin_actions_log;--`)
- **THEN** the response status SHALL be 200
- **AND** the `admin_actions_log` table SHALL still exist and be queryable afterward (the value matched zero rows as a literal `action_type`; no SQL was executed from it)

#### Scenario: Unparseable date is ignored

- **WHEN** an authenticated client sends `GET /admin/actions-log?from=13th-of-never`
- **THEN** the response status SHALL be 200
- **AND** the result SHALL be unfiltered by date (the invalid `from` is ignored)

#### Scenario: Over-long filter value is bounded, not errored

- **WHEN** an authenticated client sends `GET /admin/actions-log?action_type=<a string far longer than the 64-char column width>`
- **THEN** the response status SHALL be 200 (the over-long value is length-bounded during lenient parse and applied as a literal filter — which matches zero rows — rather than causing a 400/500)

### Requirement: HTMX partial swap with plain-GET progressive enhancement

The system SHALL serve the audit-log table as an HTMX-swappable fragment AND as a full standalone page from the same route, branching on the `HX-Request` header. When the request carries `HX-Request: true`, the system SHALL respond with only the table fragment (the swappable `#actions-log-table` element) so the filter form and surrounding layout remain in place. When the request does NOT carry `HX-Request`, the system SHALL respond with the full page (which includes the same table fragment), so filtering and pagination work without JavaScript. The filtered/paginated URL SHALL remain shareable (a plain `GET` to a filtered URL SHALL reproduce the same filtered view).

#### Scenario: HTMX request returns only the table fragment

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/actions-log?action_type=admin_login_success` with header `HX-Request: true`
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the `id="actions-log-table"` element
- **AND** the response body SHALL NOT contain the full-page `<html>` document wrapper or the base-layout header/footer (it is a fragment, not a full page)

#### Scenario: Plain GET returns the full page

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/actions-log?action_type=admin_login_success` with no `HX-Request` header
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the base-layout structural sections AND the `id="actions-log-table"` element
- **AND** the rendered table SHALL reflect the `action_type=admin_login_success` filter (a shared filtered link reproduces the filtered view)

### Requirement: All audit-row values render HTML-escaped, never raw

The system SHALL render every audit-row value HTML-escaped in the admin's browser — this includes the free-text `reason`, the client-controlled `user_agent` (which is fully attacker-influenced at write time), the `ip`, and the `before_state` / `after_state` JSONB (the latter two in a per-row on-demand detail region, not inline in the summary row). No audit-row value SHALL be emitted through any template mechanism that bypasses HTML escaping (e.g., a Pebble `raw` filter); the templates rely on Pebble's default-on autoescaping. A `NULL` `before_state` / `after_state` JSONB column SHALL render as a placeholder (em-dash), not the literal text produced by an unguarded null.

#### Scenario: HTML-bearing state payload is escaped, not executed

- **GIVEN** an authenticated session AND an `admin_actions_log` row whose `after_state` JSONB contains the substring `<script>alert(1)</script>`
- **WHEN** `GET /admin/actions-log` is served and the row's detail region is rendered
- **THEN** the response body SHALL contain the escaped form (e.g., `&lt;script&gt;`) and SHALL NOT contain a live, unescaped `<script>alert(1)</script>` tag

#### Scenario: Client-controlled user_agent with markup is escaped

- **GIVEN** an authenticated session AND an `admin_actions_log` row whose `user_agent` contains the substring `<img src=x onerror=alert(1)>`
- **WHEN** `GET /admin/actions-log` is served and the row is rendered in the summary table
- **THEN** the response body SHALL contain the escaped form (e.g., `&lt;img `) and SHALL NOT contain a live, unescaped `<img src=x onerror=alert(1)>` tag

#### Scenario: NULL state columns render as a placeholder

- **GIVEN** an authenticated session AND a row with `before_state IS NULL`
- **WHEN** the row's detail region is rendered
- **THEN** the rendered output for `before_state` SHALL be a placeholder (em-dash), not the literal string `null`

### Requirement: The capability adds only read routes; mutation methods are unmapped

The system SHALL expose only `GET` under the `/admin/actions-log` path. It SHALL NOT wire any `POST`, `PUT`, `PATCH`, or `DELETE` handler on that path. DB-level immutability of `admin_actions_log` (the `REVOKE UPDATE, DELETE … FROM admin_app` enforcement) is provisioned operationally per `docs/07-Operations.md` § Data Access Pattern and is OUT OF SCOPE for this capability — this requirement asserts only that the viewer introduces no mutation surface.

#### Scenario: POST on the actions-log path is not wired

- **GIVEN** an authenticated session (so the request passes the auth gate)
- **WHEN** the client sends `POST /admin/actions-log`
- **THEN** the response status SHALL be 405 Method Not Allowed (the route exists but only `GET` is wired; consistent with `admin-panel-scaffold`'s non-GET posture)

#### Scenario: Serving the viewer writes no audit row

- **GIVEN** an authenticated session AND a known count N of rows in `admin_actions_log`
- **WHEN** the client sends `GET /admin/actions-log` (one or more times)
- **THEN** the count of rows in `admin_actions_log` SHALL remain N (viewing the log is not itself an auditable action — no `admin_actions_log` insert occurs)

### Requirement: The viewer is accessible to every authenticated admin role

The system SHALL grant read access to `GET /admin/actions-log` to any admin with a valid session, regardless of `admin_users.role` (`owner`, `admin`, `moderator`, `read_only`). The viewer SHALL NOT reject a `read_only` admin. No role-based redaction of rows or columns SHALL be applied in this capability.

#### Scenario: read_only admin can view the audit log

- **GIVEN** an authenticated session for an admin whose `role = 'read_only'`
- **WHEN** the client sends `GET /admin/actions-log`
- **THEN** the response status SHALL be 200
- **AND** the audit-log table SHALL be rendered (no role-based rejection)

### Requirement: Empty result renders an empty state, not an error

The system SHALL render an explicit empty-state message when no `admin_actions_log` rows match the current filters (including the unfiltered case of an empty table), rather than an error or a blank page.

#### Scenario: No matching rows renders an empty-state message

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/actions-log?action_type=a_value_that_matches_no_rows` is served
- **THEN** the response status SHALL be 200
- **AND** the rendered body SHALL contain an empty-state indicator (e.g., a "no entries" message) rather than a table of rows or an error page

### Requirement: A composite (created_at DESC, id DESC) index serves the keyset queries

The change SHALL ship a Flyway migration (`V17`) creating a composite index `admin_actions_created_idx` on `admin_actions_log (created_at DESC, id DESC)`. This index serves both the `ORDER BY created_at DESC, id DESC` clause and the keyset row-value predicate `(created_at, id) < (?, ?)` for the no-filter (default newest-first) and date-only-filter query paths — the three V16 secondary indexes each lead with a different column (`admin_id` / `target_type` / `action_type`) and so cannot serve a bare newest-first scan without a full sort. The index SHALL be non-partial (no `WHERE` clause, hence no `NOW()`/volatile predicate, satisfying the partial-index invariant by construction) and SHALL be defined idempotently (`IF NOT EXISTS`) so it applies cleanly against the integration-test Postgres, a hand-pre-applied staging index, and production alike.

#### Scenario: Post-migration the index exists with the canonical definition

- **WHEN** the integration-test spec queries `pg_indexes WHERE schemaname = 'public' AND tablename = 'admin_actions_log' AND indexname = 'admin_actions_created_idx'`
- **THEN** exactly one row SHALL be returned
- **AND** its `indexdef` SHALL reference `created_at DESC` AND `id DESC` as the index key columns

#### Scenario: The index is non-partial (no WHERE predicate)

- **WHEN** the integration-test spec reads the `indexdef` for `admin_actions_created_idx`
- **THEN** the definition SHALL NOT contain a `WHERE` clause (the index covers the whole table; no volatile/`NOW()` predicate is present)
