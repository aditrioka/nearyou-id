## ADDED Requirements

### Requirement: Admin can list data-export requests

The admin panel SHALL serve `GET /admin/data-exports` — a keyset-paginated, newest-`requested_at`-first list of `data_export_requests` rows joined to `users` for the requester's username. Each row SHALL surface, per admin mockup frame 16: the requester **username** (deep-linking to `/admin/users?q=<username>`), the **requested-at** timestamp in UTC, the request **status**, and a **delivered-via** indicator. Status SHALL be surfaced with the labels `pending → QUEUED`, `processing → RUNNING`, `ready → DELIVERED`, `expired → EXPIRED`, `failed → FAILED`; the delivered-via cell SHALL show "`data_export_ready` notification + email" only for a `ready`/`DELIVERED` row and `—` otherwise. The read SHALL be served by the existing `data_export_requests` indexes (no new index, no Flyway migration). The endpoint SHALL render via HTMX with a plain-`GET` fallback that returns a complete HTML page, and SHALL HTML-escape all user-derived text. Any authenticated admin role MAY read.

#### Scenario: Authenticated admin sees the paginated queue newest-first
- **WHEN** an authenticated admin opens `GET /admin/data-exports` and three requests exist with distinct `requested_at` values
- **THEN** the page lists all three ordered newest-`requested_at`-first, each showing username, requested-at (UTC), and the mapped status label

#### Scenario: Status labels map from the five DB statuses
- **WHEN** rows exist with `status` `pending`, `processing`, `ready`, `expired`, `failed`
- **THEN** they render as `QUEUED`, `RUNNING`, `DELIVERED`, `EXPIRED`, `FAILED` respectively AND only the `ready` row's delivered-via cell shows the notification+email indicator

#### Scenario: Username deep-links to the user lookup
- **WHEN** a row is rendered for a request by user `sari.menyapa`
- **THEN** the username is a link to `/admin/users?q=sari.menyapa`

#### Scenario: Plain-GET fallback renders without HTMX
- **WHEN** `GET /admin/data-exports` is requested without the HTMX request header
- **THEN** a complete standalone HTML page is returned (not an HTMX fragment) listing the same rows

#### Scenario: Unauthenticated request is rejected by the admin gate
- **WHEN** `GET /admin/data-exports` is requested without a valid `__Host-admin_session`
- **THEN** the request is denied by the admin authentication gate (redirect to login / 401), exposing no queue data

### Requirement: The admin panel never exposes export contents

