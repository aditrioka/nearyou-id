## ADDED Requirements

### Requirement: Scheduled refresh-token retention sweep

The system SHALL, on each `POST /internal/cleanup` invocation, delete from `refresh_tokens` every row that is either **expired** (`expires_at < NOW() - INTERVAL '1 day'`) or **stale** (`last_used_at < NOW() - INTERVAL '90 days'`), per `docs/05` §112. Rows already marked `revoked_at` SHALL also be deleted when they match a threshold (the sweep is not filtered by `revoked_at`). Rows inside both windows SHALL NOT be deleted.

#### Scenario: An expired refresh token is deleted
- **WHEN** the worker runs AND a `refresh_tokens` row has `expires_at` more than one day in the past
- **THEN** that row is deleted AND it is counted in `refresh_tokens_deleted`

#### Scenario: A long-unused refresh token is deleted
- **WHEN** the worker runs AND a `refresh_tokens` row has `last_used_at` more than 90 days in the past (even if `expires_at` has not yet passed)
- **THEN** that row is deleted AND it is counted in `refresh_tokens_deleted`

#### Scenario: A revoked-and-expired refresh token is deleted
- **WHEN** the worker runs AND a `refresh_tokens` row has `revoked_at` set AND `expires_at` more than one day in the past
- **THEN** that row is deleted (the sweep does not exclude revoked rows)

#### Scenario: A still-valid recently-used refresh token survives
- **WHEN** the worker runs AND a `refresh_tokens` row has `expires_at` in the future AND `last_used_at` within the last 90 days
- **THEN** that row is NOT deleted AND it is NOT counted in `refresh_tokens_deleted`

### Requirement: Scheduled notifications retention purge

The system SHALL, on each `POST /internal/cleanup` invocation, delete from `notifications` every row whose `created_at < NOW() - INTERVAL '90 days'`, per `docs/05` §582. The purge SHALL be type-agnostic — no notification `type` is exempted — and SHALL NOT be filtered by `read_at`.

#### Scenario: A notification older than 90 days is purged
- **WHEN** the worker runs AND a `notifications` row has `created_at` 91 days in the past
- **THEN** that row is deleted AND it is counted in `notifications_deleted`

#### Scenario: A notification within 90 days survives
- **WHEN** the worker runs AND a `notifications` row has `created_at` 89 days in the past
- **THEN** that row is NOT deleted AND it is NOT counted in `notifications_deleted`

#### Scenario: The purge does not exempt any notification type
- **WHEN** the worker runs AND a `notifications` row with `type = 'data_export_ready'` (or any other type) has `created_at` more than 90 days in the past
- **THEN** that row is deleted (no type is special-cased)

### Requirement: Scheduled FCM stale-token sweep

The system SHALL, on each `POST /internal/cleanup` invocation, delete from `user_fcm_tokens` every row whose `last_seen_at < NOW() - INTERVAL '30 days'`, per `docs/05` §1120.

#### Scenario: A token unseen for over 30 days is deleted
- **WHEN** the worker runs AND a `user_fcm_tokens` row has `last_seen_at` 31 days in the past
- **THEN** that row is deleted AND it is counted in `fcm_tokens_deleted`

#### Scenario: A recently-seen token survives
- **WHEN** the worker runs AND a `user_fcm_tokens` row has `last_seen_at` 29 days in the past
- **THEN** that row is NOT deleted AND it is NOT counted in `fcm_tokens_deleted`

### Requirement: The endpoint runs all sweeps per invocation and returns per-sweep counts

The system SHALL expose `POST /internal/cleanup` such that a single invocation runs all in-scope retention sweeps (refresh tokens, notifications, FCM tokens) and responds `200` with a JSON body reporting the deleted-row count for each sweep: `{"refresh_tokens_deleted": <int>, "notifications_deleted": <int>, "fcm_tokens_deleted": <int>}`. Each sweep SHALL be an independent statement so that the failure of one sweep does not roll back rows already reclaimed by a sibling sweep.

#### Scenario: A run reports a count for every sweep
- **WHEN** the worker is invoked AND each table contains some rows past its retention window
- **THEN** the response is `200` with `refresh_tokens_deleted`, `notifications_deleted`, and `fcm_tokens_deleted` each equal to the number of rows that sweep deleted

