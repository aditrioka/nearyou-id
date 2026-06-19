# admin-rejected-identifiers-viewer Specification

## Purpose

Provide moderators a read-only admin surface (`GET /admin/rejected-identifiers`) over the `rejected_identifiers` anti-abuse blocklist — the hashed Google/Apple identifiers the age gate writes on under-18 rejection (`reason = age_under_18`) and after persistent attestation failures (`reason = attestation_persistent_fail`). The viewer renders a newest-first, keyset-paginated table with composable `reason` / `identifier_type` / UTC date-range filters and a per-reason/per-type count summary, so a moderator can watch the "`rejected_identifiers` insert rate" / "age gate rejection rate" anomaly signals and spot a brute-force-signup spike at a glance. It enforces hash-only PII discipline (it surfaces only the one-way `identifier_hash`, never a resolved raw identifier, and never cross-links a row to a `users` record). The read listing is strictly read-only (it writes no `admin_actions_log` row and wires no mutation handler); the manual support-clear write action — removing a row so a falsely-rejected legitimate adult can re-verify — is provided by `POST /admin/rejected-identifiers/{id}/clear` (owner/admin-only, CSRF-gated, reason-required, audit-logged, dedicated 10/hr per-admin cap), added by the `admin-rejected-identifiers-clear-action` change.
## Requirements
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

### Requirement: The manual support-clear action is implemented (owner/admin)

