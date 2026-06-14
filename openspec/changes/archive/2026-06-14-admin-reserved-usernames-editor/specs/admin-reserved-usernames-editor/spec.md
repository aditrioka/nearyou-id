## ADDED Requirements

### Requirement: Admins can view the reserved-usernames list

The system SHALL serve `GET /admin/reserved-usernames` to any authenticated admin (no write role required), rendering `reserved_usernames` rows keyset-paginated newest-first over `(created_at DESC, username)`. The listing SHALL support a `source` filter (`seed_system` | `admin_added` | all) and a case-insensitive substring search on `username`, composable with the cursor. The surface SHALL render via HTMX fragment swap with a plain-`GET` (no-JS) fallback, and SHALL HTML-escape every rendered `username` and `reason`.

#### Scenario: List renders rows newest-first
- **WHEN** an authenticated admin requests `GET /admin/reserved-usernames`
- **THEN** the response SHALL list `reserved_usernames` rows ordered by `(created_at DESC, username)` with the keyset cursor for the next page

#### Scenario: Source filter narrows the list
- **WHEN** an admin requests the list with `source=admin_added`
- **THEN** the response SHALL include only `admin_added` rows and exclude `seed_system` rows

#### Scenario: Substring search matches username case-insensitively
- **GIVEN** a reserved row `username = "admin"`
- **WHEN** an admin searches with `q=ADM`
- **THEN** the `admin` row SHALL appear in the results

#### Scenario: Rendered username and reason are HTML-escaped
- **GIVEN** an `admin_added` row whose `reason` contains `<script>alert(1)</script>`
- **WHEN** the list is rendered
- **THEN** the reason SHALL appear HTML-escaped (no executable markup in the response body)

#### Scenario: An unauthenticated request is redirected to login
- **WHEN** an unauthenticated client requests `GET /admin/reserved-usernames`
- **THEN** the system SHALL redirect (302) to `/admin/login` and SHALL NOT render the list

### Requirement: Admins can add a single reserved username

The system SHALL accept `POST /admin/reserved-usernames` to insert one reserved username with `source = 'admin_added'`. The request SHALL require a valid CSRF token and a write role. `username` SHALL be trimmed, lowercased, and validated against the canonical username charset (`[a-z0-9._]`, length 1..30) — charset-only, deliberately not the full signup shape rules, so short/edge handles (matching the V3 seed short-handles) remain reservable; `reason` SHALL be trimmed, non-blank, and at most 64 characters (the `reserved_usernames.reason VARCHAR(64)` column width). A blank or charset-invalid `username`, or a blank or over-64-character `reason`, SHALL return 400 with no mutation (never a DB-overflow 5xx). A successful add SHALL write exactly one `admin_actions_log` row with `action_type = 'reserved_username_added'`. Attempting to add a username that already exists (including a case-variant that normalizes to an existing entry) SHALL be an in-band "already reserved" outcome with no mutation and no audit row (never a 5xx).

#### Scenario: Valid add inserts an admin_added row and one audit row
- **GIVEN** an admin with a valid CSRF token and write role
- **WHEN** they add `username = "kopibrand"`, `reason = "brand protection"`
- **THEN** a `reserved_usernames` row SHALL be inserted with `source = 'admin_added'` AND exactly one `admin_actions_log` row with `action_type = 'reserved_username_added'`, `target_type = 'reserved_username'`, `target_id = 'kopibrand'` SHALL be written

#### Scenario: Adding an already-reserved username is a no-op
- **GIVEN** a reserved row `username = "admin"` already exists
- **WHEN** an admin attempts to add `username = "admin"`
- **THEN** the response SHALL surface an in-band "already reserved" message AND no row SHALL be inserted AND no `admin_actions_log` row SHALL be written

#### Scenario: Blank reason is rejected with no write
- **WHEN** an admin submits a valid `username` with a blank `reason`
- **THEN** the response SHALL be 400 AND no `reserved_usernames` row SHALL be inserted AND no audit row SHALL be written

