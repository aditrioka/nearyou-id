## MODIFIED Requirements

### Requirement: Admins can bulk-add reserved usernames from a CSV

The system SHALL accept `POST /admin/reserved-usernames/bulk` with the CSV submitted as a **text form field** (`username,reason` rows, optional header) — not a `multipart/form-data` file upload, so it is read through the standard CSRF-gated form-parameters path — and process it in a single transaction, classifying each data row as **added** (new + valid), **skipped (duplicate)** (already in the table, or a username already accepted earlier in the same upload), or **skipped (invalid)** (wrong arity, blank/charset-failing username, blank `reason`, or `reason` over 64 characters), and returning a per-row report of the three buckets. Each newly-inserted row SHALL be `source = 'admin_added'` and SHALL write one `reserved_username_added` audit row. The request SHALL require a valid CSRF token and a write role. An upload exceeding the guardrails (more than 1000 data rows or 256 KB) SHALL return 400 before parsing; an empty or header-only submission SHALL return an empty (0/0/0) report, not an error. The whole request body is additionally bounded by this path's 1 MiB transport limit (`backend-bootstrap` § Global request body size limit): a body above it is rejected `413 payload_too_large` before the route runs, so the `400` guardrail applies to uploads up to that limit.

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

#### Scenario: Body above the transport limit is rejected 413 before the route runs
- **WHEN** a `POST /admin/reserved-usernames/bulk` declares a `Content-Length` above 1 MiB
- **THEN** the response is `413` with error code `payload_too_large`, the CSRF and role gates and CSV parsing never run, and no row or audit row is written

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
