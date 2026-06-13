## ADDED Requirements

### Requirement: Authenticated GET /admin/blocks renders the block-pairs table

The system SHALL serve `GET /admin/blocks` as an authenticated route, wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block established by the `admin-login` capability so the session middleware gates it. On a valid session it SHALL return HTTP 200 with an HTML page that extends the shared admin base layout (per `admin-panel-scaffold`) and renders a table of `user_blocks` pairs ordered newest-first (`created_at DESC, blocker_id DESC, blocked_id DESC`). Each rendered row SHALL display: the blocker username, the blocked username, `created_at` (rendered in UTC), and a "Bidirectional?" indicator. Usernames SHALL be resolved by joining `user_blocks` to `users` on both `blocker_id` and `blocked_id`. The route SHALL be read-only — it SHALL NOT write any `admin_actions_log` row, and it SHALL NOT mutate any table.

#### Scenario: Authenticated request renders the table with block-pair rows

- **GIVEN** an authenticated admin session AND at least one row exists in `user_blocks`
- **WHEN** the client sends `GET /admin/blocks` carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL be Pebble-rendered HTML that contains the blocker user's `username` AND the blocked user's `username` for the existing row
- **AND** the rendered HTML SHALL contain the base-layout structural sections (header, nav, footer)

#### Scenario: Unauthenticated request redirects to the login page

- **WHEN** a client sends `GET /admin/blocks` with no `__Host-admin_session` cookie (or an invalid / expired / revoked / idle-timed-out session)
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** no block-registry table content SHALL be served

#### Scenario: Rows are ordered newest-first

- **GIVEN** an authenticated session AND three `user_blocks` rows with strictly increasing `created_at` values
- **WHEN** `GET /admin/blocks` is served
- **THEN** the row with the latest `created_at` SHALL appear before the others in the rendered table (newest-first `created_at DESC, blocker_id DESC, blocked_id DESC` order)

#### Scenario: Each username deep-links to the shipped user lookup