#### Scenario: Username is normalized and charset-validated
- **WHEN** an admin submits `username = "  Brand_X "` with a valid reason
- **THEN** the system SHALL normalize it to `brand_x` before insert; AND a `username` containing characters outside the canonical username charset SHALL return 400 with no mutation

#### Scenario: A case-variant of an existing username is a normalized no-op
- **GIVEN** a reserved row `username = "admin"` already exists
- **WHEN** an admin attempts to add `username = "Admin"`
- **THEN** the system SHALL normalize it to `admin`, surface the in-band "already reserved" outcome, AND make no mutation and write no audit row

#### Scenario: A reason longer than the column width is rejected with no write
- **WHEN** an admin submits a valid `username` with a `reason` of 65 characters
- **THEN** the response SHALL be 400 AND no `reserved_usernames` row SHALL be inserted AND no DB-overflow 5xx SHALL occur (a 64-character `reason` SHALL be accepted)

### Requirement: Admins can bulk-add reserved usernames from a CSV

The system SHALL accept `POST /admin/reserved-usernames/bulk` with the CSV submitted as a **text form field** (`username,reason` rows, optional header) — not a `multipart/form-data` file upload, so it is read through the standard CSRF-gated form-parameters path — and process it in a single transaction, classifying each data row as **added** (new + valid), **skipped (duplicate)** (already in the table, or a username already accepted earlier in the same upload), or **skipped (invalid)** (wrong arity, blank/charset-failing username, blank `reason`, or `reason` over 64 characters), and returning a per-row report of the three buckets. Each newly-inserted row SHALL be `source = 'admin_added'` and SHALL write one `reserved_username_added` audit row. The request SHALL require a valid CSRF token and a write role. An upload exceeding the guardrails (more than 1000 data rows or 256 KB) SHALL return 400 before parsing; an empty or header-only submission SHALL return an empty (0/0/0) report, not an error.

#### Scenario: Bulk add inserts new rows and skips duplicates with a report
- **GIVEN** `reserved_usernames` already contains `admin`
- **WHEN** an admin uploads a CSV with rows `admin,…`, `newhandle1,…`, `newhandle2,…`
- **THEN** `newhandle1` and `newhandle2` SHALL be inserted as `admin_added` AND `admin` SHALL be reported as a skipped duplicate AND the report SHALL list 2 added, 1 skipped-duplicate

#### Scenario: Malformed rows are reported and skipped without aborting the batch
- **WHEN** an admin uploads a CSV with one valid row and one row with a blank username
- **THEN** the valid row SHALL be inserted AND the blank-username row SHALL be reported as skipped-invalid with its line number AND the batch SHALL NOT be aborted

#### Scenario: Each inserted bulk row writes its own audit row
- **WHEN** a bulk upload inserts 3 new usernames
- **THEN** exactly 3 `admin_actions_log` rows with `action_type = 'reserved_username_added'` SHALL be written (one per inserted username)

#### Scenario: Oversized upload is rejected before parsing
- **WHEN** an admin uploads a CSV with more than 1000 data rows
- **THEN** the response SHALL be 400 AND no `reserved_usernames` row SHALL be inserted

#### Scenario: A bulk upload that would exceed the trailing-hour cap is rejected wholesale
- **GIVEN** an admin whose trailing-hour reserved-username write count is 98
- **WHEN** they upload a CSV whose **added** bucket (new + valid, after duplicate/invalid exclusion) is 5 rows
- **THEN** the entire upload SHALL be rejected in-band ("would exceed your 100/hour quota") AND no `reserved_usernames` row SHALL be inserted AND no `admin_actions_log` row SHALL be written (the count holds at 98)

#### Scenario: A username repeated within one upload is added once
- **WHEN** an admin uploads a CSV containing the same new `username` on two rows
- **THEN** the username SHALL be inserted exactly once with exactly one `reserved_username_added` audit row AND the second occurrence SHALL be reported as a skipped duplicate (no phantom audit row)

