# apple-s2s-deletion-flows Specification

## Purpose
Server-side ingestion of the two Apple Sign-In server-to-server account-deletion events at `POST /internal/apple/s2s-notifications`: `account-delete` (the user deleted their Apple ID — immediate, non-cancellable, synchronously tombstoned before the `200`, daily-worker backstopped) and `consent-revoked` (the user revoked Sign in with Apple — a 30-day cancellable grace row plus an every-receipt `token_version` session-kick). Honoring `account-delete` is an App Store requirement for any app using Sign in with Apple and closes the UU-PDP right-to-erasure gap for orphaned accounts. Activates the `apple_s2s_consent_revoked` / `apple_s2s_account_delete` `deletion_requests` sources that the `account-deletion` capability reserved (V27); the verification pipeline, `aud` allow-list, dedup, and email-relay handling it builds on are specified alongside and remain unchanged.
## Requirements
### Requirement: Apple S2S `account-delete` schedules an immediate, non-cancellable deletion and runs it synchronously

When the verified Apple S2S notification at `POST /internal/apple/s2s-notifications` has `type = "account-delete"` (the user deleted their Apple ID entirely), the handler SHALL resolve the Apple `sub` to a live user via `sha256Hex(sub)` → `apple_id_hash` → user lookup, INSERT a `deletion_requests` row with `source = 'apple_s2s_account_delete'` and `scheduled_hard_delete_at = NOW()` (no grace), commit that row, and THEN synchronously execute the tombstone + cascade for that row (via the hard-delete worker's per-request executor) before responding `200` to Apple. The synchronous execution MUST reuse the existing hard-delete per-row path (claim `FOR UPDATE SKIP LOCKED`, tombstone, write `deletion_log`, stamp `executed_at`), not a second deletion implementation. The row's `source = 'apple_s2s_account_delete'` MUST be non-cancellable (the existing cancel guard excludes this source). The immediate `apple_s2s_account_delete` insert MUST NOT be suppressed by a pending-row idempotency guard: Apple requires immediate deletion, so account-delete SHALL always insert its own immediate row and execute it even when the user already has a pending `'user'` or `'apple_s2s_consent_revoked'` deletion row (the account-delete escalates an existing grace to immediate). The now-moot pending row SHALL be mooted — not re-processed — when it later becomes due: the worker's per-user tombstone guard (`deleted_at IS NULL`) stamps it `executed_at` WITHOUT a second `deletion_log` entry, without re-running the cascade, and without touching the user's original `deleted_at`.

#### Scenario: account-delete inserts an immediate deletion row and tombstones synchronously
- **WHEN** a verified `account-delete` notification arrives for a `sub` resolving to a live user
- **THEN** a `deletion_requests` row exists with `source = 'apple_s2s_account_delete'` and `scheduled_hard_delete_at` at (approximately) `NOW()` AND the user is tombstoned (a `deletion_log` row written, `executed_at` stamped) before the handler returns `200`

#### Scenario: account-delete escalates an existing grace to immediate deletion
- **GIVEN** a user with a pending un-cancelled `'user'` or `'apple_s2s_consent_revoked'` deletion row (30-day grace)
- **WHEN** a verified `account-delete` notification arrives for that user
- **THEN** an `apple_s2s_account_delete` row is inserted at `NOW()` and the user is tombstoned immediately (NOT left in the 30-day grace), honoring Apple's immediate-deletion requirement — AND when the leftover grace row later becomes due, the worker moots it (stamps `executed_at`) with exactly one `deletion_log` row for the user and the original `deleted_at` unchanged

#### Scenario: account-delete rows are not cancellable
- **WHEN** a cancel is attempted against a `source = 'apple_s2s_account_delete'` row (e.g. via `DELETE /api/v1/account/deletion-request`)
- **THEN** the cancel matches no row and the deletion stands (the row is never restored)

### Requirement: A synchronous account-delete failure is backstopped by the daily worker, and Apple still receives `200`

Once the `apple_s2s_account_delete` row is durably committed, the deletion is guaranteed regardless of the synchronous execution outcome. If the synchronous tombstone+cascade throws or is skipped, the handler SHALL still respond `200` to Apple (so Apple does not retry-storm), leaving the row `executed_at IS NULL` and due at `NOW()` so the daily hard-delete worker completes it via `deletion_requests_immediate_idx`. A failure that occurs BEFORE the row is durably committed (e.g. the database is unavailable) SHALL return a non-2xx so Apple retries — and a non-2xx receipt SHALL NOT consume the dedup key (the key is recorded only after a 2xx outcome), so the retry of the same `transaction_id` is processed rather than short-circuited to `duplicate`. No latitude/longitude, raw `sub`, or resolved `user_id` SHALL be written to any log on either path.

#### Scenario: Synchronous execution failure leaves a durable row for the backstop
- **GIVEN** an `account-delete` whose row is committed but whose synchronous executor throws
- **WHEN** the handler completes
- **THEN** it responds `200` to Apple AND the `deletion_requests` row remains present with `executed_at IS NULL` (due now) so the daily worker picks it up via the immediate index

#### Scenario: Pre-persist failure returns non-2xx
- **WHEN** the row cannot be durably persisted (database unavailable) before any response
- **THEN** the handler responds with a non-2xx status so Apple retries the notification

#### Scenario: The retry of a failed receipt is processed, not deduplicated
- **GIVEN** an `account-delete` whose first receipt failed pre-persist with a non-2xx
- **WHEN** Apple retries the notification with the same `transaction_id`
- **THEN** the retry is processed (row inserted, user tombstoned, `200`) — the failed receipt did not consume the dedup key

### Requirement: Apple S2S `consent-revoked` schedules a 30-day cancellable deletion and revokes live sessions

When the verified notification has `type = "consent-revoked"` (the user revoked Sign in with Apple), the handler SHALL resolve the `sub` to a live user, INSERT a `deletion_requests` row with `source = 'apple_s2s_consent_revoked'` and `scheduled_hard_delete_at = NOW() + INTERVAL '30 days'` (a cancellable grace, mirroring user-initiated deletion), revoke that user's live sessions by bumping `token_version` (`token_version = token_version + 1`, reusing the existing session-kick write), then respond `200`. The session-kick is a **separate** write from the insert — the two are NOT claimed to be a single atomic transaction (each is individually safe-on-retry); the `token_version` bump SHALL fire on **every** `consent-revoked` receipt for a live user, including when the deletion insert is a no-op because a pending row already exists. The 30-day row MUST be cancellable during the grace window (the cancel guard does NOT exclude this source). The deletion insert's pending-row idempotency guard MUST prevent a second pending `deletion_requests` row when one already exists for the user.

#### Scenario: consent-revoked schedules a cancellable 30-day deletion and kicks sessions
- **WHEN** a verified `consent-revoked` notification arrives for a `sub` resolving to a live user with no pending deletion
- **THEN** a `deletion_requests` row exists with `source = 'apple_s2s_consent_revoked'` and `scheduled_hard_delete_at` ≈ `NOW() + 30 days` AND the user's `token_version` is incremented AND the handler returns `200`

#### Scenario: consent-revoked grace is cancellable
- **WHEN** the user (re-authenticated) cancels within the grace window
- **THEN** the `apple_s2s_consent_revoked` row is set `cancelled_at` and the account is restored (the row matches the existing cancel path)

#### Scenario: consent-revoked is idempotent against an existing pending deletion but still revokes sessions
- **GIVEN** a user who already has a pending un-cancelled, un-executed deletion row and a known `token_version`
- **WHEN** a `consent-revoked` notification arrives for that user
- **THEN** no second pending `deletion_requests` row is created (the pending-row guard holds) AND the user's `token_version` is still incremented (sessions kicked on every receipt)

### Requirement: Missing or unresolvable Apple `sub` is handled without a write

A verified `account-delete` or `consent-revoked` notification with a **null/absent `sub`** SHALL be rejected with `400` (mirroring the existing `email-enabled`/`email-disabled` missing-`sub` handling), since the user cannot be identified. A verified notification whose `sub` resolves to **no live user** — unknown `apple_id_hash`, or a row whose `deleted_at IS NOT NULL` (already-tombstoned) — SHALL respond `200` without creating a `deletion_requests` row and without throwing. The live-user determination MUST apply a `deleted_at IS NULL` guard at the resolution call site (the `apple_id_hash` lookup does not itself filter tombstoned rows), so a re-sent notification for an already-deleted user is a safe no-op rather than a second row. This keeps the endpoint idempotent and crash-free.

#### Scenario: deletion event with a missing sub returns 400
- **WHEN** a verified `account-delete` or `consent-revoked` notification has no `sub`
- **THEN** the handler responds `400` and creates no `deletion_requests` row

#### Scenario: account-delete for an unknown/already-deleted sub is a safe no-op
- **WHEN** a verified `account-delete` notification arrives for a `sub` resolving to no live user (unknown hash, or `deleted_at IS NOT NULL`)
- **THEN** the handler responds `200`, no new `deletion_requests` row is created, and no exception propagates

#### Scenario: consent-revoked for an unknown/already-deleted sub is a safe no-op
- **WHEN** a verified `consent-revoked` notification arrives for a `sub` resolving to no live user (unknown hash, or `deleted_at IS NOT NULL`)
- **THEN** the handler responds `200`, no new `deletion_requests` row is created, no `token_version` is bumped, and no exception propagates

### Requirement: Apple deletion-event handling logs no PII

Neither deletion handler SHALL write the raw Apple `sub`, the resolved `user_id`/`apple_id_hash`, or any user coordinate to logs or diagnostics on any path (success, no-user no-op, dedup, or synchronous-execution failure). Failure logging SHALL be limited to the event type and an error class, mirroring the existing hard-delete worker logging discipline.

#### Scenario: A synchronous-execution failure logs no PII
- **WHEN** the synchronous `account-delete` executor throws and the failure is logged
- **THEN** the log entry contains no raw `sub`, no `user_id`/`apple_id_hash`, and no coordinate (only the event type and an error class)

### Requirement: Existing Apple S2S behavior (verification, email-relay, dedup) is preserved

This change SHALL NOT alter the existing Apple S2S pipeline: JWT signature verification against Apple JWKS (`kid` lookup, RSA256, leeway), the fail-closed `aud` allow-list, the base64url payload parse, the `transaction_id`-keyed dedup, or the `email-enabled`/`email-disabled` relay-email flag handling. A duplicate notification (already-seen dedup key) SHALL short-circuit to `200 {"status":"duplicate"}` before any deletion write. The deletion handlers SHALL only run after the same verification + dedup steps that gate the email events. One dedup refinement (review-driven): the key SHALL be recorded only after a 2xx outcome — previously check-and-record on receipt — so a non-2xx receipt (persist failure, missing `sub`) stays retryable; the duplicate short-circuit for successfully-processed notifications is unchanged.

#### Scenario: Duplicate deletion notification is deduped before any DB write
- **GIVEN** a `consent-revoked` or `account-delete` notification whose dedup key was already seen
- **WHEN** the notification is received again
- **THEN** the handler responds `200 {"status":"duplicate"}` and creates no additional `deletion_requests` row

#### Scenario: Email-relay events and signature verification remain unchanged
- **WHEN** an `email-enabled` / `email-disabled` notification is received, or a notification fails signature/`aud` verification
- **THEN** the email-relay flag update (for valid email events) and the existing rejection responses (for invalid signatures) behave exactly as before this change

