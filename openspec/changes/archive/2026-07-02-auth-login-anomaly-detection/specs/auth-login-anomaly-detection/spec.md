## ADDED Requirements

### Requirement: OIDC-gated login-anomaly-check worker endpoint

The system SHALL expose a single internal worker endpoint `POST /internal/login-anomaly-check` that runs one login-anomaly detection sweep and returns a summary. The route SHALL be mounted under the existing `route("/internal")` subtree and SHALL install the `internal-endpoint-auth` OIDC gate (`InternalEndpointAuth`) on **its own** sub-route node — never on the shared `/internal` node — mirroring the shipped `RetentionCleanupRoutes` / `privacyFlipWorkerRoute` / `accountHardDeleteWorkerRoute` idiom (the `InternalRoutingIsolationTest`-guarded pattern that keeps the gate from capturing the sibling vendor-auth webhooks). The endpoint SHALL NOT be reachable by a user JWT. A successful sweep SHALL respond `200 OK` with a JSON summary of how many users were evaluated/flagged; a thrown error SHALL respond `500` with a sanitized `{"error": "<classification>"}` body (via the shared `classifyHandlerError`) and SHALL NOT leak the underlying exception or any PII.

#### Scenario: A valid Google OIDC bearer runs the sweep
- **GIVEN** the OIDC verifier accepts a request bearer for the configured internal audience
- **WHEN** `POST /internal/login-anomaly-check` is invoked
- **THEN** the response is `200 OK` with a JSON summary object (e.g. a flagged-user count)

#### Scenario: A request without a valid OIDC bearer is rejected
- **WHEN** `POST /internal/login-anomaly-check` is invoked with no bearer or an invalid/expired OIDC token
- **THEN** the response is `401` AND no detection sweep runs AND no `moderation_queue` row is written

#### Scenario: The OIDC gate does not capture sibling internal webhooks
- **GIVEN** the worker route installs `InternalEndpointAuth` on its own `/login-anomaly-check` node under `/internal`
- **WHEN** a sibling internal route that uses a different auth scheme (e.g. the RevenueCat or Apple S2S webhook) is invoked
- **THEN** that sibling is NOT 401'd by the login-anomaly-check OIDC gate (the gate is scoped to the worker's own subtree, not the shared `/internal` node)

### Requirement: Per-user login-source-spread detection over login_events

The sweep SHALL identify each user who has **strictly more than 5 distinct non-NULL `ip_subnet_24` values** in `login_events` within a **trailing one-hour window** measured from a single evaluation instant captured once per sweep. The window predicate SHALL compare `occurred_at` against a bound timestamp parameter (the evaluation instant minus one hour) supplied as a bound `PreparedStatement` parameter — it SHALL NOT embed `NOW()` (or any volatile expression) inside any index definition (the sweep adds NO index; it reads via the existing `login_events (user_id, occurred_at DESC)` index). Rows whose `ip_subnet_24` is NULL SHALL be excluded from the distinct count (a NULL subnet is not a distinct location). The threshold (`> 5` distinct subnets) and the window length (1 hour) SHALL be named constants/configuration, not scattered literals, so the cap is tunable without touching query construction.

#### Scenario: Six distinct subnets in the window flags the user
- **GIVEN** a user with 6 `login_events` rows in the trailing hour, each carrying a distinct `ip_subnet_24`
- **WHEN** the sweep evaluates that user
- **THEN** the user is flagged as anomalous (6 > 5)

#### Scenario: Exactly five distinct subnets does NOT flag the user (boundary)
- **GIVEN** a user with 5 `login_events` rows in the trailing hour carrying 5 distinct `ip_subnet_24` values (and any number of additional rows that repeat those subnets)
- **WHEN** the sweep evaluates that user
- **THEN** the user is NOT flagged (5 is not strictly greater than 5)

#### Scenario: NULL subnets are excluded from the distinct count
- **GIVEN** a user with 5 rows carrying distinct non-NULL `ip_subnet_24` values plus 3 rows whose `ip_subnet_24` is NULL, all in the trailing hour
- **WHEN** the sweep evaluates that user
- **THEN** the distinct-subnet count is 5 (the NULL rows do not count) AND the user is NOT flagged

#### Scenario: Events outside the trailing window are not counted
- **GIVEN** a user with 6 distinct-subnet rows whose `occurred_at` is older than one hour before the evaluation instant, and 2 distinct-subnet rows inside the window
- **WHEN** the sweep evaluates that user
- **THEN** only the 2 in-window rows are counted AND the user is NOT flagged

### Requirement: Durable, idempotent anomaly signal in moderation_queue

For each flagged user the sweep SHALL record the anomaly as a `moderation_queue` row with `target_type = 'user'`, `target_id = <user_id>`, `trigger = 'anomaly_detection'`, and `status = 'pending'`, reusing the existing table (no new table, no migration — `'anomaly_detection'` and `'user'` are already valid CHECK values). The insert SHALL be idempotent via `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` against the existing `UNIQUE (target_type, target_id, trigger)` constraint, so a user who already has an `anomaly_detection` row for `target_type='user'` is NOT enqueued a second time by a later sweep. The `notes` column MAY carry a **non-PII** summary of the trigger (e.g. the distinct-subnet count and window length) but SHALL NOT contain any IP address, subnet value, identifier hash, or other reversible/PII string.

#### Scenario: A flagged user gets exactly one pending moderation_queue row
- **GIVEN** a user flagged by the sweep with no pre-existing `anomaly_detection` `moderation_queue` row
- **WHEN** the sweep records the signal
- **THEN** exactly one `moderation_queue` row exists for that user with `target_type='user'`, `trigger='anomaly_detection'`, `status='pending'`