#### Scenario: An empty or header-only upload returns an empty report
- **WHEN** an admin submits an empty CSV (or only the `username,reason` header row)
- **THEN** the response SHALL be a successful empty report (0 added, 0 skipped-duplicate, 0 skipped-invalid) AND SHALL NOT be a 400 or 5xx AND no row SHALL be inserted

### Requirement: Admins can edit the reason of an admin_added reserved username

The system SHALL accept `POST /admin/reserved-usernames/{username}/edit-reason` to update only the `reason` of an `admin_added` row. The request SHALL require a valid CSRF token and a write role; `reason` SHALL be trimmed, non-blank, and at most 64 characters (the column width — an over-64 reason is a 400, never an overflow 5xx). Editing the reason of a `seed_system` row SHALL be refused at the application layer (no mutation, in-band message, no audit row) — the system SHALL NOT rely on the DB for this guard, since the V3 trigger does not block seed reason edits. A successful edit SHALL write one `reserved_username_edited` audit row whose `before_state`/`after_state` capture the old/new reason, and (via the V3 `reserved_usernames_set_updated_at` trigger) SHALL refresh the row's `updated_at`.

#### Scenario: Edit reason on an admin_added row succeeds with an audit row
- **GIVEN** an `admin_added` row `username = "kopibrand"`, `reason = "old"`
- **WHEN** an admin edits its reason to `"new"`
- **THEN** the row's `reason` SHALL become `"new"` AND one `admin_actions_log` row with `action_type = 'reserved_username_edited'`, `before_state.reason = "old"`, `after_state.reason = "new"` SHALL be written

#### Scenario: Editing a seed_system reason is refused at the app layer
- **GIVEN** the `seed_system` row `username = "admin"`
- **WHEN** an admin attempts to edit its reason
- **THEN** the response SHALL surface an in-band "seed entry cannot be edited" message AND the `reason` SHALL be unchanged AND no audit row SHALL be written

#### Scenario: Editing a nonexistent username is a no-op
- **WHEN** an admin attempts to edit the reason of a username not present in `reserved_usernames`
- **THEN** the response SHALL surface an in-band "not found" message AND no mutation SHALL occur

#### Scenario: A successful reason edit refreshes updated_at
- **GIVEN** an `admin_added` row with a known `updated_at`
- **WHEN** an admin successfully edits its `reason`
- **THEN** the row's `updated_at` SHALL advance to the edit time (the V3 `reserved_usernames_set_updated_at` trigger fires on the UPDATE)

### Requirement: Admins can remove an admin_added reserved username

The system SHALL accept `POST /admin/reserved-usernames/{username}/remove` to delete an `admin_added` row. The request SHALL require a valid CSRF token and a write role. Removing a `seed_system` row SHALL be refused at the application layer (no mutation, in-band message, no audit row), with the V3 `reserved_usernames_protect_seed` trigger as the defense-in-depth second line. A successful removal SHALL write one `reserved_username_removed` audit row whose `before_state` captures the removed row.

#### Scenario: Remove an admin_added row succeeds with an audit row
- **GIVEN** an `admin_added` row `username = "kopibrand"`
- **WHEN** an admin removes it
- **THEN** the row SHALL be deleted AND one `admin_actions_log` row with `action_type = 'reserved_username_removed'`, `target_id = 'kopibrand'`, `before_state` capturing the removed row SHALL be written

#### Scenario: Removing a seed_system row is refused at the app layer
- **GIVEN** the `seed_system` row `username = "support"`
- **WHEN** an admin attempts to remove it
- **THEN** the response SHALL surface an in-band "seed entry cannot be removed" message AND the row SHALL remain AND no audit row SHALL be written

### Requirement: The DB trigger is the defense-in-depth backstop for seed protection

The existing `reserved_usernames_protect_seed` trigger (V3) SHALL remain the second line of defense behind the app-layer seed guard: a direct `DELETE` of a `seed_system` row, or a direct `UPDATE` changing a `seed_system` row's `source`, SHALL be rejected at the database even if the application guard is bypassed. (This is the docs Pre-Launch "reserved_usernames trigger test".)

