## ADDED Requirements

### Requirement: Apple S2S `account-delete` schedules an immediate, non-cancellable deletion and runs it synchronously

When the verified Apple S2S notification at `POST /internal/apple/s2s-notifications` has `type = "account-delete"` (the user deleted their Apple ID entirely), the handler SHALL resolve the Apple `sub` to a user via `sha256Hex(sub)` → `apple_id_hash` → user lookup, INSERT a `deletion_requests` row with `source = 'apple_s2s_account_delete'` and `scheduled_hard_delete_at = NOW()` (no grace), commit that row, and THEN synchronously execute the tombstone + cascade for that row (via the hard-delete worker's per-request executor) before responding `200` to Apple. The synchronous execution MUST reuse the existing hard-delete per-row path (claim `FOR UPDATE SKIP LOCKED`, tombstone, write `deletion_log`, stamp `executed_at`), not a second deletion implementation. The row's `source = 'apple_s2s_account_delete'` MUST be non-cancellable (the existing cancel guard excludes this source).

#### Scenario: account-delete inserts an immediate deletion row and tombstones synchronously
- **WHEN** a verified `account-delete` notification arrives for a `sub` resolving to a live user
- **THEN** a `deletion_requests` row exists with `source = 'apple_s2s_account_delete'` and `scheduled_hard_delete_at` at (approximately) `NOW()` AND the user is tombstoned (a `deletion_log` row written, `executed_at` stamped) before the handler returns `200`

#### Scenario: account-delete rows are not cancellable
- **WHEN** a cancel is attempted against a `source = 'apple_s2s_account_delete'` row (e.g. via `DELETE /api/v1/account/deletion-request`)
- **THEN** the cancel matches no row and the deletion stands (the row is never restored)

### Requirement: A synchronous account-delete failure is backstopped by the daily worker, and Apple still receives `200`

Once the `apple_s2s_account_delete` row is durably committed, the deletion is guaranteed regardless of the synchronous execution outcome. If the synchronous tombstone+cascade throws or is skipped, the handler SHALL still respond `200` to Apple (so Apple does not retry-storm), leaving the row `executed_at IS NULL` and due at `NOW()` so the daily hard-delete worker completes it via `deletion_requests_immediate_idx`. A failure that occurs BEFORE the row is durably committed (e.g. the database is unavailable) SHALL return a non-2xx so Apple retries. No latitude/longitude, raw `sub`, or resolved `user_id` SHALL be written to any log on either path.

#### Scenario: Synchronous execution failure leaves a durable row for the backstop
- **GIVEN** an `account-delete` whose row is committed but whose synchronous executor throws
- **WHEN** the handler completes
- **THEN** it responds `200` to Apple AND the `deletion_requests` row remains present with `executed_at IS NULL` (due now) so the daily worker picks it up via the immediate index

#### Scenario: Pre-persist failure returns non-2xx
- **WHEN** the row cannot be durably persisted (database unavailable) before any response
- **THEN** the handler responds with a non-2xx status so Apple retries the notification

### Requirement: Apple S2S `consent-revoked` schedules a 30-day cancellable deletion and revokes live sessions

When the verified notification has `type = "consent-revoked"` (the user revoked Sign in with Apple), the handler SHALL resolve the `sub` to a user, INSERT a `deletion_requests` row with `source = 'apple_s2s_consent_revoked'` and `scheduled_hard_delete_at = NOW() + INTERVAL '30 days'` (a cancellable grace, mirroring user-initiated deletion), AND bump that user's `token_version` (`token_version = token_version + 1`) to revoke live sessions, then respond `200`. The 30-day row MUST be cancellable during the grace window (the cancel guard does NOT exclude this source). The pending-row idempotency guard MUST prevent a second pending row when one already exists for the user.

#### Scenario: consent-revoked schedules a cancellable 30-day deletion and kicks sessions
- **WHEN** a verified `consent-revoked` notification arrives for a `sub` resolving to a live user with no pending deletion
- **THEN** a `deletion_requests` row exists with `source = 'apple_s2s_consent_revoked'` and `scheduled_hard_delete_at` ≈ `NOW() + 30 days` AND the user's `token_version` is incremented AND the handler returns `200`

#### Scenario: consent-revoked grace is cancellable
- **WHEN** the user (re-authenticated) cancels within the grace window
- **THEN** the `apple_s2s_consent_revoked` row is set `cancelled_at` and the account is restored (the row matches the existing cancel path)

#### Scenario: consent-revoked is idempotent against an existing pending deletion
- **GIVEN** a user who already has a pending un-cancelled, un-executed deletion row
- **WHEN** a `consent-revoked` notification arrives for that user
- **THEN** no second pending `deletion_requests` row is created (the pending-row guard holds)

### Requirement: Unknown or already-deleted Apple `sub` resolves gracefully

When a verified `account-delete` or `consent-revoked` notification carries a `sub` that resolves to no live user (unknown `apple_id_hash`, or an already-tombstoned/deleted account), the handler SHALL respond `200` without creating a `deletion_requests` row and without throwing. This keeps the endpoint idempotent (a re-sent notification for an already-deleted user is a safe no-op) and crash-free.

#### Scenario: account-delete for an unknown sub is a safe no-op
- **WHEN** a verified `account-delete` notification arrives for a `sub` resolving to no live user
- **THEN** the handler responds `200`, no new `deletion_requests` row is created, and no exception propagates

#### Scenario: consent-revoked for an unknown sub is a safe no-op
- **WHEN** a verified `consent-revoked` notification arrives for a `sub` resolving to no live user
- **THEN** the handler responds `200`, no new `deletion_requests` row is created, and no exception propagates

### Requirement: Existing Apple S2S behavior (verification, email-relay, dedup) is preserved

This change SHALL NOT alter the existing Apple S2S pipeline: JWT signature verification against Apple JWKS (`kid` lookup, RSA256, leeway), the fail-closed `aud` allow-list, the base64url payload parse, the `transaction_id`-keyed dedup, or the `email-enabled`/`email-disabled` relay-email flag handling. A duplicate notification (already-seen dedup key) SHALL short-circuit to `200 {"status":"duplicate"}` before any deletion write. The deletion handlers SHALL only run after the same verification + dedup steps that gate the email events.

#### Scenario: Duplicate deletion notification is deduped before any DB write
- **GIVEN** a `consent-revoked` or `account-delete` notification whose dedup key was already seen
- **WHEN** the notification is received again
- **THEN** the handler responds `200 {"status":"duplicate"}` and creates no additional `deletion_requests` row

#### Scenario: Email-relay events and signature verification remain unchanged
- **WHEN** an `email-enabled` / `email-disabled` notification is received, or a notification fails signature/`aud` verification
- **THEN** the email-relay flag update (for valid email events) and the existing rejection responses (for invalid signatures) behave exactly as before this change
