## ADDED Requirements

### Requirement: Authenticated GET /admin/deletion-requests lists accounts pending hard-delete

The system SHALL serve an authenticated admin page at `GET /admin/deletion-requests` (admin board frame 15) rendering a table of every `deletion_requests` row with `executed_at IS NULL AND cancelled_at IS NULL` — the pending-deletion population awaiting the daily hard-delete worker. The query SHALL be served by the existing partial index `deletion_requests_scheduled_idx` (`ON deletion_requests(scheduled_hard_delete_at) WHERE executed_at IS NULL AND cancelled_at IS NULL`) with **no new migration**. Each row SHALL carry the target user's identity (username, with a deep-link to `/admin/users?q=<username>`), the `requested_at` timestamp (UTC), the `scheduled_hard_delete_at` timestamp (UTC), a computed **countdown** to the scheduled deadline (which MAY read as "due now"/past for an immediate-schedule row, e.g. a future `apple_s2s_account_delete` row scheduled at `NOW()` once that downstream source produces rows), and the request `source`. The username SHALL be sourced via a JOIN to `users`. Because `deletion_requests.user_id` references `users(id)` **`ON DELETE CASCADE`**, a non-executed request's `users` row is FK-guaranteed to still exist (the worker hard-deletes the `users` row and the `deletion_requests` row together), so the JOIN always resolves a username — no absent-user fallback is required. The target user is typically **soft-deleted** (`users.deleted_at IS NOT NULL`) during the grace window, so the list SHALL NOT filter on `users.deleted_at` (a `deleted_at`-based filter would hide the intended population). Any authenticated admin role (read need not be `owner`/`admin`) SHALL be able to view the page. The page SHALL render with no rows (an empty-state) when no deletions are pending, never erroring.

#### Scenario: Pending-deletion accounts are listed

- **WHEN** an authenticated admin opens `GET /admin/deletion-requests` and two `deletion_requests` rows have `executed_at IS NULL AND cancelled_at IS NULL`
- **THEN** the response SHALL list both rows with username, `requested_at`, `scheduled_hard_delete_at`, a computed countdown, and `source`

#### Scenario: Executed and cancelled requests are excluded

- **WHEN** the page is rendered AND there exist a `deletion_requests` row with `executed_at IS NOT NULL`, a row with `cancelled_at IS NOT NULL`, and a row with both timestamps NULL (pending)
- **THEN** only the pending row SHALL appear — executed and cancelled rows are excluded

#### Scenario: A soft-deleted target user is still listed with a username

- **WHEN** a pending `deletion_requests` row references a user whose `deleted_at IS NOT NULL` (soft-deleted during the grace window)
- **THEN** the row SHALL still be listed AND the username SHALL be resolved from the still-present `users` row (the list does not filter on `users.deleted_at`)

#### Scenario: An empty queue renders an empty-state

- **WHEN** an authenticated admin opens the page and no `deletion_requests` row is pending
- **THEN** the page SHALL render an empty-state with a zero count and SHALL NOT raise an error

#### Scenario: A non-admin (no admin session) is denied

- **WHEN** an unauthenticated request hits `GET /admin/deletion-requests`
- **THEN** the system SHALL redirect to the admin login (no deletion data is disclosed)

### Requirement: The deletion queue is keyset-paginated, filterable, and summarized by count

The list SHALL be keyset-paginated over the stable ordering key `(scheduled_hard_delete_at ASC, id)` — **soonest-deadline-first**, the operational priority for a deadline-driven queue (the one admin viewer that orders ascending rather than newest-first). It SHALL accept composable, optional filters: `q` (an exact case-insensitive username match OR an exact user UUID match) and `source` (one of the `deletion_requests.source` CHECK values: `user`, `apple_s2s_consent_revoked`, `apple_s2s_account_delete`, `admin`). Filters SHALL compose (applying both narrows to their intersection) and an absent filter SHALL NOT constrain the result. All filter inputs SHALL be bound as parameterized literals (no SQL injection is possible via `q` or `source`), and a blank or whitespace-only `q` SHALL be ignored (treated as absent, not matched as an empty username). The page SHALL render a count summary of the total pending-deletion population (the operational signal — a spike indicates a mass-deletion or webhook problem). The page SHALL provide an HTMX-driven render AND a plain-`GET` (no-JS) fallback that produces the same data.

#### Scenario: The q filter narrows to a single user by username

- **WHEN** the admin loads `GET /admin/deletion-requests?q=rina.sore` and `rina.sore` has a pending deletion
- **THEN** only `rina.sore`'s pending row SHALL be listed