The Data Export Queue SHALL surface request **status and requester identity only** — it MUST NOT render the export archive contents, the `r2_object_key`, or the signed download URL (the archive is the requester's own personal data, delivered only to them via a short-lived signed link). The read projection MUST exclude object-key / signed-URL values from any rendered output.

#### Scenario: A delivered row shows status but no download link or object key
- **WHEN** a `ready` request with a populated `r2_object_key` and `download_expires_at` is rendered
- **THEN** the row shows `DELIVERED` + the notification+email indicator AND contains no download URL, signed link, or `r2_object_key` value anywhere in the response

### Requirement: Admin can filter the queue by status and user

The list SHALL accept composable parameterized filters: `status` (one of the five DB statuses) and `q` (a username or user UUID resolved against `users`). Filters SHALL compose with AND semantics, and the page SHALL show an in-window count summary reflecting the applied filters. Filtering MUST use SQL predicates over the indexed columns (no post-fetch in-memory scan that would defeat pagination).

#### Scenario: status filter narrows to one state
- **WHEN** an admin requests `GET /admin/data-exports?status=failed`
- **THEN** only `failed`/`FAILED` rows are listed and the count summary reflects that filtered total

#### Scenario: q filter resolves a username or UUID
- **WHEN** an admin requests `GET /admin/data-exports?q=jaka_kelana`
- **THEN** only requests by that user are listed

#### Scenario: filters compose
- **WHEN** an admin requests `GET /admin/data-exports?status=pending&q=jaka_kelana`
- **THEN** only that user's `pending` requests are listed (AND semantics)

### Requirement: Admin can trigger a re-run of a stalled or failed export

The admin panel SHALL serve `POST /admin/data-exports/{id}/trigger` that re-runs the export for a stalled or failed request **through the existing producer pipeline** (the `account-data-export` single-request processing seam) — it MUST NOT implement a second, divergent export path. The action SHALL be gated in this order: (1) CSRF token verified against the session (mismatch → `403` + an `admin_csrf_violation` audit entry, no mutation); (2) role `IN ('owner','admin')`; (3) a non-empty **reason** is required; (4) a **distinct** rate-limit bucket of **10 triggers per admin per hour**, independent of the 20/hour destructive-action budget (sourced from the audit-trail-as-ledger counter). On success it SHALL re-enqueue the row to `pending` where needed and drive the single-request pipeline, and write **exactly one** immutable `admin_actions_log` row with `action_type = 'data_export_triggered'` carrying before/after status snapshots — the audit write and the state transition in one transaction. The UI control SHALL be `hx-confirm`-guarded.

#### Scenario: Owner/admin re-runs a failed export
- **GIVEN** a request in `status = 'failed'`
- **WHEN** an `owner` or `admin` submits the trigger with a valid CSRF token and a reason
- **THEN** the row is re-enqueued and processed through the existing pipeline, reaching `ready` (object key + `data_export_ready` notification produced) AND exactly one `admin_actions_log` row with `action_type = 'data_export_triggered'` and before-status `failed` is written

#### Scenario: Trigger on a stuck QUEUED row runs it immediately
- **GIVEN** a request stuck in `status = 'pending'` (the scheduler has not processed it)
- **WHEN** an `owner`/`admin` submits the trigger with valid CSRF + reason
- **THEN** the request is processed immediately through the pipeline (not left waiting on the next scheduled run) AND one `data_export_triggered` audit row is written

#### Scenario: Missing or mismatched CSRF token is rejected before any work
- **WHEN** a trigger is submitted without a valid `X-CSRF-Token`
- **THEN** the response is `403`, an `admin_csrf_violation` audit row is written, and the export row is not mutated and not processed

#### Scenario: A read-only admin role cannot trigger
- **WHEN** an authenticated admin whose role is not `owner`/`admin` submits a trigger with a valid CSRF token
- **THEN** the action is rejected with no mutation and no export processing (CSRF is checked first, then the role gate denies)

#### Scenario: Missing reason is rejected
- **WHEN** an `owner`/`admin` submits a trigger with a valid CSRF token but no reason
- **THEN** the action is rejected, the export row is not mutated, and no `admin_actions_log` row is written

#### Scenario: Distinct rate limit, independent of the destructive budget
- **WHEN** an admin submits an 11th trigger within one hour
- **THEN** the 11th is rejected with a rate-limit response and no mutation AND data-export triggers do not consume the separate 20/hour destructive-action budget

### Requirement: Trigger on a non-triggerable request is a benign idempotent no-op

A trigger targeting a request that is `processing` (in-flight) or `ready` (download link still valid) SHALL be a benign no-op: no state mutation, no second export job, and **no** `admin_actions_log` row. Re-enqueueing to `pending` SHALL honor the existing one-active partial UNIQUE index `data_export_requests_one_active_idx` (`pending|processing`): when the target user already has another active request, the unique-violation SHALL be caught and mapped to an "already active, no-op" outcome (no exception surfaced to the operator, idempotent), mirroring the hard-delete-queue rejection of ineligible targets. A trigger for an unknown `id` SHALL be a benign no-op (no audit row).

#### Scenario: Trigger on a processing row is a no-op
- **WHEN** a trigger targets a request in `status = 'processing'`
- **THEN** nothing is mutated, no second job runs, and no audit row is written

#### Scenario: Trigger on a ready row with a valid link is a no-op
- **WHEN** a trigger targets a `ready` request whose download link has not expired
- **THEN** nothing is mutated and no audit row is written

#### Scenario: Re-enqueue that would collide with another active request is mapped to no-op
- **GIVEN** a `failed` request for a user who already has a separate `pending` request
- **WHEN** an `owner`/`admin` triggers the failed one
- **THEN** the one-active unique-index violation is caught and mapped to a benign "already active" no-op (no unhandled exception, no partial mutation)
