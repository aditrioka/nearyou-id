## ADDED Requirements

### Requirement: deletion_requests schema

Migration `V23__deletion_requests.sql` SHALL create the `deletion_requests` table **exactly** per the canonical `docs/05-Implementation.md` § Deletion Requests Schema (the migration number MAY be renumbered above V23 at rebase if a concurrent change lands first):

```sql
CREATE TABLE deletion_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    scheduled_hard_delete_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ,
    source VARCHAR(24) NOT NULL CHECK (source IN (
        'user', 'apple_s2s_consent_revoked', 'apple_s2s_account_delete', 'admin'
    ))
);

CREATE INDEX deletion_requests_scheduled_idx
    ON deletion_requests(scheduled_hard_delete_at)
    WHERE executed_at IS NULL AND cancelled_at IS NULL;

CREATE INDEX deletion_requests_immediate_idx
    ON deletion_requests(requested_at)
    WHERE source = 'apple_s2s_account_delete'
      AND executed_at IS NULL
      AND cancelled_at IS NULL;
```

The `source` CHECK MUST enumerate all four canonical values even though this change only PRODUCES `'user'` rows (the other three are reserved for the downstream Apple-S2S and admin paths, which then need no further migration). Both partial-index `WHERE` clauses MUST be `NOW()`-free (the partial-index lint invariant).

#### Scenario: Table and indexes exist after migration
- **WHEN** the migration set is applied and `deletion_requests` is inspected via `information_schema`
- **THEN** the table exists with columns `id, user_id, requested_at, scheduled_hard_delete_at, cancelled_at, executed_at, source` AND both partial indexes `deletion_requests_scheduled_idx` and `deletion_requests_immediate_idx` exist

#### Scenario: source CHECK rejects an unknown value
- **WHEN** an `INSERT INTO deletion_requests (...) VALUES (..., 'gdpr_export')` is attempted
- **THEN** the database rejects it with a CHECK-constraint violation

#### Scenario: source CHECK accepts all four canonical values
- **WHEN** rows are inserted with `source` of each of `'user'`, `'apple_s2s_consent_revoked'`, `'apple_s2s_account_delete'`, `'admin'`
- **THEN** all four inserts succeed

#### Scenario: Partial-index predicates are NOW()-free
- **WHEN** the `CREATE INDEX` statements for both partial indexes are read
- **THEN** neither `WHERE` clause contains `NOW()` (only `executed_at IS NULL` / `cancelled_at IS NULL` / `source = ...` predicates), so Postgres accepts them as immutable partial indexes

### Requirement: User can request account deletion with a 30-day grace

An authenticated endpoint `POST /api/v1/account/deletion-request` (Bearer JWT via `AUTH_PROVIDER_USER`) SHALL insert a `deletion_requests` row for the calling user with `source = 'user'` and `scheduled_hard_delete_at = NOW() + INTERVAL '30 days'`, and respond `200`/`201` with the scheduled hard-delete timestamp. The operation MUST be **idempotent**: when the user already has a row with `cancelled_at IS NULL AND executed_at IS NULL`, the endpoint MUST NOT insert a second row and MUST return the existing schedule. An unauthenticated request MUST return `401`.

#### Scenario: First request schedules deletion 30 days out
- **WHEN** an authenticated user with no pending request calls `POST /api/v1/account/deletion-request`
- **THEN** the response is success with a `scheduled_hard_delete_at` ≈ `NOW() + 30 days` AND exactly one `deletion_requests` row exists for that user with `source = 'user'`, `cancelled_at IS NULL`, `executed_at IS NULL`

#### Scenario: Re-request is idempotent
- **WHEN** a user who already has one pending (un-cancelled, un-executed) request calls the endpoint again
- **THEN** no second row is inserted AND the response returns the same `scheduled_hard_delete_at` as the existing row

#### Scenario: Unauthenticated request rejected
- **WHEN** a caller without a valid Bearer JWT calls `POST /api/v1/account/deletion-request`
- **THEN** the response is `401` and no row is written

### Requirement: A pending deletion is cancellable within the grace window

An authenticated endpoint (e.g. `DELETE /api/v1/account/deletion-request`) SHALL cancel the caller's pending deletion by setting `cancelled_at = NOW()` on the row where `executed_at IS NULL AND cancelled_at IS NULL`, restoring the account. Cancellation MUST be rejected (no-op `409`/`404`) once `executed_at IS NOT NULL` (the hard-delete already ran). A row with `source = 'apple_s2s_account_delete'` MUST NOT be cancellable (Apple-required immediate deletion) — this guard MUST be enforced even though that source is not produced by this change.

#### Scenario: Cancel within grace restores the account
- **WHEN** a user with a pending `source = 'user'` request (still within grace, `executed_at IS NULL`) calls the cancel endpoint
- **THEN** the row's `cancelled_at` is set to ≈ `NOW()` AND a subsequent worker scan skips the row (it is no longer due)

#### Scenario: Cancel after execution is rejected
- **WHEN** a user whose request already has `executed_at IS NOT NULL` calls the cancel endpoint
- **THEN** the response is a non-success (`409`/`404`) AND `cancelled_at` remains NULL (no resurrection of a tombstoned account)

#### Scenario: apple_s2s_account_delete rows are non-cancellable
- **WHEN** a cancel is attempted against a `source = 'apple_s2s_account_delete'` row
- **THEN** the cancel is rejected and `cancelled_at` stays NULL

### Requirement: Deletion status is readable so the client can show the restore deadline

An authenticated read (e.g. `GET /api/v1/account/deletion-request`) SHALL report whether the caller has a pending deletion and, if so, the `scheduled_hard_delete_at` (the restore-by deadline). When no pending request exists it MUST report the no-pending state. It MUST NOT leak any other user's deletion state.

#### Scenario: Status reflects a pending request
- **WHEN** a user with a pending request reads the deletion status
- **THEN** the response indicates a scheduled deletion with the matching `scheduled_hard_delete_at`

#### Scenario: Status when nothing is scheduled
- **WHEN** a user with no pending (un-cancelled, un-executed) request reads the deletion status
- **THEN** the response indicates no scheduled deletion

### Requirement: A pending-deletion account stays fully functional during grace

Requesting deletion SHALL NOT terminate the user's session and SHALL NOT increment `users.token_version`; the account remains fully readable and writable throughout the 30-day grace so the user can seamlessly restore (contrast: suspension is session-terminating). Authenticated routes MUST NOT return `403` solely because a deletion request is pending. (The only user-facing signal is a non-blocking "deletion scheduled, restore by {date}" banner — see `mobile-settings`.)

#### Scenario: Pending-deletion user can still authenticate and write
- **WHEN** a user with a pending `source = 'user'` deletion request (within grace) calls an authenticated write endpoint (e.g. create a post)
- **THEN** the request succeeds (no `403` attributable to the pending deletion)

#### Scenario: Requesting deletion does not bump token_version
- **WHEN** a user calls `POST /api/v1/account/deletion-request`
- **THEN** the user's `token_version` is unchanged AND their existing session/access token remains valid