### Requirement: The worker is idempotent across re-runs

Because each sweep deletes the rows that exceed its threshold, a re-run with no newly-aged rows SHALL match zero rows. A run with nothing to delete SHALL return `200` with every count equal to `0`.

#### Scenario: A second immediate run is a no-op
- **WHEN** the worker is invoked a second time immediately after a run that deleted all currently-eligible rows, with no new rows having crossed any retention threshold in between
- **THEN** the response is `200` with `refresh_tokens_deleted = 0`, `notifications_deleted = 0`, and `fcm_tokens_deleted = 0`

### Requirement: Each run emits one structured INFO log line

The system SHALL emit exactly one structured INFO log line per worker run, carrying the event marker `retention_cleanup`, the per-sweep deleted counts (`refresh_tokens_deleted`, `notifications_deleted`, `fcm_tokens_deleted`), and the run duration in milliseconds.

#### Scenario: A run logs its per-sweep counts and duration
- **WHEN** the worker completes a run
- **THEN** exactly one INFO log line is emitted with `event=retention_cleanup`, the three per-sweep counts, and the run duration in milliseconds

### Requirement: The worker endpoint authenticates via OIDC on its own route subtree

The system SHALL gate `POST /internal/cleanup` with the internal-endpoint OIDC verifier installed on the `/cleanup` route subtree ONLY — never on the shared `/internal` node — so the gate cannot capture sibling internal endpoints that authenticate by a different mechanism (notably `/internal/revenuecat-webhook`, which authenticates via shared-secret Bearer + HMAC, not Google OIDC). A request without a valid OIDC identity token SHALL be rejected `401` and SHALL delete no rows.

#### Scenario: An unauthenticated call is rejected and deletes nothing
- **WHEN** `POST /internal/cleanup` is called without a valid OIDC identity token
- **THEN** the response status is `401` AND no row is deleted from any table

#### Scenario: The worker gate does not capture the sibling vendor-auth webhook
- **WHEN** the cleanup-worker OIDC gate is mounted under `/internal/cleanup` AND a RevenueCat webhook request arrives at `/internal/revenuecat-webhook`
- **THEN** the RevenueCat webhook is authenticated by its own shared-secret Bearer + HMAC path AND is NOT rejected `401` by the cleanup worker's OIDC gate

### Requirement: Handler failures return a classified error without leaking internals

On any exception thrown while processing the request, the endpoint SHALL respond `500` with body `{"error": "<classification>"}` where `<classification>` is one of `timeout`, `connection_refused`, `unknown`. The original exception SHALL be logged with full context at WARN but SHALL NOT appear in the response body.

#### Scenario: A database error is returned as a classified 500
- **WHEN** a sweep's query fails with a database timeout or connection error
- **THEN** the response status is `500` with `error` one of `timeout` / `connection_refused` / `unknown` AND the raw exception message does not appear in the response body

### Requirement: WebAuthn challenge cleanup is out of scope (deferred)

This capability SHALL NOT delete or modify any `admin_webauthn_challenges` row. The weekly WebAuthn-challenge cleanup described in `docs/05` §705 (delete `WHERE expires_at < NOW() - INTERVAL '1 day' AND consumed_at IS NULL`) is deferred to a future change, because the multi-admin WebAuthn period has not begun and nothing writes that table yet. The future change SHALL extend this capability (a new sweep), tracked by a `follow-up` issue.

#### Scenario: The worker leaves WebAuthn challenge rows untouched
- **WHEN** the worker runs AND an `admin_webauthn_challenges` row exists with `expires_at` in the past AND `consumed_at IS NULL`
- **THEN** that row is NOT deleted AND no count for it appears in the response body

### Requirement: Moderation-queue and reports archival is out of scope (deferred)

This capability SHALL NOT delete, archive, or modify any `moderation_queue` or `reports` row. The weekly archival of resolved rows older than one year (`docs/08` Phase 3.5 item 12) is deferred to a future change as a distinct archival concern, tracked by a `follow-up` issue.

#### Scenario: The worker leaves old resolved moderation and report rows untouched
- **WHEN** the worker runs AND a resolved `moderation_queue` row (or a resolved `reports` row) is older than one year
- **THEN** that row is NOT deleted or archived AND no count for it appears in the response body
