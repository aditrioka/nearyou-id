## ADDED Requirements

### Requirement: Authenticated GET /admin/rejected-identifiers renders the rejected-identifiers table

The system SHALL serve `GET /admin/rejected-identifiers` as an authenticated route, wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block established by the `admin-login` capability so the session middleware gates it. On a valid session it SHALL return HTTP 200 with an HTML page that extends the shared admin base layout (per `admin-panel-scaffold`) and renders a table of `rejected_identifiers` rows ordered newest-first (`rejected_at DESC, id DESC`). Each rendered row SHALL display: `identifier_hash` (the stored one-way SHA-256 value, rendered as the hash), `identifier_type`, `reason`, and `rejected_at`. The route SHALL be read-only — it SHALL NOT write any `admin_actions_log` row, and it SHALL NOT mutate any table.

#### Scenario: Authenticated request renders the table with rejection rows

- **GIVEN** an authenticated admin session AND at least one row exists in `rejected_identifiers`
- **WHEN** the client sends `GET /admin/rejected-identifiers` carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL be Pebble-rendered HTML that contains the existing row's `identifier_hash` value AND its `reason` value
- **AND** the rendered HTML SHALL contain the base-layout structural sections (header, nav, footer)

#### Scenario: Unauthenticated request redirects to the login page

- **WHEN** a client sends `GET /admin/rejected-identifiers` with no `__Host-admin_session` cookie (or an invalid / expired / revoked / idle-timed-out session)
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** no rejected-identifiers table content SHALL be served

#### Scenario: Rows are ordered newest-first

- **GIVEN** an authenticated session AND three `rejected_identifiers` rows with strictly increasing `rejected_at` values
- **WHEN** `GET /admin/rejected-identifiers` is served
- **THEN** the row with the latest `rejected_at` SHALL appear before the others in the rendered table (newest-first `rejected_at DESC, id DESC` order)

### Requirement: Keyset pagination over (rejected_at, id) with a fixed page size

The system SHALL paginate the rejected-identifiers list using a keyset cursor over `(rejected_at, id)` in descending order with a fixed page size. It SHALL NOT use SQL `OFFSET` for pagination. When more rows exist beyond the current page, the system SHALL render an "older" navigation control carrying an opaque cursor encoding the last-displayed row's `(rejected_at, id)`; following that control SHALL return the next-older page whose first row immediately precedes the cursor in `rejected_at DESC, id DESC` order. A malformed or absent cursor SHALL be treated as a request for the first (newest) page, never an error.

#### Scenario: Page is capped at the fixed page size

- **GIVEN** an authenticated session AND more `rejected_identifiers` rows than the fixed page size
- **WHEN** `GET /admin/rejected-identifiers` is served with no cursor
- **THEN** the rendered table SHALL contain exactly the page-size number of rows (the newest page)
- **AND** an "older" pagination control SHALL be present

#### Scenario: Following the cursor returns the next-older, non-overlapping page

- **GIVEN** an authenticated session AND two full pages of rows
- **WHEN** the client follows the "older" control's cursor URL
- **THEN** the returned rows SHALL all be strictly older (in `rejected_at DESC, id DESC` order) than the last row of the first page
- **AND** no row from the first page SHALL reappear on the second page

#### Scenario: Malformed cursor falls back to the first page

- **WHEN** an authenticated client sends `GET /admin/rejected-identifiers?cursor=not-a-valid-cursor`
- **THEN** the response status SHALL be 200
- **AND** the rendered table SHALL show the newest page (the malformed cursor is ignored, not treated as an error)

#### Scenario: Last page omits the older control

- **GIVEN** an authenticated session AND exactly one page or fewer of matching rows
- **WHEN** `GET /admin/rejected-identifiers` is served
- **THEN** no "older" pagination control SHALL be rendered (there are no older rows)

#### Scenario: Exact page-size boundary omits the older control; one more row shows it

