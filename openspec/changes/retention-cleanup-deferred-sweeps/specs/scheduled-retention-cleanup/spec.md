# Delta: scheduled-retention-cleanup (retention-cleanup-deferred-sweeps)

## RENAMED Requirements

- FROM: `### Requirement: WebAuthn challenge cleanup is out of scope (deferred)`
- TO: `### Requirement: Scheduled WebAuthn-challenge cleanup sweep`

- FROM: `### Requirement: Moderation-queue and reports archival is out of scope (deferred)`
- TO: `### Requirement: Scheduled moderation-queue and reports retention sweeps`

## MODIFIED Requirements

### Requirement: Scheduled WebAuthn-challenge cleanup sweep

The system SHALL, on each `POST /internal/cleanup` invocation, delete from `admin_webauthn_challenges` every row that is expired and unconsumed — `expires_at < NOW() - INTERVAL '1 day' AND consumed_at IS NULL` — verbatim per `docs/05` §750, returning its reclaimed-row count as `webauthn_challenges_deleted`. Consumed rows (`consumed_at IS NOT NULL`) SHALL NOT be deleted by this sweep (docs/05 prescribes only the unconsumed-expired cleanup; the predicate matches the `admin_webauthn_challenges_cleanup_idx` partial index shape). The sweep SHALL be an independent, idempotent bulk `DELETE` on its own statement, consistent with the sibling sweeps.

#### Scenario: An expired unconsumed challenge is deleted

- **WHEN** the worker runs AND an `admin_webauthn_challenges` row has `expires_at` more than one day in the past AND `consumed_at IS NULL`
- **THEN** that row is deleted AND it is counted in `webauthn_challenges_deleted`

#### Scenario: A recently-expired unconsumed challenge survives the 1-day grace

- **WHEN** the worker runs AND an `admin_webauthn_challenges` row has `expires_at` in the past but less than one day ago AND `consumed_at IS NULL`
- **THEN** that row is NOT deleted AND it is NOT counted in `webauthn_challenges_deleted`

#### Scenario: A consumed challenge is not touched regardless of age

- **WHEN** the worker runs AND an `admin_webauthn_challenges` row has `expires_at` more than one day in the past AND `consumed_at` set
- **THEN** that row is NOT deleted (the sweep is scoped to `consumed_at IS NULL`)

### Requirement: Scheduled moderation-queue and reports retention sweeps

The system SHALL, on each `POST /internal/cleanup` invocation, enforce the 1-year resolved-row retention windows of `docs/06` § Retention Policy ("Moderation queue (resolved rows) | 1 year"; "Reports (resolved) | 1 year") via two independent, idempotent bulk `DELETE` sweeps:

- `moderation_queue`: delete every row with `status = 'resolved' AND resolved_at < NOW() - INTERVAL '1 year'`, counted as `moderation_queue_deleted`.
- `reports`: delete every row with `status IN ('actioned', 'dismissed') AND reviewed_at < NOW() - INTERVAL '1 year'` (the two resolved states of the `reports.status` CHECK — there is no `resolved` value), counted as `reports_deleted`.

The retention clock SHALL start at resolution (`resolved_at` / `reviewed_at`), not submission. Rows with `status = 'pending'` SHALL NOT be deleted regardless of age. A resolved-status row whose resolution timestamp is `NULL` SHALL NOT be deleted (a `NULL` comparison is not true — fail-safe). The sweeps SHALL delete without any copy-to-archive step: the moderation-decision audit trail is owned by `admin_actions_log` under its own 1-year window, and retaining reporter PII past the written window would violate the data-minimization posture this capability enforces.

#### Scenario: A resolved moderation-queue row older than one year is deleted

- **WHEN** the worker runs AND a `moderation_queue` row has `status = 'resolved'` AND `resolved_at` more than one year in the past
- **THEN** that row is deleted AND it is counted in `moderation_queue_deleted`

#### Scenario: A recently-resolved moderation-queue row survives

