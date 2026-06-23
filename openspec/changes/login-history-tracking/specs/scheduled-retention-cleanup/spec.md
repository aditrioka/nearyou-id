## ADDED Requirements

### Requirement: Scheduled login-events retention sweep

The system SHALL, on each `POST /internal/cleanup` invocation, delete from `login_events` every row whose `occurred_at < NOW() - INTERVAL '90 days'` — the canonical "Session trail | 90 days auto-purge" window (`docs/06` § Retention Policy). The sweep SHALL be an independent, idempotent bulk `DELETE` returning its reclaimed-row count (`login_events_deleted`), consistent with the other three sweeps, and SHALL run on its own statement so a failure does not roll back a sibling sweep's reclaimed rows.

#### Scenario: A login event older than 90 days is purged
- **WHEN** the worker runs AND a `login_events` row has `occurred_at` 91 days in the past
- **THEN** that row is deleted AND it is counted in `login_events_deleted`

#### Scenario: A login event within 90 days survives
- **WHEN** the worker runs AND a `login_events` row has `occurred_at` 89 days in the past
- **THEN** that row is NOT deleted AND it is NOT counted in `login_events_deleted`

## MODIFIED Requirements

### Requirement: The endpoint runs all sweeps per invocation and returns per-sweep counts

The system SHALL expose `POST /internal/cleanup` such that a single invocation runs all in-scope retention sweeps (refresh tokens, notifications, FCM tokens, login events) and responds `200` with a JSON body reporting the deleted-row count for each sweep: `{"refresh_tokens_deleted": <int>, "notifications_deleted": <int>, "fcm_tokens_deleted": <int>, "login_events_deleted": <int>}`. Each sweep SHALL be an independent statement so that the failure of one sweep does not roll back rows already reclaimed by a sibling sweep.

#### Scenario: A run reports a count for every sweep
- **WHEN** the worker is invoked AND each table contains some rows past its retention window
- **THEN** the response is `200` with `refresh_tokens_deleted`, `notifications_deleted`, `fcm_tokens_deleted`, and `login_events_deleted` each equal to the number of rows that sweep deleted

### Requirement: The worker is idempotent across re-runs

Because each sweep deletes the rows that exceed its threshold, a re-run with no newly-aged rows SHALL match zero rows. A run with nothing to delete SHALL return `200` with every count equal to `0`.

#### Scenario: A second immediate run is a no-op
- **WHEN** the worker is invoked a second time immediately after a run that deleted all currently-eligible rows, with no new rows having crossed any retention threshold in between
- **THEN** the response is `200` with `refresh_tokens_deleted = 0`, `notifications_deleted = 0`, `fcm_tokens_deleted = 0`, and `login_events_deleted = 0`
