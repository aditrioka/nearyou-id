# scheduled-retention-cleanup Specification

## Purpose
The scheduled-retention-cleanup capability is the OIDC-gated internal worker (`POST /internal/cleanup`, Cloud-Scheduler-invoked, gated on its own route subtree) that enforces the written data-retention windows via idempotent bulk `DELETE` sweeps — `refresh_tokens` (expired/stale, `docs/05` §112), `notifications` (90-day purge, §582), `user_fcm_tokens` (30-day stale, §1120), `login_events` (90-day "Session trail", `docs/06` § Retention), `admin_webauthn_challenges` (expired unconsumed, `docs/05` §750), and the 1-year resolved-row retention of `moderation_queue` + `reports` (`docs/06` § Retention Policy; retention-enforcing deletes, no archive table — the moderation-decision audit trail is owned by `admin_actions_log`) — returning per-sweep counts and emitting one structured `retention_cleanup` log line per run. It closes the UU-PDP data-minimization gap (personal data retained past policy), the stale-refresh-token security surface, and unbounded DB growth, and reuses the shipped internal-worker pattern (`suspension-unban-worker` / `privacy-flip-worker`): own-subtree OIDC gate, classified `500`, no per-row audit. It deliberately excludes the FCM on-send `404/410` single-token delete (owned by `fcm-push-dispatch`).
## Requirements
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

#### Scenario: A never-used token is governed only by its expiry (nullable last_used_at)
- **WHEN** the worker runs AND a `refresh_tokens` row has `last_used_at IS NULL` (never used)
- **THEN** the `last_used_at` stale predicate does NOT match it (a `NULL` comparison is not true), so it is deleted only if `expires_at` is more than one day in the past — a never-used row with `expires_at` still in the future survives, and a never-used row whose `expires_at` has passed is deleted via the expiry branch

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

#### Scenario: A recently-resolved report survives
- **WHEN** the worker runs AND a `reports` row has `status = 'actioned'` (or `'dismissed'`) AND `reviewed_at` within the last year
- **THEN** that row is NOT deleted AND it is NOT counted in `reports_deleted`

#### Scenario: A pending report survives regardless of age
- **WHEN** the worker runs AND a `reports` row has `status = 'pending'` AND `created_at` more than one year in the past
- **THEN** that row is NOT deleted AND it is NOT counted in `reports_deleted`

#### Scenario: No archive copy is made
- **WHEN** the worker deletes a resolved `moderation_queue` or `reports` row past its retention window
- **THEN** the row is removed without being copied to any archive table (the audit trail lives in `admin_actions_log`)

### Requirement: Scheduled login-events retention sweep

The system SHALL, on each `POST /internal/cleanup` invocation, delete from `login_events` every row whose `occurred_at < NOW() - INTERVAL '90 days'` — the canonical "Session trail | 90 days auto-purge" window (`docs/06` § Retention Policy). The sweep SHALL be an independent, idempotent bulk `DELETE` returning its reclaimed-row count (`login_events_deleted`), consistent with the other three sweeps, and SHALL run on its own statement so a failure does not roll back a sibling sweep's reclaimed rows.

#### Scenario: A login event older than 90 days is purged
- **WHEN** the worker runs AND a `login_events` row has `occurred_at` 91 days in the past
- **THEN** that row is deleted AND it is counted in `login_events_deleted`

#### Scenario: A login event within 90 days survives
- **WHEN** the worker runs AND a `login_events` row has `occurred_at` 89 days in the past
- **THEN** that row is NOT deleted AND it is NOT counted in `login_events_deleted`

#### Scenario: A login event at exactly 90 days survives (strict-less boundary)
- **WHEN** the worker runs AND a `login_events` row has `occurred_at` exactly 90 days in the past
- **THEN** that row is NOT deleted (the predicate is `occurred_at < NOW() - INTERVAL '90 days'` — strictly less, so the exactly-90-day boundary survives)