- **WHEN** the worker runs AND a `moderation_queue` row has `status = 'resolved'` AND `resolved_at` within the last year
- **THEN** that row is NOT deleted AND it is NOT counted in `moderation_queue_deleted`

#### Scenario: A pending moderation-queue row survives regardless of age

- **WHEN** the worker runs AND a `moderation_queue` row has `status = 'pending'` AND `created_at` more than one year in the past
- **THEN** that row is NOT deleted (the sweep is scoped to resolved rows; the retention clock starts at resolution)

#### Scenario: An actioned report older than one year is deleted

- **WHEN** the worker runs AND a `reports` row has `status = 'actioned'` AND `reviewed_at` more than one year in the past
- **THEN** that row is deleted AND it is counted in `reports_deleted`

#### Scenario: A dismissed report older than one year is deleted

- **WHEN** the worker runs AND a `reports` row has `status = 'dismissed'` AND `reviewed_at` more than one year in the past
- **THEN** that row is deleted AND it is counted in `reports_deleted`

#### Scenario: A pending report survives regardless of age

- **WHEN** the worker runs AND a `reports` row has `status = 'pending'` AND `created_at` more than one year in the past
- **THEN** that row is NOT deleted AND it is NOT counted in `reports_deleted`

#### Scenario: No archive copy is made

- **WHEN** the worker deletes a resolved `moderation_queue` or `reports` row past its retention window
- **THEN** the row is removed without being copied to any archive table (the audit trail lives in `admin_actions_log`)

### Requirement: The endpoint runs all sweeps per invocation and returns per-sweep counts

The system SHALL expose `POST /internal/cleanup` such that a single invocation runs all in-scope retention sweeps (refresh tokens, notifications, FCM tokens, login events, WebAuthn challenges, moderation queue, reports) and responds `200` with a JSON body reporting the deleted-row count for each sweep: `{"refresh_tokens_deleted": <int>, "notifications_deleted": <int>, "fcm_tokens_deleted": <int>, "login_events_deleted": <int>, "webauthn_challenges_deleted": <int>, "moderation_queue_deleted": <int>, "reports_deleted": <int>}`. Each sweep SHALL be an independent statement so that the failure of one sweep does not roll back rows already reclaimed by a sibling sweep.

#### Scenario: A run reports a count for every sweep

- **WHEN** the worker is invoked AND each table contains some rows past its retention window
- **THEN** the response is `200` with `refresh_tokens_deleted`, `notifications_deleted`, `fcm_tokens_deleted`, `login_events_deleted`, `webauthn_challenges_deleted`, `moderation_queue_deleted`, and `reports_deleted` each equal to the number of rows that sweep deleted

### Requirement: The worker is idempotent across re-runs

Because each sweep deletes the rows that exceed its threshold, a re-run with no newly-aged rows SHALL match zero rows. A run with nothing to delete SHALL return `200` with every count equal to `0`.

#### Scenario: A second immediate run is a no-op

- **WHEN** the worker is invoked a second time immediately after a run that deleted all currently-eligible rows, with no new rows having crossed any retention threshold in between
- **THEN** the response is `200` with `refresh_tokens_deleted = 0`, `notifications_deleted = 0`, `fcm_tokens_deleted = 0`, `login_events_deleted = 0`, `webauthn_challenges_deleted = 0`, `moderation_queue_deleted = 0`, and `reports_deleted = 0`

### Requirement: Each run emits one structured INFO log line

The system SHALL emit exactly one structured INFO log line per worker run, carrying the event marker `retention_cleanup`, the per-sweep deleted counts (`refresh_tokens_deleted`, `notifications_deleted`, `fcm_tokens_deleted`, `login_events_deleted`, `webauthn_challenges_deleted`, `moderation_queue_deleted`, `reports_deleted`), and the run duration in milliseconds.

#### Scenario: A run logs its per-sweep counts and duration

- **WHEN** the worker completes a run
- **THEN** exactly one INFO log line is emitted with `event=retention_cleanup`, the seven per-sweep counts, and the run duration in milliseconds