#### Scenario: Filters compose

- **WHEN** the admin loads `GET /admin/deletion-requests?source=user` AND the pending population spans both `user` and `apple_s2s_consent_revoked` sources
- **THEN** only the `source = 'user'` pending rows SHALL be listed

#### Scenario: Rows are ordered soonest-deadline-first

- **WHEN** the pending population contains rows with differing `scheduled_hard_delete_at` values
- **THEN** the rows SHALL be ordered by `scheduled_hard_delete_at` ascending (the nearest deadline first)

#### Scenario: The count summary reflects the full pending population

- **WHEN** 7 deletion requests are pending and the admin views the page with no filter
- **THEN** the count summary SHALL report 7 (independent of the per-page keyset limit)

#### Scenario: Pagination does not drop or duplicate rows

- **WHEN** the pending population exceeds one page and the admin requests the next page via the keyset cursor
- **THEN** the next page SHALL continue from the cursor with no row duplicated or skipped across the page boundary

#### Scenario: Filter inputs are bound as literals and cannot inject SQL

- **WHEN** `q` or `source` contains SQL metacharacters (e.g. `' OR 1=1 --`)
- **THEN** the value SHALL be treated as a literal filter term (matching no row) AND no SQL injection SHALL occur (the `deletion_requests` table is unaffected)

#### Scenario: A blank q filter is ignored

- **WHEN** the page is requested with a blank or whitespace-only `q`
- **THEN** the `q` filter SHALL be treated as absent (the full pending population is listed, not an empty-username match)

### Requirement: The deletion queue enforces identity-only PII discipline and escapes user-controlled output

The surface SHALL expose only identity + deletion-lifecycle fields (username, user id, `requested_at`, `scheduled_hard_delete_at`, `source`, and expedite-bookkeeping metadata). It SHALL NOT render location, email, date of birth, or any other sensitive PII. All user-controlled output (notably usernames and any stored reason text echoed back) SHALL be HTML-escaped in the rendered page to prevent injection.

#### Scenario: No sensitive PII is rendered

- **WHEN** the queue renders a pending-deletion row
- **THEN** the row SHALL contain identity + deletion-lifecycle fields only AND SHALL NOT contain the user's location, email, or date of birth

#### Scenario: A username containing HTML metacharacters is escaped

- **WHEN** a listed user's username (or an echoed reason value) contains HTML metacharacters such as `<`, `>`, or `&`
- **THEN** the rendered output SHALL HTML-escape those characters rather than emitting raw markup

### Requirement: Manual expedite brings the scheduled hard-delete forward and records an immutable audit row

The system SHALL expose `POST /admin/deletion-requests/{id}/expedite` as a support-desk action that accelerates a pending account erasure. A successful expedite SHALL set the target row's `scheduled_hard_delete_at = NOW()` so the **existing** daily hard-delete worker executes the erasure on its next run — the admin route SHALL itself perform **no** tombstone, cascade, or `deletion_log` write (the worker remains the sole executor; the admin route only re-schedules). The mutation SHALL be applied as a single guarded `UPDATE … WHERE id = {id} AND executed_at IS NULL AND cancelled_at IS NULL AND scheduled_hard_delete_at > NOW()` so a row already executed, cancelled, or due is not re-scheduled (see the rejection requirement). A successful expedite SHALL write exactly one immutable `admin_actions_log` row with `action_type = 'deletion_request_expedited'`, `target_type = 'deletion_request'`, `target_id = {id}`, the acting `admin_id`, a **required** `reason` (the request SHALL be rejected if the reason is absent or blank), and `before_state` / `after_state` snapshots in which `scheduled_hard_delete_at` differs (the prior future deadline → `NOW()`) and which carry the affected `user_id`. The write SHALL be append-only — repeat actions write their own rows and the action never UPDATEs or DELETEs an existing `admin_actions_log` row.

#### Scenario: A successful expedite advances the deadline and logs one row

- **WHEN** an `owner`/`admin` submits `POST /admin/deletion-requests/{id}/expedite` for a pending request whose `scheduled_hard_delete_at` is in the future, with a valid CSRF token and a non-blank reason
- **THEN** the row's `scheduled_hard_delete_at` SHALL be set to `NOW()` AND exactly one `admin_actions_log` row SHALL be written with `action_type = 'deletion_request_expedited'`, `target_id = {id}`, and `before_state`/`after_state` snapshots whose `scheduled_hard_delete_at` values differ

