## ADDED Requirements

### Requirement: Authenticated GET /admin/privacy-flips renders the privacy-flip monitor table

The system SHALL serve `GET /admin/privacy-flips` as an authenticated route, wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block established by the `admin-login` capability so the session middleware gates it. On a valid session it SHALL return HTTP 200 with an HTML page that extends the shared admin base layout (per `admin-panel-scaffold`) and renders a table of the `users` rows where `privacy_flip_scheduled_at IS NOT NULL` (every user with a pending privacy flip, in-window OR overdue). Each rendered row SHALL display: the user's `username`, `display_name`, current privacy state (`private_profile_opt_in`), the scheduled flip timestamp (`privacy_flip_scheduled_at`), and a derived IN_WINDOW / OVERDUE status. The route SHALL be read-only — it SHALL NOT write any `admin_actions_log` row, SHALL NOT mutate any `users` row, and SHALL NOT clear or modify `privacy_flip_scheduled_at`.

#### Scenario: Authenticated request renders the monitor with pending-flip rows

- **GIVEN** an authenticated admin session AND at least one `users` row with a non-NULL `privacy_flip_scheduled_at`
- **WHEN** the client sends `GET /admin/privacy-flips` carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL be Pebble-rendered HTML that contains that user's `username` AND its derived status
- **AND** the rendered HTML SHALL contain the base-layout structural sections (header, nav, footer)

#### Scenario: Unauthenticated request redirects to the login page

- **WHEN** a client sends `GET /admin/privacy-flips` with no `__Host-admin_session` cookie (or an invalid / expired / revoked / idle-timed-out session)
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** no privacy-flip table content SHALL be served

#### Scenario: A user with no pending flip is excluded

- **GIVEN** an authenticated session AND a `users` row whose `privacy_flip_scheduled_at IS NULL`
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** that user SHALL NOT appear in the rendered table (the monitor lists only users with a pending flip)

#### Scenario: A soft-deleted user with a lingering scheduled flip is still surfaced

- **GIVEN** an authenticated session AND a `users` row whose `deleted_at IS NOT NULL` AND whose `privacy_flip_scheduled_at IS NOT NULL`
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** that row SHALL appear in the rendered table (a soft-deleted user carrying a pending flip is itself a stuck-row signal; the query filters only on `privacy_flip_scheduled_at IS NOT NULL`, never on `deleted_at`)

### Requirement: Per-row IN_WINDOW / OVERDUE classification

The system SHALL classify every rendered row against a **single request-scoped evaluation instant** (one consistent point in time computed once per request): a row whose `privacy_flip_scheduled_at` is strictly after the evaluation instant SHALL be classified **IN_WINDOW** (mid-grace; the canonical 72h-downgrade-window population per `docs/07-Operations.md`), and a row whose `privacy_flip_scheduled_at` is at-or-before the evaluation instant SHALL be classified **OVERDUE** (past its deadline but not yet cleared by the worker — a stuck-row / webhook-handler-bug signal). The boundary is **closed on the OVERDUE side**: a row whose `privacy_flip_scheduled_at` equals the evaluation instant exactly SHALL be OVERDUE (the IN_WINDOW test is strict `>`). The OVERDUE bucket is INTENTIONALLY outside the canonical in-window predicate; the monitor surfaces it as the documented stuck-row extension. The classification AND the `status` filter SHALL be evaluated against the SAME single evaluation instant within one request, so the row's rendered class and the `status`-filtered result set cannot disagree (no read-vs-filter skew, even at the boundary instant). An IN_WINDOW row SHALL render the time remaining until the flip; an OVERDUE row SHALL render how long it has been overdue.

#### Scenario: A future-dated flip is classified IN_WINDOW

- **GIVEN** an authenticated session AND a row whose `privacy_flip_scheduled_at` is in the future
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** that row SHALL be rendered with the IN_WINDOW status (and a time-remaining indication)

#### Scenario: A past-dated, uncleared flip is classified OVERDUE

- **GIVEN** an authenticated session AND a row whose `privacy_flip_scheduled_at` is in the past (the worker has not cleared it)
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** that row SHALL be rendered with the OVERDUE status (and an overdue-by indication) — the stuck-row signal is visible, not hidden by the canonical in-window predicate