The manual support-clear action — the admin removal of a `rejected_identifiers` row so a falsely-rejected legitimate adult can re-verify (the "purgeable via legitimate adult re-verification workflow" path in [`docs/05-Implementation.md`](../../../docs/05-Implementation.md)) — is **no longer deferred**. The system SHALL provide it in this change as `POST /admin/rejected-identifiers/{id}/clear`, governed by the clear-action requirements ADDED below (endpoint + audit, CSRF + owner/admin role gating, required reason, idempotent not-found, dedicated rate-limit, atomic delete+audit, and the per-row HTMX control). The action SHALL be **role-gated to owner/admin, CSRF-gated, and audit-logged** (one immutable `admin_actions_log` row, `action_type = 'rejected_identifier_cleared'`, the cleared row preserved in `before_state`) and **rate-limited** per admin. It replaces the prior out-of-band raw-SQL clear path. This requirement supersedes the prior deferral; tracking issue [#190](https://github.com/aditrioka/nearyou-id/issues/190) is resolved by this change.

#### Scenario: The clear action is wired (no longer deferred)

- **GIVEN** an authenticated owner/admin session
- **WHEN** the admin opens `GET /admin/rejected-identifiers` AND a `rejected_identifiers` row exists
- **THEN** the rendered page SHALL present a per-row clear control (inverting the prior "no clear / remove control is wired") AND a `POST /admin/rejected-identifiers/{id}/clear` route SHALL be wired to remove that row (per the clear-action requirements added by this change)

### Requirement: The collection path is read-only; mutation is confined to the clear sub-route

The bare collection path `/admin/rejected-identifiers` SHALL expose only `GET` — it SHALL NOT wire any `POST`, `PUT`, `PATCH`, or `DELETE` handler, and serving it (with any filter or cursor parameters) SHALL write no `admin_actions_log` row and mutate no table. All mutation on this capability SHALL be confined to the dedicated clear sub-route `POST /admin/rejected-identifiers/{id}/clear` introduced by this change; the listing itself remains strictly read-only.

#### Scenario: POST on the bare collection path is not wired

- **GIVEN** an authenticated session (so the request passes the auth gate)
- **WHEN** the client sends `POST /admin/rejected-identifiers` (the bare collection path, not a `/{id}/clear` sub-route)
- **THEN** the response status SHALL be 405 Method Not Allowed (only `GET` is wired on the collection path; mutation is served by the `/{id}/clear` sub-route)

#### Scenario: Serving the listing writes no audit row and mutates nothing

- **GIVEN** an authenticated session AND a known count N of rows in `rejected_identifiers` AND a known count M of rows in `admin_actions_log`
- **WHEN** the client sends `GET /admin/rejected-identifiers` (one or more times, with and without filters/cursor)
- **THEN** the count of rows in `rejected_identifiers` SHALL remain N
- **AND** the count of rows in `admin_actions_log` SHALL remain M (viewing the list is not itself an auditable action)

### Requirement: Authenticated clear endpoint removes the row and writes one audit row

The system SHALL serve `POST /admin/rejected-identifiers/{id}/clear` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block. On a valid session with a valid CSRF token, an owner/admin role, and a non-blank reason, it SHALL, in ONE database transaction, hard-`DELETE` the `rejected_identifiers` row identified by `{id}` and write exactly one `admin_actions_log` row with `action_type = 'rejected_identifier_cleared'`, `target_type = 'rejected_identifier'`, `target_id = {id}`, the acting admin's id, the admin-supplied `reason`, and a `before_state` JSONB capturing the cleared row's `identifier_hash`, `identifier_type`, `reason`, and `rejected_at` (`after_state` is null — the row is gone). On success the `rejected_identifiers` count SHALL drop by exactly one and exactly one new `admin_actions_log` row SHALL be written.

#### Scenario: A clear removes the row and writes one audit row with before_state

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND a `rejected_identifiers` row with id `R` (`identifier_hash = H`, `reason = age_under_18`)
- **WHEN** the client sends `POST /admin/rejected-identifiers/R/clear` with a non-blank `reason`
- **THEN** the `rejected_identifiers` row `R` SHALL no longer exist
- **AND** exactly one `admin_actions_log` row SHALL be written with `action_type = 'rejected_identifier_cleared'`, `target_type = 'rejected_identifier'`, `target_id = R`, the acting admin's id, and the supplied reason
- **AND** that row's `before_state` SHALL capture all four cleared-row fields (`identifier_hash = H`, `identifier_type`, `reason = age_under_18`, `rejected_at`) AND its `after_state` SHALL be null

#### Scenario: A cleared identifier can be re-rejected on a later signup attempt

- **GIVEN** a `rejected_identifiers` row for `(identifier_hash = H, identifier_type = google)` that has just been cleared
- **WHEN** the age gate later writes a fresh rejection for the same `(H, google)` (e.g. the same still-under-18 identity retries)
- **THEN** the insert SHALL succeed (the `UNIQUE (identifier_hash, identifier_type)` no longer conflicts — the prior row is gone) AND the row SHALL reappear in the viewer (the clear is not a permanent allowlist)

### Requirement: The clear action is session-, CSRF-, and owner/admin-role-gated, in order

The clear endpoint SHALL enforce, in order, the `admin-login` session gate, then CSRF validation, then the role gate. An unauthenticated (or expired / revoked / idle-timed-out) request SHALL redirect 302 to `/admin/login` and write nothing. A request whose `X-CSRF-Token` is missing or does not match `admin_sessions.csrf_token_hash` SHALL return 403, emit an `admin_csrf_violation` audit entry, and perform no clear. An authenticated admin whose role is NOT `owner` or `admin` (this explicitly INCLUDES `moderator` and `read_only`) SHALL be rejected with no mutation and no `rejected_identifier_cleared` audit row. CSRF SHALL be validated BEFORE the role gate. (The role gate for this write is intentionally stricter than the any-admin-role READ view — clearing weakens an anti-abuse control, so it matches the owner/admin tier of permanent-ban / chat-redaction.)

#### Scenario: Unauthenticated clear request redirects and writes nothing

- **WHEN** a client sends `POST /admin/rejected-identifiers/{id}/clear` with no valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 302 with `Location: /admin/login`
- **AND** no `rejected_identifiers` row SHALL be deleted AND no `admin_actions_log` row SHALL be written

#### Scenario: Missing or invalid CSRF token is rejected and audited

- **GIVEN** an authenticated owner/admin session
- **WHEN** the client sends a clear `POST` without a valid CSRF token
- **THEN** the response status SHALL be 403 AND an `admin_csrf_violation` audit entry SHALL be recorded AND no `rejected_identifiers` row SHALL be deleted

#### Scenario: CSRF is validated before the role gate

- **GIVEN** an authenticated `read_only` admin
- **WHEN** the client sends a clear `POST` WITHOUT a valid CSRF token
- **THEN** the rejection SHALL be the CSRF rejection (403 + `admin_csrf_violation`), demonstrating CSRF is evaluated before the role gate, AND no write SHALL occur

#### Scenario: A moderator is rejected by the role gate

- **GIVEN** an authenticated `moderator`-role admin with a valid CSRF token AND an existing `rejected_identifiers` row
- **WHEN** the client sends `POST /admin/rejected-identifiers/{id}/clear` with a non-blank reason
- **THEN** the request SHALL be rejected (the clear is owner/admin-only) AND the row SHALL still exist AND no `rejected_identifier_cleared` audit row SHALL be written

#### Scenario: A read_only admin is rejected by the role gate

- **GIVEN** an authenticated `read_only` admin with a valid CSRF token AND an existing `rejected_identifiers` row
- **WHEN** the client sends the clear `POST` with a non-blank reason
- **THEN** the request SHALL be rejected AND no `rejected_identifiers` row SHALL be deleted

### Requirement: A clear requires a non-blank, length-bounded reason

The clear endpoint SHALL require a `reason` form field that is non-blank (not empty / not whitespace-only) and within a bounded length. A blank/whitespace-only reason OR an over-length reason SHALL be rejected with NO delete and NO audit row (validated server-side before any DB write), surfaced as an inline validation message rather than a 5xx.

#### Scenario: Blank reason is rejected with no write

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND an existing `rejected_identifiers` row
- **WHEN** the client sends the clear `POST` with an empty or whitespace-only `reason`
- **THEN** the request SHALL be rejected with an inline validation message (not a 5xx) AND the row SHALL still exist AND no `admin_actions_log` row SHALL be written

#### Scenario: Over-length reason is rejected with no write

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND an existing `rejected_identifiers` row
- **WHEN** the client sends the clear `POST` with a `reason` longer than the bounded length
- **THEN** the request SHALL be rejected with no delete and no `admin_actions_log` row (not a 5xx)

### Requirement: Clearing a nonexistent or already-cleared identifier is a safe no-op

The clear endpoint SHALL tolerate bad / stale ids without a 5xx and SHALL be idempotent. A malformed path `{id}` (not a UUID) SHALL return 400 with no write. Clearing an `{id}` that does not exist (never existed, or was already cleared by a prior request or a concurrent admin) SHALL perform no delete, write no `admin_actions_log` row, and return a graceful "already removed / not found" inline state rather than a 5xx — serializing the two-admins-clear-the-same-row race so the loser is a harmless no-op.

#### Scenario: Malformed id yields 400 with no write

- **GIVEN** an authenticated owner/admin session with a valid CSRF token
- **WHEN** the client sends `POST /admin/rejected-identifiers/not-a-uuid/clear` with a non-blank reason
- **THEN** the response status SHALL be 400 AND no `rejected_identifiers` / `admin_actions_log` row SHALL be written

#### Scenario: Clearing a nonexistent id is a graceful no-op

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND an `{id}` that matches no `rejected_identifiers` row
- **WHEN** the client sends the clear `POST` with a non-blank reason AND a known count M of `admin_actions_log` rows
- **THEN** no row SHALL be deleted AND the `admin_actions_log` count SHALL remain M AND the response SHALL be a graceful "already removed / not found" state (not a 5xx)

### Requirement: The clear action is rate-limited per admin at 10 per trailing hour

The system SHALL enforce a dedicated cap of **10 clears per acting admin per trailing one-hour window**, counted from the immutable `admin_actions_log` audit trail (the audit trail is the rate-limit ledger — no second source of truth): rows for the acting `admin_id` with `action_type = 'rejected_identifier_cleared'` and `created_at > NOW() - INTERVAL '1 hour'`. The count SHALL be read inside the same JDBC transaction as the gated clear (a soft abuse-prevention cap with ±1 concurrency tolerance, NOT a hard authorization boundary — it SHALL NOT take a `FOR UPDATE` lock on the ledger). A clear attempt at or over the cap SHALL be rejected with NO delete and NO audit row, surfaced as an inline "quota exceeded" state (never a 5xx). A clear under the cap SHALL proceed normally and, by writing its own audit row, advance the count by one. The cap SHALL be per-admin — one admin reaching the cap SHALL NOT block a different admin. (This is a dedicated cap, NOT the shared `admin-destructive-action-rate-limit`, whose set is user-punitive actions only — see `design.md` D1.)

#### Scenario: A clear under the cap proceeds and advances the count

- **GIVEN** an authenticated owner/admin with 9 `rejected_identifier_cleared` rows in the trailing hour AND an existing `rejected_identifiers` row
- **WHEN** that admin performs a clear with a valid CSRF token and non-blank reason
- **THEN** the clear SHALL apply AND exactly one new `rejected_identifier_cleared` audit row SHALL be written (bringing the trailing-hour count to 10)

#### Scenario: A clear at the cap is rejected without effect

- **GIVEN** an authenticated owner/admin with exactly 10 `rejected_identifier_cleared` rows in the trailing hour AND an existing `rejected_identifiers` row
- **WHEN** that admin attempts an 11th clear with a valid CSRF token and non-blank reason
- **THEN** the response SHALL surface a "quota exceeded" state (not a 5xx) AND the row SHALL still exist AND no new `admin_actions_log` row SHALL be written (the count stays 10)

#### Scenario: The cap counts only in-window clear actions for the acting admin

- **GIVEN** an authenticated owner/admin with, in the last hour, 2 `rejected_identifier_cleared` rows AND 5 `user_suspended` rows, plus 9 `rejected_identifier_cleared` rows OLDER than one hour
- **WHEN** that admin's clear-count is computed
- **THEN** the count SHALL be 2 (only in-window `rejected_identifier_cleared` rows count — other action types and out-of-window rows are excluded)

#### Scenario: The cap is per-admin

- **GIVEN** admin A with 10 `rejected_identifier_cleared` rows in the trailing hour AND admin B with 0
- **WHEN** admin B (owner/admin) performs a clear with a valid CSRF token and non-blank reason
- **THEN** admin B's clear SHALL apply normally (admin A's exhausted quota does not block admin B)