#### Scenario: A direct delete of a seed row is rejected by the trigger
- **WHEN** a `DELETE FROM reserved_usernames WHERE username = 'admin'` is executed directly (bypassing the app guard)
- **THEN** the database SHALL raise an exception and the `admin` row SHALL remain

#### Scenario: A direct source-change of a seed row is rejected by the trigger
- **WHEN** an `UPDATE reserved_usernames SET source = 'admin_added' WHERE username = 'admin'` is executed directly
- **THEN** the database SHALL raise an exception and the row's `source` SHALL remain `seed_system`

### Requirement: Reserved-username writes are rate-limited per admin at 100 per trailing hour

The system SHALL enforce a per-acting-admin cap of 100 reserved-username write actions (`reserved_username_added` + `reserved_username_edited` + `reserved_username_removed`) per trailing one-hour window, sourced by COUNT over `admin_actions_log` for the acting admin within `NOW() - INTERVAL '1 hour'`, checked inside the same transaction as the gated write (soft cap, no row lock, ±1 concurrency tolerance accepted). A write at or over the cap SHALL be rejected in-band ("quota exceeded (100/hour)") with no mutation and no audit row, never a 5xx. The cap SHALL be per-admin and SHALL count only the three reserved action types within the window.

#### Scenario: A write under the cap proceeds and advances the count
- **GIVEN** an admin with 99 reserved-username write rows in the trailing hour
- **WHEN** they perform one more reserved-username write
- **THEN** the action SHALL apply normally AND one new audit row SHALL be written (bringing the trailing-hour count to 100)

#### Scenario: A write at the cap is rejected without effect
- **GIVEN** an admin with 100 reserved-username write rows in the trailing hour
- **WHEN** they attempt another reserved-username write against a valid target
- **THEN** the response SHALL surface "quota exceeded (100/hour)" AND no `reserved_usernames` row SHALL be mutated AND no new `admin_actions_log` row SHALL be written

#### Scenario: The cap counts only the reserved action types in-window
- **GIVEN** an admin with, in the last hour, 5 `user_suspended` rows and 2 `reserved_username_added` rows, plus 50 `reserved_username_added` rows older than one hour
- **WHEN** that admin's reserved-username trailing-hour count is computed
- **THEN** the count SHALL be 2 (suspends and out-of-window rows are excluded)

#### Scenario: The cap is per-admin
- **GIVEN** admin A at the cap and admin B with 0 reserved-username writes in the trailing hour
- **WHEN** admin B performs a reserved-username write
- **THEN** admin B's write SHALL apply normally (admin A's exhausted quota does not block admin B)

### Requirement: State-changing requests are CSRF- and write-role-gated in order

Every reserved-username write (`POST` add / bulk / edit-reason / remove) SHALL validate the CSRF token FIRST (a missing or mismatched token returns 403, writes an `admin_csrf_violation` audit row, and performs no mutation), THEN require a write role (a read-only admin role returns 403 with no mutation), THEN parse and validate the target/body. A CSRF-failing request SHALL never reach the mutation or the rate-limit count.

#### Scenario: A write without a valid CSRF token is rejected and audited
- **WHEN** a write POST is made without a valid CSRF token
- **THEN** the response SHALL be 403 AND one `admin_actions_log` row with `action_type = 'admin_csrf_violation'` SHALL be written AND no `reserved_usernames` row SHALL be mutated

#### Scenario: A read-only-role admin is rejected on a write route
- **GIVEN** an admin whose role lacks write permission, with a valid CSRF token
- **WHEN** they attempt a reserved-username write
- **THEN** the response SHALL be 403 AND no `reserved_usernames` row SHALL be mutated

#### Scenario: CSRF is checked before role and parsing
- **WHEN** a write POST with no CSRF token targets a malformed/nonexistent username
- **THEN** the request SHALL be rejected at the CSRF gate (403 + `admin_csrf_violation`) and SHALL NOT reach target parsing, the role gate, or any mutation