#### Scenario: Both buckets appear together when unfiltered

- **GIVEN** an authenticated session AND at least one IN_WINDOW row AND at least one OVERDUE row
- **WHEN** `GET /admin/privacy-flips` is served with no `status` filter
- **THEN** the rendered table SHALL contain BOTH the IN_WINDOW row(s) AND the OVERDUE row(s), each carrying its respective classification

#### Scenario: A flip scheduled exactly at the evaluation instant is classified OVERDUE (boundary fencepost)

- **GIVEN** an authenticated session AND a deterministic evaluation instant (e.g. a fixed injected clock) AND a row whose `privacy_flip_scheduled_at` equals that evaluation instant exactly
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** that row SHALL be classified OVERDUE (the IN_WINDOW test is strict `>`; equality falls to the at-or-before OVERDUE branch)

#### Scenario: The boundary row classifies and filters identically (no read-vs-filter skew)

- **GIVEN** an authenticated session AND a deterministic evaluation instant AND a row whose `privacy_flip_scheduled_at` equals that instant exactly (classified OVERDUE per the boundary rule)
- **WHEN** `GET /admin/privacy-flips?status=overdue` is served AND, separately, `GET /admin/privacy-flips?status=in_window` is served
- **THEN** the boundary row SHALL appear under `status=overdue` AND SHALL NOT appear under `status=in_window` — the same evaluation instant drives both the rendered class and the `status` predicate, so a row can never be OVERDUE in the table yet excluded by `status=overdue` (or vice versa)

### Requirement: Ascending keyset pagination over (privacy_flip_scheduled_at, id)

The system SHALL paginate the list using a keyset cursor over `(privacy_flip_scheduled_at, id)` in ASCENDING order with a fixed page size, so the most-overdue / soonest-to-flip rows surface first. It SHALL NOT use SQL `OFFSET` for pagination. When more rows exist beyond the current page, the system SHALL render a "next" navigation control carrying an opaque cursor encoding the last-displayed row's `(privacy_flip_scheduled_at, id)`; following that control SHALL return the next page whose first row immediately follows the cursor in `privacy_flip_scheduled_at ASC, id ASC` order via the row-value predicate `(privacy_flip_scheduled_at, id) > (?, ?)`. A malformed or absent cursor SHALL be treated as a request for the first page, never an error.

#### Scenario: Page is capped at the fixed page size

- **GIVEN** an authenticated session AND more pending-flip rows than the fixed page size
- **WHEN** `GET /admin/privacy-flips` is served with no cursor
- **THEN** the rendered table SHALL contain exactly the page-size number of rows (the soonest/most-overdue page)
- **AND** a "next" pagination control SHALL be present

#### Scenario: Most-overdue rows sort ahead of in-window rows

- **GIVEN** an authenticated session AND an OVERDUE row (deadline 2 days ago) AND an IN_WINDOW row (deadline in 2 days)
- **WHEN** `GET /admin/privacy-flips` is served (no `status` filter)
- **THEN** the OVERDUE row SHALL appear before the IN_WINDOW row in the rendered table (ascending `privacy_flip_scheduled_at` puts the earlier/past deadline first)

#### Scenario: Following the cursor returns the next, non-overlapping page

- **GIVEN** an authenticated session AND two full pages of rows
- **WHEN** the client follows the "next" control's cursor URL
- **THEN** the returned rows SHALL all sort strictly after (in `privacy_flip_scheduled_at ASC, id ASC` order) the last row of the first page
- **AND** no row from the first page SHALL reappear on the second page

#### Scenario: Malformed cursor falls back to the first page

- **WHEN** an authenticated client sends `GET /admin/privacy-flips?cursor=not-a-valid-cursor`
- **THEN** the response status SHALL be 200
- **AND** the rendered table SHALL show the first page (the malformed cursor is ignored, not treated as an error)

#### Scenario: Last page omits the next control

- **GIVEN** an authenticated session AND exactly one page or fewer of matching rows
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** no "next" pagination control SHALL be rendered (there are no further rows)