- **GIVEN** an authenticated session AND a `user_blocks` row whose blocker has username `sari.menyapa`
- **WHEN** `GET /admin/blocks` is served and the row is rendered
- **THEN** the blocker username SHALL be rendered as a link to `/admin/users?q=sari.menyapa` (the shipped `admin-user-moderation` lookup), AND the link SHALL NOT target `/admin/users/{id}` (the per-user profile page is out of this change's scope)

### Requirement: Keyset pagination over (created_at, blocker_id, blocked_id) with a fixed page size

The system SHALL paginate the block-registry list using a keyset cursor over `(created_at, blocker_id, blocked_id)` in descending order with a fixed page size. It SHALL NOT use SQL `OFFSET` for pagination. When more rows exist beyond the current page, the system SHALL render an "older" navigation control carrying an opaque cursor encoding the last-displayed row's `(created_at, blocker_id, blocked_id)`; following that control SHALL return the next-older page whose first row immediately precedes the cursor in `created_at DESC, blocker_id DESC, blocked_id DESC` order. A malformed or absent cursor SHALL be treated as a request for the first (newest) page, never an error. The keyset predicate SHALL be the row-value comparison `(created_at, blocker_id, blocked_id) < (?, ?, ?)` so the composite primary key `(blocker_id, blocked_id)` deterministically breaks ties between rows sharing an identical `created_at`.

#### Scenario: Page is capped at the fixed page size

- **GIVEN** an authenticated session AND more `user_blocks` rows than the fixed page size
- **WHEN** `GET /admin/blocks` is served with no cursor
- **THEN** the rendered table SHALL contain exactly the page-size number of rows (the newest page)
- **AND** an "older" pagination control SHALL be present

#### Scenario: Following the cursor returns the next-older, non-overlapping page

- **GIVEN** an authenticated session AND two full pages of rows
- **WHEN** the client follows the "older" control's cursor URL
- **THEN** the returned rows SHALL all be strictly older (in `created_at DESC, blocker_id DESC, blocked_id DESC` order) than the last row of the first page
- **AND** no row from the first page SHALL reappear on the second page

#### Scenario: Malformed cursor falls back to the first page

- **WHEN** an authenticated client sends `GET /admin/blocks?cursor=not-a-valid-cursor`
- **THEN** the response status SHALL be 200
- **AND** the rendered table SHALL show the newest page (the malformed cursor is ignored, not treated as an error)

#### Scenario: Last page omits the older control

- **GIVEN** an authenticated session AND exactly one page or fewer of matching rows
- **WHEN** `GET /admin/blocks` is served
- **THEN** no "older" pagination control SHALL be rendered (there are no older rows)

#### Scenario: Exact page-size boundary omits the older control; one more row shows it

- **GIVEN** an authenticated session AND EXACTLY page-size matching rows
- **WHEN** `GET /admin/blocks` is served with no cursor
- **THEN** all page-size rows SHALL be rendered AND no "older" control SHALL be present (there is no older row)
- **AND** WHEN one additional older row is then present (page-size + 1 total) and the same request is re-served, the newest page-size rows SHALL be rendered AND an "older" control SHALL be present (the fixed-page fencepost holds at the exact boundary)

#### Scenario: Rows sharing an identical created_at paginate by the primary-key tiebreaker without loss or duplication

- **GIVEN** an authenticated session AND two or more rows with an IDENTICAL `created_at` value but distinct `(blocker_id, blocked_id)` keys, positioned so the page boundary falls between them (`created_at` defaults to `NOW()`, so a block burst can write colliding timestamps)
- **WHEN** the client pages through via the "older" cursor across that boundary
- **THEN** every such row SHALL appear exactly once across the pages (the `(created_at, blocker_id, blocked_id) < (?, ?, ?)` row-value predicate's primary-key tiebreaker prevents both skipping and duplication at the boundary)

#### Scenario: The older-link cursor carries the active search filter

- **GIVEN** an authenticated session AND more than one page of rows matching a search filter
- **WHEN** the client follows the "older" control rendered for `GET /admin/blocks?q=budi_kopi`
- **THEN** the followed URL SHALL retain `q=budi_kopi` alongside the `cursor` parameter
- **AND** the next page SHALL remain filtered to `q=budi_kopi` (filter + pagination compose; the paginated URL stays shareable)

### Requirement: Either-side search by username or user ID

The system SHALL accept a single optional `q` query parameter that filters the block-registry list by matching the search term against EITHER side of the pair (the blocker OR the blocked user). When `q` parses as a valid UUID, the system SHALL match rows where `blocker_id = ?` OR `blocked_id = ?` (the UUID bound to both placeholders). When `q` is not a valid UUID, the system SHALL match rows where the blocker's username OR the blocked's username equals `q` case-insensitively (`LOWER(username) = LOWER(?)`, served by the existing `users_username_lower_idx`). The match SHALL be an EXACT username match, not a substring match. All values SHALL be applied via parameterized query placeholders — never string-interpolated into SQL. An absent or blank `q` SHALL return the unfiltered newest-first list.

#### Scenario: Searching by a username matches pairs on either side

- **GIVEN** an authenticated session AND a row where `budi_kopi` is the blocker AND another row where `budi_kopi` is the blocked user
- **WHEN** `GET /admin/blocks?q=budi_kopi` is served
- **THEN** both rows SHALL be rendered (the term matches the blocker side of one row and the blocked side of the other)
- **AND** rows involving neither `budi_kopi` SHALL NOT be rendered

#### Scenario: Username match is case-insensitive and exact

- **GIVEN** an authenticated session AND a row involving the user whose username is `Budi_Kopi`
- **WHEN** `GET /admin/blocks?q=budi_kopi` is served
- **THEN** that row SHALL be rendered (case-insensitive match)
- **AND** WHEN `GET /admin/blocks?q=budi` is served instead, that row SHALL NOT be rendered (exact match, not substring)

#### Scenario: Searching by a user ID UUID matches pairs on either side

- **GIVEN** an authenticated session AND the UUID of a user who appears as a blocker in one row and as a blocked user in another row
- **WHEN** `GET /admin/blocks?q=<that-uuid>` is served
- **THEN** both rows SHALL be rendered (the UUID matches `blocker_id` in one and `blocked_id` in the other)

#### Scenario: Absent search returns the unfiltered list

- **GIVEN** an authenticated session AND rows in `user_blocks`
- **WHEN** `GET /admin/blocks` is served with no `q` parameter
- **THEN** the unfiltered newest-first list SHALL be rendered

### Requirement: Non-matching and malformed search inputs are handled safely without error or injection

The system SHALL tolerate non-matching and malformed `q` inputs without returning a 500 and without executing attacker-controlled SQL. A `q` that is neither a valid UUID nor an existing username SHALL produce an empty result set (the empty-state, not an error). Because the value is bound as a query parameter, a `q` containing SQL metacharacters SHALL be treated as a literal filter value, never as SQL. An over-long `q` value SHALL be length-bounded during lenient parse and matched as a literal (matching no username), never causing a 400/500.

#### Scenario: A search term matching no user yields the empty state

- **WHEN** an authenticated client sends `GET /admin/blocks?q=nobody_has_this_username`
- **THEN** the response status SHALL be 200
- **AND** the rendered body SHALL contain the empty-state indicator (no row matched the term)

#### Scenario: SQL-metacharacter search value is treated as a literal

- **WHEN** an authenticated client sends `GET /admin/blocks?q=%27%3B+DROP+TABLE+user_blocks%3B--` (URL-encoded `'; DROP TABLE user_blocks;--`)
- **THEN** the response status SHALL be 200
- **AND** the `user_blocks` table SHALL still exist and be queryable afterward (the value matched no username and was bound as a literal; no SQL was executed from it)

#### Scenario: Over-long search value is bounded, not errored

- **WHEN** an authenticated client sends `GET /admin/blocks?q=<a string far longer than the 60-char username column width>`
- **THEN** the response status SHALL be 200 (the over-long value is length-bounded during lenient parse and, matching no username, yields the empty state — rather than causing a 400/500)

### Requirement: Bidirectional indicator reflects a mutual block

The system SHALL render, for each block-pair row, a "Bidirectional?" indicator that is affirmative ("yes (mutual)") when the reverse pair `(blocked_id, blocker_id)` also exists in `user_blocks`, and negative ("no") otherwise. The indicator SHALL be computed via an `EXISTS` subquery against `user_blocks` (not a self-join that would duplicate rows).

#### Scenario: A mutual block pair is marked bidirectional

- **GIVEN** an authenticated session AND both `(A → B)` and `(B → A)` rows exist in `user_blocks`
- **WHEN** `GET /admin/blocks` is served
- **THEN** the `(A → B)` row's "Bidirectional?" indicator SHALL be affirmative (mutual)

#### Scenario: A one-directional block is marked non-bidirectional

- **GIVEN** an authenticated session AND only the `(A → B)` row exists (no `(B → A)` row)
- **WHEN** `GET /admin/blocks` is served
- **THEN** the `(A → B)` row's "Bidirectional?" indicator SHALL be negative (not mutual)

#### Scenario: The bidirectional EXISTS check does not duplicate the row

- **GIVEN** an authenticated session AND a mutual block pair `(A → B)` / `(B → A)`
- **WHEN** `GET /admin/blocks` is served unfiltered
- **THEN** the `(A → B)` directed row SHALL appear exactly once (the bidirectionality is a per-row computed flag, not a join that multiplies rows) — both directed rows are still listed as their own entries, but neither is duplicated by the indicator computation

### Requirement: HTMX partial swap with plain-GET progressive enhancement

The system SHALL serve the block-registry table as an HTMX-swappable fragment AND as a full standalone page from the same route, branching on the `HX-Request` header. When the request carries `HX-Request: true`, the system SHALL respond with only the table fragment (the swappable `#block-registry-table` element) so the search form and surrounding layout remain in place. When the request does NOT carry `HX-Request`, the system SHALL respond with the full page (which includes the same table fragment), so searching and pagination work without JavaScript. The filtered/paginated URL SHALL remain shareable (a plain `GET` to a filtered URL SHALL reproduce the same filtered view).

#### Scenario: HTMX request returns only the table fragment

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/blocks?q=budi_kopi` with header `HX-Request: true`
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the `id="block-registry-table"` element
- **AND** the response body SHALL NOT contain the full-page `<html>` document wrapper or the base-layout header/footer (it is a fragment, not a full page)

#### Scenario: Plain GET returns the full page

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/blocks?q=budi_kopi` with no `HX-Request` header
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the base-layout structural sections AND the `id="block-registry-table"` element
- **AND** the rendered table SHALL reflect the `q=budi_kopi` filter (a shared filtered link reproduces the filtered view)

### Requirement: All rendered values are HTML-escaped, never raw

The system SHALL render every block-registry value HTML-escaped in the admin's browser. No row value SHALL be emitted through any template mechanism that bypasses HTML escaping (e.g., a Pebble `raw` filter); the templates rely on Pebble's default-on autoescaping. Because `username` and `display_name` are user-controlled free-text columns (unlike the hash-only rejected-identifiers surface), escaping is load-bearing here, not merely defense-in-depth.

#### Scenario: A username containing markup is escaped, not executed

- **GIVEN** an authenticated session AND a `user_blocks` row whose blocker user's `username` column has been set (e.g., via a test fixture) to a value containing the literal string `<script>alert(1)</script>`
- **WHEN** `GET /admin/blocks` is served and the row is rendered
- **THEN** the response body SHALL contain the escaped form (e.g., `&lt;script&gt;`) and SHALL NOT contain a live, unescaped `<script>alert(1)</script>` tag

### Requirement: The capability adds only read routes; mutation methods are unmapped

The system SHALL expose only `GET` under the `/admin/blocks` path. It SHALL NOT wire any `POST`, `PUT`, `PATCH`, or `DELETE` handler on that path. The viewer SHALL introduce no mutation surface over `user_blocks` or any other table — it SHALL NOT create a block, remove a block, or act on behalf of any user. A page banner SHALL state that block enforcement stays in the product path via the bidirectional NOT-IN join (the `BlockExclusionJoinRule` invariant) and that this surface only reads.

#### Scenario: POST on the blocks path is not wired

- **GIVEN** an authenticated session (so the request passes the auth gate)
- **WHEN** the client sends `POST /admin/blocks`
- **THEN** the response status SHALL be 405 Method Not Allowed (the route exists but only `GET` is wired; consistent with `admin-panel-scaffold`'s non-GET posture)

#### Scenario: Serving the viewer writes no audit row and mutates nothing

- **GIVEN** an authenticated session AND a known count N of rows in `user_blocks` AND a known count M of rows in `admin_actions_log`
- **WHEN** the client sends `GET /admin/blocks` (one or more times)
- **THEN** the count of rows in `user_blocks` SHALL remain N
- **AND** the count of rows in `admin_actions_log` SHALL remain M (viewing the registry is not itself an auditable action — no insert occurs)

### Requirement: Block relationships are surfaced for dispute-resolution context only

The system SHALL surface block-pair relationships ("who blocked whom") solely for the dispute-resolution / support-ticket context described in [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Block User Registry. Serving this view SHALL NOT notify either user in a pair, SHALL NOT change either user's block state, and SHALL NOT cross-link a row to any surface beyond the username deep-link to the shipped `/admin/users` lookup. The bidirectional block-enforcement semantics in the product path are unaffected by this read surface.

#### Scenario: Viewing a block pair notifies neither user

- **GIVEN** an authenticated session AND a `user_blocks` row `(A → B)` AND a known count of `notifications` rows for users A and B
- **WHEN** `GET /admin/blocks` is served and the `(A → B)` row is rendered
- **THEN** no `notifications` row SHALL be written for user A or user B (the registry is a passive read surface; neither party is informed their block was viewed)

### Requirement: The viewer is accessible to every authenticated admin role

The system SHALL grant read access to `GET /admin/blocks` to any admin with a valid session, regardless of `admin_users.role` (`owner`, `admin`, `moderator`, `read_only`). The viewer SHALL NOT reject a `read_only` admin. No role-based redaction of rows or columns SHALL be applied in this capability.

#### Scenario: read_only admin can view the block registry

- **GIVEN** an authenticated session for an admin whose `role = 'read_only'`
- **WHEN** the client sends `GET /admin/blocks`
- **THEN** the response status SHALL be 200
- **AND** the block-registry table SHALL be rendered (no role-based rejection)

### Requirement: Empty result renders an empty state, not an error

The system SHALL render an explicit empty-state message when no `user_blocks` rows match the current search (including the unfiltered case of an empty table), rather than an error or a blank page. The empty-state SHALL render both as a full page and inside the HTMX fragment.

#### Scenario: No matching rows renders an empty-state message

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/blocks?q=nobody_has_this_username` is served (a search that matches no rows)
- **THEN** the response status SHALL be 200
- **AND** the rendered body SHALL contain an empty-state indicator (e.g., a "no entries" message) rather than a table of rows or an error page

#### Scenario: Empty state also renders inside the HTMX fragment

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/blocks?q=nobody_has_this_username` is served with header `HX-Request: true`
- **THEN** the response status SHALL be 200
- **AND** the returned `#block-registry-table` fragment SHALL contain the empty-state indicator (not a blank/empty fragment or an error)

#### Scenario: An empty user_blocks table renders the empty state, not an error

- **GIVEN** an authenticated session AND zero rows in `user_blocks`
- **WHEN** `GET /admin/blocks` is served with no search filter
- **THEN** the response status SHALL be 200
- **AND** the rendered body SHALL contain the empty-state indicator (the unfiltered-empty-table case, distinct from the no-match-search case)