- **GIVEN** an authenticated session AND EXACTLY page-size matching rows
- **WHEN** `GET /admin/rejected-identifiers` is served with no cursor
- **THEN** all page-size rows SHALL be rendered AND no "older" control SHALL be present (there is no older row)
- **AND** WHEN one additional older row is then present (page-size + 1 total) and the same request is re-served, the newest page-size rows SHALL be rendered AND an "older" control SHALL be present (the fixed-page fencepost holds at the exact boundary)

#### Scenario: Rows sharing an identical rejected_at paginate by the id tiebreaker without loss or duplication

- **GIVEN** an authenticated session AND two or more rows with an IDENTICAL `rejected_at` value but distinct `id`s, positioned so the page boundary falls between them (`rejected_at` defaults to `NOW()`, so a signup burst can write colliding timestamps)
- **WHEN** the client pages through via the "older" cursor across that boundary
- **THEN** every such row SHALL appear exactly once across the pages (the `(rejected_at, id) < (?, ?)` row-value predicate's `id DESC` tiebreaker prevents both skipping and duplication at the boundary)

#### Scenario: The older-link cursor carries the active filters

- **GIVEN** an authenticated session AND more than one page of rows matching a `reason` filter
- **WHEN** the client follows the "older" control rendered for `GET /admin/rejected-identifiers?reason=age_under_18`
- **THEN** the followed URL SHALL retain `reason=age_under_18` alongside the `cursor` parameter
- **AND** the next page SHALL remain filtered to `reason=age_under_18` (filter + pagination compose; the paginated URL stays shareable)

### Requirement: Composable filtering

The system SHALL accept the query parameters `reason`, `identifier_type`, `from`, and `to`, each filtering the rejected-identifiers list and composing with logical AND. `reason` SHALL match exactly one of the allowed values (`age_under_18`, `attestation_persistent_fail`). `identifier_type` SHALL match exactly one of the allowed values (`google`, `apple`). `from` and `to` SHALL bound `rejected_at` (a `TIMESTAMPTZ`) interpreted in **UTC** — `from` inclusive from the start of that UTC day; `to` inclusive of the whole UTC day via an exclusive `< to + 1 day` (UTC) upper bound — matching the shipped `admin-actions-log-viewer` date-boundary convention (its route parses both via `atStartOfDay(ZoneOffset.UTC)`). All filter values SHALL be applied via parameterized query placeholders — never string-interpolated into SQL. (No `(rejected_at, id)` index is shipped — see `design.md` D2 — so "index-aligned" is intentionally NOT claimed for this capability; the filters are served by scan over the low-cardinality table.)

#### Scenario: Filtering by reason returns only matching rows

- **GIVEN** an authenticated session AND rows with `reason` values `age_under_18` and `attestation_persistent_fail`
- **WHEN** `GET /admin/rejected-identifiers?reason=age_under_18` is served
- **THEN** every rendered row SHALL have `reason = age_under_18`
- **AND** no `attestation_persistent_fail` row SHALL be rendered

#### Scenario: Filtering by identifier_type returns only matching rows

- **GIVEN** an authenticated session AND rows with `identifier_type` values `google` and `apple`
- **WHEN** `GET /admin/rejected-identifiers?identifier_type=apple` is served
- **THEN** every rendered row SHALL have `identifier_type = apple`

#### Scenario: Filters compose with AND

- **GIVEN** an authenticated session AND rows spanning multiple `reason` + `identifier_type` combinations
- **WHEN** `GET /admin/rejected-identifiers?reason=age_under_18&identifier_type=google` is served
- **THEN** every rendered row SHALL satisfy BOTH `reason = age_under_18` AND `identifier_type = google`

#### Scenario: Date range bounds rejected_at in UTC with inclusive whole-day upper bound

- **GIVEN** an authenticated session AND rows whose `rejected_at` fall on `2026-05-20`, `2026-05-25`, and `2026-05-30` (test fixtures pinned with EXPLICIT UTC offsets — never CI-host-local time — so the boundary is deterministic across runners)
- **WHEN** `GET /admin/rejected-identifiers?from=2026-05-25&to=2026-05-25` is served
- **THEN** the row(s) within the whole UTC day `[2026-05-25T00:00:00Z, 2026-05-26T00:00:00Z)` SHALL be rendered
- **AND** the `2026-05-20` and `2026-05-30` rows SHALL NOT be rendered

#### Scenario: A row near the UTC day boundary is bucketed by its UTC date

- **GIVEN** an authenticated session AND a row whose `rejected_at` is `2026-05-25T23:30:00Z`
- **WHEN** `GET /admin/rejected-identifiers?from=2026-05-25&to=2026-05-25` is served
- **THEN** that row SHALL be rendered (it falls within the UTC day 2026-05-25 regardless of the server's local timezone — a +07:00 WIB interpretation would wrongly push it to the 26th, which this convention does not do)

### Requirement: Malformed filter inputs are handled safely without error or injection

The system SHALL tolerate malformed filter inputs without returning a 500 and without executing attacker-controlled SQL. An unrecognized `reason` / `identifier_type` value (not in the allowed set), an unparseable `from` / `to` date, or an over-long filter value SHALL cause that single filter to be ignored (lenient parse) while the remaining valid filters still apply. Because all values are bound as query parameters, a value containing SQL metacharacters SHALL be treated as a literal filter value, never as SQL.

#### Scenario: Unrecognized enum value is ignored, other filters still apply

- **WHEN** an authenticated client sends `GET /admin/rejected-identifiers?reason=not-a-real-reason&identifier_type=google`
- **THEN** the response status SHALL be 200
- **AND** the rendered rows SHALL be filtered by `identifier_type = google` (the invalid `reason` is ignored)

#### Scenario: SQL-metacharacter filter value is treated as a literal

- **WHEN** an authenticated client sends `GET /admin/rejected-identifiers?reason=%27%3B+DROP+TABLE+rejected_identifiers%3B--` (URL-encoded `'; DROP TABLE rejected_identifiers;--`)
- **THEN** the response status SHALL be 200
- **AND** the `rejected_identifiers` table SHALL still exist and be queryable afterward (the value matched no allowed enum and was ignored; no SQL was executed from it)

#### Scenario: Unparseable date is ignored

- **WHEN** an authenticated client sends `GET /admin/rejected-identifiers?from=13th-of-never`
- **THEN** the response status SHALL be 200
- **AND** the result SHALL be unfiltered by date (the invalid `from` is ignored)

#### Scenario: Over-long filter value is bounded, not errored

- **WHEN** an authenticated client sends `GET /admin/rejected-identifiers?reason=<a string far longer than the 32-char column width>`
- **THEN** the response status SHALL be 200 (the over-long value is length-bounded during lenient parse and, matching no allowed enum, is ignored — rather than causing a 400/500)

### Requirement: Per-reason and per-type count summary reflects the active filter scope

The system SHALL render a count summary alongside the table that reports, for the **current filter scope**, the number of matching rows broken down by `reason` AND by `identifier_type`. The summary exists to surface rejection-volume spikes (the "`rejected_identifiers` insert rate" / "age gate rejection rate" anomaly signals) at a glance. The counts SHALL reflect the applied filters (a summary of the filtered result set, not of the whole table when filters are present) but SHALL NOT be limited by pagination — they count the ENTIRE filtered result set (ignoring the keyset cursor and page size), not just the rows on the current page.

#### Scenario: Summary reflects both reasons when unfiltered

- **GIVEN** an authenticated session AND rows of both `reason = age_under_18` and `reason = attestation_persistent_fail`
- **WHEN** `GET /admin/rejected-identifiers` is served with no filters
- **THEN** the rendered summary SHALL show a non-zero count for `age_under_18` AND a non-zero count for `attestation_persistent_fail`

#### Scenario: Summary narrows to the filtered scope

- **GIVEN** an authenticated session AND rows of both reasons
- **WHEN** `GET /admin/rejected-identifiers?reason=age_under_18` is served
- **THEN** the rendered summary SHALL report the `age_under_18` count (matching the filtered rows) AND SHALL NOT report a non-zero `attestation_persistent_fail` count (that bucket is either shown as zero or omitted) — the active filter scopes the summary to `age_under_18`

#### Scenario: Summary breaks down by identifier_type

- **GIVEN** an authenticated session AND rows with `identifier_type` values `google` and `apple`
- **WHEN** `GET /admin/rejected-identifiers` is served with no filters
- **THEN** the rendered summary SHALL show a `google` count AND an `apple` count reflecting the respective row totals

#### Scenario: Summary counts the whole filtered set, not just the current page

- **GIVEN** an authenticated session AND more matching rows than the fixed page size
- **WHEN** `GET /admin/rejected-identifiers` is served (the newest page)
- **THEN** the summary's total count SHALL equal the full number of matching rows (a value greater than the page size), NOT the page-size number of rows currently displayed

### Requirement: HTMX partial swap with plain-GET progressive enhancement

The system SHALL serve the rejected-identifiers table as an HTMX-swappable fragment AND as a full standalone page from the same route, branching on the `HX-Request` header. When the request carries `HX-Request: true`, the system SHALL respond with only the table fragment (the swappable `#rejected-identifiers-table` element) so the filter form and surrounding layout remain in place. When the request does NOT carry `HX-Request`, the system SHALL respond with the full page (which includes the same table fragment), so filtering and pagination work without JavaScript. The filtered/paginated URL SHALL remain shareable (a plain `GET` to a filtered URL SHALL reproduce the same filtered view).

#### Scenario: HTMX request returns only the table fragment

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/rejected-identifiers?reason=age_under_18` with header `HX-Request: true`
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the `id="rejected-identifiers-table"` element
- **AND** the response body SHALL NOT contain the full-page `<html>` document wrapper or the base-layout header/footer (it is a fragment, not a full page)

#### Scenario: Plain GET returns the full page

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/rejected-identifiers?reason=age_under_18` with no `HX-Request` header
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the base-layout structural sections AND the `id="rejected-identifiers-table"` element
- **AND** the rendered table SHALL reflect the `reason=age_under_18` filter (a shared filtered link reproduces the filtered view)

### Requirement: All rendered values are HTML-escaped, never raw

The system SHALL render every rejected-identifiers value HTML-escaped in the admin's browser. No row value SHALL be emitted through any template mechanism that bypasses HTML escaping (e.g., a Pebble `raw` filter); the templates rely on Pebble's default-on autoescaping. Although `rejected_identifiers` carries no free-text or client-controlled column (`identifier_hash` is hex, `identifier_type` and `reason` are CHECK-constrained enums, `rejected_at` is a timestamp) — a materially smaller XSS surface than the audit log's `user_agent` / JSONB — escaping SHALL still be applied as defense-in-depth.

#### Scenario: A value containing markup is escaped, not executed

- **GIVEN** an authenticated session AND a `rejected_identifiers` row whose `identifier_hash` column has been set (e.g., via a test fixture) to the literal string `<script>alert(1)</script>`
- **WHEN** `GET /admin/rejected-identifiers` is served and the row is rendered
- **THEN** the response body SHALL contain the escaped form (e.g., `&lt;script&gt;`) and SHALL NOT contain a live, unescaped `<script>alert(1)</script>` tag

### Requirement: Hash-only PII discipline — no raw-identifier resolution

The system SHALL display only the stored one-way `identifier_hash`; it SHALL NOT attempt to resolve, look up, or surface any raw identifier (email, Google `sub`, Apple `sub`) for a row — no such value is stored, by design ([`docs/06-Security-Privacy.md`](../../../docs/06-Security-Privacy.md): only the hashed identifier is retained). The view SHALL NOT cross-link a row to a `users` record (a rejected identifier has no `users` row by design).

#### Scenario: Rendered row exposes the hash, not a raw identifier

- **GIVEN** an authenticated session AND a `rejected_identifiers` row
- **WHEN** `GET /admin/rejected-identifiers` is served
- **THEN** the rendered row SHALL contain the `identifier_hash` value
- **AND** the rendered row SHALL NOT contain a resolved email address or a link to a `users` record (there is no stored raw identifier and no associated user row to resolve)

### Requirement: The capability adds only read routes; mutation methods are unmapped

The system SHALL expose only `GET` under the `/admin/rejected-identifiers` path. It SHALL NOT wire any `POST`, `PUT`, `PATCH`, or `DELETE` handler on that path. The viewer SHALL introduce no mutation surface over `rejected_identifiers` or any other table.

#### Scenario: POST on the rejected-identifiers path is not wired

- **GIVEN** an authenticated session (so the request passes the auth gate)
- **WHEN** the client sends `POST /admin/rejected-identifiers`
- **THEN** the response status SHALL be 405 Method Not Allowed (the route exists but only `GET` is wired; consistent with `admin-panel-scaffold`'s non-GET posture)

#### Scenario: Serving the viewer writes no audit row and mutates nothing

- **GIVEN** an authenticated session AND a known count N of rows in `rejected_identifiers` AND a known count M of rows in `admin_actions_log`
- **WHEN** the client sends `GET /admin/rejected-identifiers` (one or more times)
- **THEN** the count of rows in `rejected_identifiers` SHALL remain N
- **AND** the count of rows in `admin_actions_log` SHALL remain M (viewing the list is not itself an auditable action — no insert occurs)

### Requirement: The manual support-clear action is deferred to a fast-follow change

This change SHALL NOT ship the manual support-clear action (the admin removal of a `rejected_identifiers` row to let a falsely-rejected legitimate adult re-verify, per the "purgeable via legitimate adult re-verification workflow" path in [`docs/05-Implementation.md`](../../../docs/05-Implementation.md)). That write action — which MUST be role-gated, CSRF-gated, audit-logged (`admin_actions_log`, e.g. action type `rejected_identifier_cleared`), and rate-limited — is deferred to the fast-follow change `admin-rejected-identifiers-clear-action`. Until that change ships, clearing a rejected identifier remains the existing out-of-band operational path. This requirement exists so the fast-follow has a concrete requirement to MODIFY rather than inventing scope.

#### Scenario: No clear / remove control is wired in this change

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/rejected-identifiers` is served
- **THEN** the rendered page SHALL NOT contain a clear / remove / delete control for any row
- **AND** no route under `/admin/rejected-identifiers` SHALL accept a mutation request that removes a row (consistent with the read-only / mutation-unmapped requirement)

### Requirement: The viewer is accessible to every authenticated admin role

The system SHALL grant read access to `GET /admin/rejected-identifiers` to any admin with a valid session, regardless of `admin_users.role` (`owner`, `admin`, `moderator`, `read_only`). The viewer SHALL NOT reject a `read_only` admin. No role-based redaction of rows or columns SHALL be applied in this capability. (The deferred clear action will be role-gated separately; the read view is not.)

#### Scenario: read_only admin can view the rejected-identifiers list

- **GIVEN** an authenticated session for an admin whose `role = 'read_only'`
- **WHEN** the client sends `GET /admin/rejected-identifiers`
- **THEN** the response status SHALL be 200
- **AND** the rejected-identifiers table SHALL be rendered (no role-based rejection)

### Requirement: Empty result renders an empty state, not an error

The system SHALL render an explicit empty-state message when no `rejected_identifiers` rows match the current filters (including the unfiltered case of an empty table), rather than an error or a blank page.

#### Scenario: No matching rows renders an empty-state message

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/rejected-identifiers?from=2999-01-01` is served (a date range that matches no rows)
- **THEN** the response status SHALL be 200
- **AND** the rendered body SHALL contain an empty-state indicator (e.g., a "no entries" message) rather than a table of rows or an error page

#### Scenario: Empty state also renders inside the HTMX fragment

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/rejected-identifiers?from=2999-01-01` is served with header `HX-Request: true`
- **THEN** the response status SHALL be 200
- **AND** the returned `#rejected-identifiers-table` fragment SHALL contain the empty-state indicator (not a blank/empty fragment or an error)