#### Scenario: Exact page-size boundary omits the next control; one more row shows it

- **GIVEN** an authenticated session AND EXACTLY page-size matching rows
- **WHEN** `GET /admin/privacy-flips` is served with no cursor
- **THEN** all page-size rows SHALL be rendered AND no "next" control SHALL be present
- **AND** WHEN one additional row is then present (page-size + 1 total) and the same request is re-served, the first page-size rows SHALL be rendered AND a "next" control SHALL be present (the fixed-page fencepost holds at the exact boundary)

#### Scenario: Rows sharing an identical privacy_flip_scheduled_at paginate by the id tiebreaker without loss or duplication

- **GIVEN** an authenticated session AND two or more rows with an IDENTICAL `privacy_flip_scheduled_at` value but distinct `id`s, positioned so the page boundary falls between them (a mass-scheduling event can write colliding timestamps)
- **WHEN** the client pages through via the "next" cursor across that boundary
- **THEN** every such row SHALL appear exactly once across the pages (the `(privacy_flip_scheduled_at, id) > (?, ?)` row-value predicate's `id ASC` tiebreaker prevents both skipping and duplication at the boundary)

#### Scenario: The next-link cursor carries the active filters

- **GIVEN** an authenticated session AND more than one page of rows matching a `status` filter
- **WHEN** the client follows the "next" control rendered for `GET /admin/privacy-flips?status=overdue`
- **THEN** the followed URL SHALL retain `status=overdue` alongside the `cursor` parameter
- **AND** the next page SHALL remain filtered to `status=overdue` (filter + pagination compose; the paginated URL stays shareable)

### Requirement: Composable status and user-search filtering

The system SHALL accept the query parameters `status` and `q`, each filtering the list and composing with logical AND. `status` SHALL match exactly one of the allowed values: `in_window` (filter to `privacy_flip_scheduled_at > NOW()`) or `overdue` (filter to `privacy_flip_scheduled_at <= NOW()`), reusing the SAME `NOW()`-based predicate as the row classification so the filtered set matches the rendered classes. `q` SHALL perform a single-user lookup: a value that parses as a UUID SHALL filter `id = ?`; any other value SHALL filter `LOWER(username) = LOWER(?)` (exact, case-insensitive — served by `users_username_lower_idx`, not a substring scan). All filter values SHALL be applied via parameterized query placeholders — never string-interpolated into SQL.

#### Scenario: Filtering by status=overdue returns only overdue rows

- **GIVEN** an authenticated session AND at least one IN_WINDOW row AND at least one OVERDUE row
- **WHEN** `GET /admin/privacy-flips?status=overdue` is served
- **THEN** every rendered row SHALL be classified OVERDUE (`privacy_flip_scheduled_at <= NOW()`)
- **AND** no IN_WINDOW row SHALL be rendered

#### Scenario: Filtering by status=in_window returns only in-window rows

- **GIVEN** an authenticated session AND rows of both classes
- **WHEN** `GET /admin/privacy-flips?status=in_window` is served
- **THEN** every rendered row SHALL be classified IN_WINDOW (`privacy_flip_scheduled_at > NOW()`)

#### Scenario: Searching by username returns the single matching user

- **GIVEN** an authenticated session AND a pending-flip user with `username = 'budi_kopi'`
- **WHEN** `GET /admin/privacy-flips?q=budi_kopi` is served
- **THEN** the rendered table SHALL contain the `budi_kopi` row AND no unrelated user's row (exact case-insensitive username match)

#### Scenario: Searching by UUID returns the single matching user

- **GIVEN** an authenticated session AND a pending-flip user with a known `id`
- **WHEN** `GET /admin/privacy-flips?q=<that UUID>` is served
- **THEN** the rendered table SHALL contain that user's row (the UUID-shaped `q` filters `id = ?`)

#### Scenario: status and q compose with AND

- **GIVEN** an authenticated session AND an OVERDUE pending-flip user `rina_sore` AND an IN_WINDOW pending-flip user `kang_santuy`
- **WHEN** `GET /admin/privacy-flips?status=overdue&q=kang_santuy` is served
- **THEN** no row SHALL be rendered (the user matches `q` but is IN_WINDOW, so the AND-composed `status=overdue` filter excludes it) — and the empty state SHALL render

#### Scenario: An empty or blank q is ignored, not treated as an empty-username match

- **GIVEN** an authenticated session AND at least one pending-flip row
- **WHEN** `GET /admin/privacy-flips?q=` (empty) or `GET /admin/privacy-flips?q=%20%20` (whitespace-only) is served
- **THEN** the response SHALL be the unfiltered list (the blank `q` is ignored, NOT applied as `LOWER(username) = ''` which would wrongly render the empty state)

### Requirement: Per-status count summary reflects the active filter scope

The system SHALL render a count summary alongside the table that reports, for the **current filter scope** (with any active `q` applied), the number of matching rows broken down as IN_WINDOW vs OVERDUE. The summary exists to surface the stuck-row anomaly (a rising OVERDUE count signals a worker/webhook-handler bug or a mass-scheduling event) at a glance. The counts SHALL reflect the applied `q` filter but SHALL NOT be limited by pagination — they count the ENTIRE filtered result set (ignoring the keyset cursor and page size), not just the rows on the current page. When a `status` filter is active, the summary MAY scope to that bucket; the OVERDUE total SHALL always be derivable.

#### Scenario: Summary reports both buckets when unfiltered

- **GIVEN** an authenticated session AND at least one IN_WINDOW row AND at least one OVERDUE row
- **WHEN** `GET /admin/privacy-flips` is served with no filters
- **THEN** the rendered summary SHALL show a non-zero IN_WINDOW count AND a non-zero OVERDUE count

#### Scenario: Summary counts the whole filtered set, not just the current page

- **GIVEN** an authenticated session AND more matching rows than the fixed page size
- **WHEN** `GET /admin/privacy-flips` is served (the first page)
- **THEN** the summary's total count SHALL equal the full number of matching rows (a value greater than the page size), NOT the page-size number of rows currently displayed

#### Scenario: Summary surfaces the overdue count as the anomaly signal

- **GIVEN** an authenticated session AND several OVERDUE rows
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** the rendered summary SHALL report the OVERDUE count (so a moderator can spot a stuck-row spike without paging through the table)

#### Scenario: Summary respects an active q scope

- **GIVEN** an authenticated session AND a single OVERDUE pending-flip user `budi_kopi` AND other pending-flip users
- **WHEN** `GET /admin/privacy-flips?q=budi_kopi` is served
- **THEN** the rendered summary SHALL reflect only the `q`-scoped result set (OVERDUE = 1, IN_WINDOW = 0), NOT the whole-table totals — the summary counts the SAME filtered set the page query pages over

### Requirement: Malformed filter inputs are handled safely without error or injection

The system SHALL tolerate malformed filter inputs without returning a 500 and without executing attacker-controlled SQL. An unrecognized `status` value (not `in_window` / `overdue`) or an over-long filter value SHALL cause that single filter to be ignored (lenient parse) while the remaining valid filters still apply. Because all values are bound as query parameters, a `q` value containing SQL metacharacters SHALL be treated as a literal filter value (matching no user), never as SQL.

#### Scenario: Unrecognized status value is ignored, other filters still apply

- **WHEN** an authenticated client sends `GET /admin/privacy-flips?status=not-a-real-status&q=budi_kopi`
- **THEN** the response status SHALL be 200
- **AND** the rendered rows SHALL be filtered by `q=budi_kopi` only (the invalid `status` is ignored)

#### Scenario: SQL-metacharacter q value is treated as a literal

- **WHEN** an authenticated client sends `GET /admin/privacy-flips?q=%27%3B+DROP+TABLE+users%3B--` (URL-encoded `'; DROP TABLE users;--`)
- **THEN** the response status SHALL be 200
- **AND** the `users` table SHALL still exist and be queryable afterward (the value was bound as a literal username that matches no user; no SQL was executed from it)
- **AND** the empty state SHALL render (no username equals that literal)

#### Scenario: Over-long q value is bounded, not errored

- **WHEN** an authenticated client sends `GET /admin/privacy-flips?q=<a string far longer than the 60-char username column width>`
- **THEN** the response status SHALL be 200 (the over-long value is length-bounded during lenient parse and, matching no user, renders the empty state — rather than causing a 400/500)

#### Scenario: A maximum-width (60-char) username still matches; one char longer is bounded out

- **GIVEN** an authenticated session AND a pending-flip user whose `username` is exactly 60 characters (the column maximum)
- **WHEN** `GET /admin/privacy-flips?q=<that exact 60-char username>` is served
- **THEN** that user's row SHALL be rendered (the length bound is `>= 60`, so a valid maximum-width username is NOT truncated and still matches exactly)
- **AND** WHEN a 61-character `q` is served, the value SHALL be bounded out (matching no user → empty state), never 400/500 — the truncation guard never shortens a legitimate 60-char username into a non-matching prefix

### Requirement: HTMX partial swap with plain-GET progressive enhancement

The system SHALL serve the monitor table as an HTMX-swappable fragment AND as a full standalone page from the same route, branching on the `HX-Request` header. When the request carries `HX-Request: true`, the system SHALL respond with only the table fragment (the swappable `#privacy-flips-table` element, which includes the count summary so it stays in sync with the active filters) so the filter form and surrounding layout remain in place. When the request does NOT carry `HX-Request`, the system SHALL respond with the full page (which includes the same table fragment), so filtering and pagination work without JavaScript. The filtered/paginated URL SHALL remain shareable (a plain `GET` to a filtered URL SHALL reproduce the same filtered view).

#### Scenario: HTMX request returns only the table fragment

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/privacy-flips?status=overdue` with header `HX-Request: true`
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the `id="privacy-flips-table"` element
- **AND** the response body SHALL NOT contain the full-page `<html>` document wrapper or the base-layout header/footer (it is a fragment, not a full page)

#### Scenario: Plain GET returns the full page

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/privacy-flips?status=overdue` with no `HX-Request` header
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the base-layout structural sections AND the `id="privacy-flips-table"` element
- **AND** the rendered table SHALL reflect the `status=overdue` filter (a shared filtered link reproduces the filtered view)

### Requirement: All rendered values are HTML-escaped, never raw

The system SHALL render every monitor value HTML-escaped in the admin's browser. No row value SHALL be emitted through any template mechanism that bypasses HTML escaping (e.g., a Pebble `raw` filter); the templates rely on Pebble's default-on autoescaping. Because this view renders **user-controlled free text** (`username`, `display_name`), escaping is a load-bearing XSS control here (a materially larger surface than the hash-and-enum-only `admin-rejected-identifiers-viewer`), not merely defense-in-depth.

#### Scenario: A display_name containing markup is escaped, not executed

- **GIVEN** an authenticated session AND a pending-flip user whose `display_name` is the literal string `<script>alert(1)</script>`
- **WHEN** `GET /admin/privacy-flips` is served and the row is rendered
- **THEN** the response body SHALL contain the escaped form (e.g., `&lt;script&gt;`) and SHALL NOT contain a live, unescaped `<script>alert(1)</script>` tag

#### Scenario: A username carrying markup is escaped in BOTH the text and the deep-link href

- **GIVEN** an authenticated session AND a pending-flip user whose `username` contains HTML/URL-significant characters (e.g. an ampersand or angle bracket admitted by the username rules)
- **WHEN** `GET /admin/privacy-flips` is served and the row is rendered
- **THEN** the `username` SHALL be HTML-escaped where it is rendered as text
- **AND** the `username` SHALL be safely encoded where it is interpolated into the `/admin/users?q=<username>` deep-link `href` (attribute/URL context), so a crafted username cannot break out of the attribute or inject script — the `href` value SHALL NOT contain a live unescaped markup sequence

### Requirement: Identity-only PII discipline — no location, deep-link to the shipped lookup

The system SHALL surface only the user's identity (`username`, `display_name`, `id`), current privacy state (`private_profile_opt_in`), and flip state (`privacy_flip_scheduled_at` + derived status). It SHALL NOT surface any location value (`display_location`, raw coordinates, city) — none is read by the monitor query, so the spatial-fuzzing invariant is not engaged. It SHALL NOT surface email or date of birth. The `username` SHALL deep-link to the SHIPPED `/admin/users?q=<username>` moderation lookup (the `admin-user-moderation` surface), NOT to any unshipped per-user profile route.

#### Scenario: Rendered row exposes identity + flip state, not location

- **GIVEN** an authenticated session AND a pending-flip row
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** the rendered row SHALL contain the `username`, `display_name`, privacy state, and scheduled flip timestamp
- **AND** the rendered row SHALL NOT contain any location string, coordinate, email, or date of birth

#### Scenario: Username links to the shipped user-moderation lookup

- **GIVEN** an authenticated session AND a pending-flip user `budi_kopi`
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** the rendered `budi_kopi` row SHALL link to `/admin/users?q=budi_kopi` (the shipped lookup), and SHALL NOT link to a `/admin/users/{id}` profile route

### Requirement: The capability adds only read routes; mutation methods are unmapped

The system SHALL expose only `GET` under the `/admin/privacy-flips` path. It SHALL NOT wire any `POST`, `PUT`, `PATCH`, or `DELETE` handler on that path. The monitor SHALL introduce no mutation surface over `users.privacy_flip_scheduled_at` or any other column — it neither clears nor expedites a scheduled flip (anomalies are escalated to an out-of-band worker fix). Serving the monitor SHALL write no `admin_actions_log` row.

#### Scenario: POST on the privacy-flips path is not wired

- **GIVEN** an authenticated session (so the request passes the auth gate)
- **WHEN** the client sends `POST /admin/privacy-flips`
- **THEN** the response status SHALL be 405 Method Not Allowed (the route exists but only `GET` is wired; consistent with `admin-panel-scaffold`'s non-GET posture)

#### Scenario: Serving the monitor writes no audit row and mutates nothing

- **GIVEN** an authenticated session AND a known count N of `users` rows with `privacy_flip_scheduled_at IS NOT NULL` AND a known count M of rows in `admin_actions_log`
- **WHEN** the client sends `GET /admin/privacy-flips` (one or more times)
- **THEN** the count of `users` rows with a pending flip SHALL remain N (no flip cleared or scheduled)
- **AND** the count of rows in `admin_actions_log` SHALL remain M (viewing the monitor is not an auditable action — no insert occurs)

#### Scenario: No clear / expedite control is wired in this change

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** the rendered page SHALL NOT contain a clear / expedite / force-flip control for any row
- **AND** no route under `/admin/privacy-flips` SHALL accept a mutation request that modifies `privacy_flip_scheduled_at`

### Requirement: The monitor is accessible to every authenticated admin role

The system SHALL grant read access to `GET /admin/privacy-flips` to any admin with a valid session, regardless of `admin_users.role` (`owner`, `admin`, `moderator`, `read_only`). The monitor SHALL NOT reject a `read_only` admin. No role-based redaction of rows or columns SHALL be applied in this capability.

#### Scenario: read_only admin can view the monitor

- **GIVEN** an authenticated session for an admin whose `role = 'read_only'`
- **WHEN** the client sends `GET /admin/privacy-flips`
- **THEN** the response status SHALL be 200
- **AND** the monitor table SHALL be rendered (no role-based rejection)

### Requirement: Empty result renders an empty state, not an error

The system SHALL render an explicit empty-state message when no `users` rows match the current filters (including the case where no user has a pending flip at all), rather than an error or a blank page.

#### Scenario: No matching rows renders an empty-state message

- **GIVEN** an authenticated session AND no `users` row matches the active filter (e.g. `q` for a user with no pending flip)
- **WHEN** `GET /admin/privacy-flips?q=someone_with_no_pending_flip` is served
- **THEN** the response status SHALL be 200
- **AND** the rendered body SHALL contain an empty-state indicator (e.g., a "no entries" message) rather than a table of rows or an error page

#### Scenario: Empty state also renders inside the HTMX fragment

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/privacy-flips?q=someone_with_no_pending_flip` is served with header `HX-Request: true`
- **THEN** the response status SHALL be 200
- **AND** the returned `#privacy-flips-table` fragment SHALL contain the empty-state indicator (not a blank/empty fragment or an error)