#### Scenario: The admin route does not execute the erasure itself

- **WHEN** an expedite succeeds
- **THEN** the request path SHALL NOT tombstone the user, cascade-delete their content, or write `deletion_log` — `executed_at` SHALL remain NULL until the daily worker runs

#### Scenario: An expedite with a blank reason is rejected

- **WHEN** an expedite is submitted with a missing or blank reason
- **THEN** the request SHALL be rejected with no `admin_actions_log` row written and no mutation

#### Scenario: Repeat expedite intent appends a second row only when still actionable

- **WHEN** an admin expedites a pending request that is still in the future (a prior expedite did not occur) twice
- **THEN** each actionable expedite SHALL write its own append-only `admin_actions_log` row AND no prior row SHALL be UPDATEd or DELETEd

### Requirement: Manual expedite is atomic — the deadline advance, the rate-limit count, and the audit row commit or roll back together

A successful expedite's three effects — the guarded `UPDATE deletion_requests` (deadline advance), the trailing-hour rate-limit count read against `admin_actions_log`, and the immutable audit-row insert — SHALL execute within a **single JDBC transaction** so they commit or roll back as a unit. If any step fails (the rate-limit count is at/over cap, the audit insert fails, or the connection drops mid-action), the deadline advance SHALL NOT persist — the `deletion_requests` row SHALL retain its original `scheduled_hard_delete_at`. The system SHALL NOT leave a state in which the deadline was advanced but no audit row exists, nor one in which an audit row exists but the deadline was not advanced. This mirrors the `admin-rejected-identifiers-clear-action` atomicity guarantee (the irreversible mutation and its audit/rate-limit ledger are one transaction).

#### Scenario: A rate-limit rejection rolls back the deadline advance

- **GIVEN** an admin already at the expedite cap for the trailing hour
- **WHEN** that admin attempts an expedite against a pending future request
- **THEN** the request's `scheduled_hard_delete_at` SHALL be unchanged (no deadline advance persists) AND no `admin_actions_log` row SHALL be written

#### Scenario: An audit-write failure rolls back the deadline advance

- **WHEN** the guarded `UPDATE` advances the deadline but the subsequent `admin_actions_log` insert fails within the same transaction
- **THEN** the transaction SHALL roll back AND the request's `scheduled_hard_delete_at` SHALL retain its original future value (no orphaned deadline advance without an audit row)

### Requirement: Manual expedite is role-gated to owner/admin and CSRF-protected

The expedite write SHALL be restricted to admins whose role is `owner` or `admin`; a read-only admin role SHALL NOT be able to expedite. Every expedite request SHALL carry an `X-CSRF-Token` matching the acting session's `admin_sessions.csrf_token_hash`; a missing or mismatched token SHALL return 403, perform no mutation, and write an `admin_csrf_violation` audit entry (no `deletion_request_expedited` row is written for the rejected attempt). CSRF validation SHALL precede the role check, so a request bearing a missing or mismatched token is rejected (and audited) as a CSRF violation regardless of the caller's role.

#### Scenario: A read-only admin role cannot expedite

- **WHEN** an admin whose role is not `owner`/`admin` submits an expedite
- **THEN** the request SHALL be rejected (forbidden) with no `deletion_request_expedited` `admin_actions_log` row written and no mutation

#### Scenario: A CSRF-token mismatch is rejected and audited

- **WHEN** an `owner`/`admin` submits an expedite with a missing or mismatched `X-CSRF-Token`
- **THEN** the system SHALL return 403, perform no mutation, and write an `admin_csrf_violation` audit entry (and no `deletion_request_expedited` row)

#### Scenario: CSRF rejection precedes the role check

- **WHEN** a read-only (non-`owner`/`admin`) admin submits an expedite with a missing or mismatched `X-CSRF-Token`
- **THEN** the request SHALL be rejected as a CSRF violation (403 + `admin_csrf_violation` audit) — CSRF validation runs before the role gate — and no mutation SHALL occur

### Requirement: Manual expedite is rate-limited per admin via a distinct trailing-hour counter