### Requirement: Clear, audit, and rate-check are atomic

For every clear, the `rejected_identifiers` `DELETE`, the `admin_actions_log` insert, and the in-transaction rate-count read SHALL commit or roll back together in one transaction. There SHALL be no observable partial state — no delete without its audit row, and no audit row without the delete.

#### Scenario: An audit-write failure rolls back the delete

- **GIVEN** an authenticated owner/admin session, an existing `rejected_identifiers` row, AND the `admin_actions_log` insert is made to fail (fault injection, as in the `admin-user-moderation` rollback tests)
- **WHEN** the client sends a valid clear `POST`
- **THEN** the transaction SHALL roll back: the `rejected_identifiers` row SHALL still exist AND no `admin_actions_log` row SHALL be written

### Requirement: The per-row clear control renders escaped, HTMX-partial with a no-JS fallback, for owner/admin only

The rejected-identifiers table SHALL render a per-row clear control — a form carrying the session CSRF token plus a required reason input that posts to `POST /admin/rejected-identifiers/{id}/clear` — ONLY for sessions whose role is `owner` or `admin`. A `moderator` or `read_only` session SHALL see NO clear control (the read view still renders for them, per the unchanged any-role read requirement). Because the clear takes effect immediately and is destructive (the row is hard-deleted), the control SHALL make its destructive nature clear (a confirm affordance / explicit label). Every dynamic value rendered into the control SHALL be HTML-escaped. An `HX-Request: true` clear SHALL return the swappable table fragment with the cleared row removed; a successful no-JS (plain `POST`) clear SHALL 303-redirect back to the (filter-preserving) `/admin/rejected-identifiers` listing.