#### Scenario: Re-running the sweep does not duplicate the row
- **GIVEN** a user already carrying one `anomaly_detection` `moderation_queue` row (`target_type='user'`) and still flagged on a later sweep
- **WHEN** the sweep runs again
- **THEN** the user still has exactly one such `moderation_queue` row (the `ON CONFLICT DO NOTHING` suppresses the duplicate)

#### Scenario: An already-resolved anomaly row also suppresses re-enqueue
- **GIVEN** a user whose single `anomaly_detection` `moderation_queue` row (`target_type='user'`) has already been `status='resolved'` by a moderator, and who is flagged again on a later sweep
- **WHEN** the sweep runs
- **THEN** no new row is inserted (the `UNIQUE`/`ON CONFLICT` is status-agnostic) AND the existing row's `status` remains `resolved` (re-arming after resolution is out of scope — see design Risks; this is the accepted trade-off, asserted as a guard)

#### Scenario: The notes field carries no PII
- **WHEN** a flagged user's `moderation_queue` row is recorded with a `notes` summary
- **THEN** `notes` contains no IP address, no `ip_subnet_24` value, and no `identifier_hash` (only a non-PII descriptor such as the distinct-subnet count and the window length)

### Requirement: The sweep is fail-soft per user

The detection sweep SHALL isolate per-user failures: if recording the signal for one flagged user throws (e.g. a transient DB error), the sweep SHALL log the failure (without PII) and continue evaluating/recording the remaining flagged users, returning a summary that reflects the users it could process. A single user's failure SHALL NOT abort the whole sweep or cause the endpoint to return `500` when other users were processable.

#### Scenario: One user's insert failure does not abort the sweep
- **GIVEN** three flagged users where the `moderation_queue` insert for the second user throws
- **WHEN** the sweep runs
- **THEN** the first and third users' anomaly rows are still recorded AND the failure for the second is logged (PII-free) AND the endpoint does not fail the whole run on that single error

### Requirement: PII and logging discipline

The worker, its service, and its repository SHALL NOT write any raw IP address, `ip_subnet_24` value, or `identifier_hash` to logs, diagnostics, error responses, or the `moderation_queue.notes` field. Reads of `login_events` are permitted under the security / legitimate-interest basis (not gated by the analytics-consent toggle), but the only values that may leave the worker boundary are non-PII aggregates (counts) and the `user_id` written as `moderation_queue.target_id`.

#### Scenario: No subnet or IP value appears in logs
- **WHEN** the sweep evaluates and flags users (including on the error path)
- **THEN** no log line emitted by the worker/service/repository contains an IP address, a subnet value, or an identifier hash

### Requirement: Detection scope is the login-source-spread leg only; other anomaly metrics are deferred

This capability SHALL implement ONLY the per-user login-source-spread (distinct `ip_subnet_24`) detection leg, and its only output SHALL be the durable `moderation_queue` row. It SHALL NOT implement the other `docs/05-Implementation.md` § Anomaly Detection metrics — the JWT-verify failure-rate spike, the >50 realtime-channel-subscriptions-in-5-minutes signal, and the RevenueCat webhook signature-failure-rate signal — nor the image-delivery >5× baseline / Phase 4 #17 rolling-30-day-baseline anomaly (image upload is feature-flag-gated off until Month 6) nor the deferred username-flagged anomaly-score increment (`docs/05-Implementation.md:295`). It SHALL NOT implement the **Sentry + Slack alert delivery** that `docs/05-Implementation.md:30` frames this metric with (the durable `moderation_queue` row is the MVP review surface; the real-time alert hook is a deferred enhancement). Each deferred metric / delivery mechanism SHALL be tracked by a `follow-up` GitHub issue so a future change has explicit scope to extend this capability.

#### Scenario: The worker computes only the subnet-spread signal and emits only the moderation_queue row
- **WHEN** the login-anomaly-check sweep runs
- **THEN** it evaluates only the distinct-`ip_subnet_24` spread over `login_events` AND its only output is the `moderation_queue` row AND it does NOT compute any JWT-verify-failure-rate, realtime-channel-subscription-count, RevenueCat-signature-failure-rate, image-delivery, or username-flagged anomaly-score signal AND it fires no Sentry/Slack alert

### Requirement: The admin anomaly-review surface is explicitly deferred

A dedicated admin UI for reviewing `target_type='user'` + `trigger='anomaly_detection'` `moderation_queue` rows SHALL NOT ship in this change; it is deferred (per `docs/12-Integration-Contracts.md` §3) to a future admin-surface change governed by the admin mockup board (`docs/11` §3.6), of which the already-tracked `admin-operational-dashboard` "anomaly spike-alert banner" deferred follow-up is the natural home — this change gives that future surface its real data source. This change SHALL produce the durable, queryable `moderation_queue` signal now (the canonical anti-abuse ledger row), and SHALL track the admin review surface with a `follow-up` GitHub issue. This change SHALL NOT add, modify, or render any admin HTML/Pebble/HTMX surface, and SHALL NOT introduce a `reports` row for an anomaly (the signal is a `moderation_queue` row, not a user-submitted report).

#### Scenario: The durable signal is produced but no admin UI is added
- **WHEN** the change is implemented
- **THEN** a flagged user's anomaly is recorded as a `moderation_queue` row queryable by admins AND the change adds no new admin HTML/Pebble route or template AND it writes no `reports` row for the anomaly