The system SHALL cap expedite actions at **10 per acting admin per trailing one-hour window**. The cap SHALL reuse the `admin-destructive-action-rate-limit` mechanism — the immutable `admin_actions_log` is the rate-limit ledger, the count is taken inside the same JDBC transaction as the gated write (a soft cap with accepted ±1 concurrency tolerance, not a hard authorization boundary), and an at-or-over-cap attempt is surfaced as an inline "quota exceeded" state rather than a 5xx — but SHALL count on a **distinct** key: rows for the acting `admin_id` with `created_at > NOW() - INTERVAL '1 hour'` and `action_type = 'deletion_request_expedited'`. Because expedite is a user-requested accommodation **outside** the user-punitive destructive set, an expedite SHALL NOT count toward, consume, or be blocked by the 20/hour destructive-action budget, and a destructive action SHALL NOT count toward the expedite budget. An expedite rejected at the cap SHALL write no `admin_actions_log` row and perform no mutation.

#### Scenario: The 11th expedite in an hour is rejected without effect

- **GIVEN** an admin with exactly 10 `deletion_request_expedited` rows in the trailing hour
- **WHEN** that admin attempts an 11th expedite with a valid CSRF token and reason
- **THEN** the response SHALL surface a "quota exceeded" state (not a 5xx) AND no new `admin_actions_log` row SHALL be written AND the target row's `scheduled_hard_delete_at` SHALL be unchanged

#### Scenario: Expedite and destructive budgets are independent

- **GIVEN** an admin with 20 destructive-action rows (suspend/ban/etc.) in the trailing hour and 0 `deletion_request_expedited` rows
- **WHEN** that admin submits an expedite with a valid CSRF token and reason
- **THEN** the expedite SHALL succeed (the exhausted destructive budget does not block it) AND it SHALL write one `deletion_request_expedited` row without consuming the destructive budget

#### Scenario: The cap is per-admin

- **GIVEN** admin A with 10 `deletion_request_expedited` rows in the trailing hour and admin B with 0
- **WHEN** admin B submits an expedite
- **THEN** admin B's expedite SHALL succeed (admin A's exhausted expedite quota does not block admin B)

### Requirement: The read surface indicates whether a deletion request was already expedited

For each listed pending row, the page SHALL indicate whether a manual expedite has already been recorded, by surfacing the most recent `admin_actions_log` row with `action_type = 'deletion_request_expedited'` and `target_id = {id}` (via a LEFT JOIN). When present, the indicator SHALL convey that the row was handled (e.g. the acting admin and timestamp) so the support desk does not duplicate the action; when absent, the row SHALL present the expedite control as available. This indicator is read-only and SHALL NOT itself write any audit row.

#### Scenario: A previously-expedited row shows the handled indicator

- **WHEN** a pending row has a prior `deletion_request_expedited` audit row and the page is rendered
- **THEN** that row SHALL display the already-expedited indicator (acting admin + timestamp) sourced from the latest matching `admin_actions_log` row

#### Scenario: A never-expedited row shows the expedite control as available

- **WHEN** a pending row has no `deletion_request_expedited` audit row
- **THEN** that row SHALL present the expedite action as available (no handled indicator) AND rendering the page SHALL write no `admin_actions_log` row

### Requirement: Manual expedite is rejected for a target outside the pending/future population

An expedite SHALL be valid only against a `deletion_requests` row that is `executed_at IS NULL AND cancelled_at IS NULL AND scheduled_hard_delete_at > NOW()` — a genuinely-future pending deletion. The system SHALL reject an expedite whose `{id}` does not resolve to such a row (an unknown id; an already-executed row; a cancelled row; or a row already due/past — any deadline that has arrived, e.g. a future `apple_s2s_account_delete` immediate row scheduled at `NOW()` once that downstream source produces rows, which the worker already catches), performing no mutation and writing no `admin_actions_log` row for the rejected attempt. The guarded `UPDATE … WHERE` matching zero rows IS the rejection (no separate read-then-write race window).

#### Scenario: Expedite of an already-executed or cancelled request is rejected

- **WHEN** an `owner`/`admin` submits an expedite (valid CSRF token + reason) for a request whose `executed_at IS NOT NULL` OR `cancelled_at IS NOT NULL`
- **THEN** the request SHALL be rejected (the guarded UPDATE matches no row) AND no `admin_actions_log` row SHALL be written AND no mutation SHALL occur

#### Scenario: Expedite of an already-due request is rejected

- **WHEN** an expedite targets a pending row whose `scheduled_hard_delete_at <= NOW()` (already due — any row whose deadline has arrived)
- **THEN** the request SHALL be rejected AND no `admin_actions_log` row SHALL be written (there is nothing to accelerate; the worker already catches it)

#### Scenario: Expedite of an unknown id is rejected

- **WHEN** an expedite targets a `{id}` that does not exist
- **THEN** the request SHALL be rejected AND no `admin_actions_log` row SHALL be written AND no mutation SHALL occur