#### Scenario: A clear control is rendered for an owner/admin session

- **GIVEN** an authenticated `owner`-role (or `admin`-role) session AND a `rejected_identifiers` row
- **WHEN** `GET /admin/rejected-identifiers` is served
- **THEN** that row SHALL render a clear control (with a CSRF hidden field and a reason input) posting to `POST /admin/rejected-identifiers/{id}/clear`

#### Scenario: No clear control is rendered for a read_only or moderator session

- **GIVEN** an authenticated session whose role is `read_only` (or `moderator`) AND a `rejected_identifiers` row
- **WHEN** `GET /admin/rejected-identifiers` is served
- **THEN** the response status SHALL be 200 (the read view still renders) AND no clear / remove control SHALL be present for any row

#### Scenario: A successful HTMX clear removes the row from the rendered fragment

- **GIVEN** an authenticated owner/admin session submitting a clear with header `HX-Request: true` and a valid CSRF token and non-blank reason
- **WHEN** the clear succeeds
- **THEN** the response SHALL be the table fragment (or row-swap) from which the cleared row is absent

#### Scenario: A successful no-JS clear redirects back to the listing

- **GIVEN** an authenticated owner/admin submitting a plain `POST` (no `HX-Request` header) clear with a valid CSRF token and non-blank reason
- **WHEN** the clear succeeds
- **THEN** the response SHALL be a 303 redirect back to `/admin/rejected-identifiers` (preserving any active filters)

#### Scenario: Rendered clear-control values are HTML-escaped

- **GIVEN** an authenticated owner/admin session AND a `rejected_identifiers` row whose `identifier_hash` has been set (via a test fixture) to the literal string `<script>alert(1)</script>`
- **WHEN** `GET /admin/rejected-identifiers` is served with the clear controls
- **THEN** the response body SHALL contain the escaped form (e.g. `&lt;script&gt;`) and SHALL NOT contain a live, unescaped `<script>alert(1)</script>` element

