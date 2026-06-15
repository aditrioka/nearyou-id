## ADDED Requirements

### Requirement: Hourly worker flips elapsed-grace private profiles to public

The system SHALL expose an internal endpoint `POST /internal/privacy-flip-worker` that, on each invocation, applies every privacy flip whose 72h grace window has elapsed. In a single data-modifying transaction it SHALL, for every `users` row where `privacy_flip_scheduled_at IS NOT NULL AND privacy_flip_scheduled_at <= NOW() AND deleted_at IS NULL`, set `private_profile_opt_in = FALSE` and clear `privacy_flip_scheduled_at = NULL`. Rows whose deadline is still in the future, rows with no scheduled flip, and soft-deleted rows (`deleted_at IS NOT NULL`) SHALL NOT be flipped. The endpoint SHALL respond `200` with body `{"flipped_count": N}` where `N` is the number of rows flipped in that run.

#### Scenario: An elapsed grace window is flipped to public
- **WHEN** the worker runs AND a user has `private_profile_opt_in = TRUE` AND `privacy_flip_scheduled_at` set to a past instant AND `deleted_at IS NULL`
- **THEN** that user's `private_profile_opt_in` becomes `FALSE` AND `privacy_flip_scheduled_at` becomes `NULL` AND the user is counted in `flipped_count`

#### Scenario: A still-in-window row is left untouched
- **WHEN** the worker runs AND a user has `private_profile_opt_in = TRUE` AND `privacy_flip_scheduled_at` set to a future instant
- **THEN** that user's `private_profile_opt_in` remains `TRUE` AND `privacy_flip_scheduled_at` is unchanged AND the user is NOT counted in `flipped_count`

#### Scenario: A soft-deleted row with an elapsed deadline is excluded
- **WHEN** the worker runs AND a user has `privacy_flip_scheduled_at` in the past AND `deleted_at IS NOT NULL`
- **THEN** that user is NOT flipped (the `deleted_at IS NULL` guard excludes it; a soft-deleted row carrying an elapsed flip is left for the admin privacy-flip monitor to surface as a stuck-row anomaly)

### Requirement: Each flip writes one immutable system-attributed audit row

The system SHALL insert exactly one `admin_actions_log` row per flipped user, atomically with the flip within the same transaction, so that a failed audit INSERT rolls back the corresponding flip (the user stays private). Each row SHALL be attributed to the seeded `system` sentinel admin actor (the `V18`-seeded `admin_users` row), with `action_type = 'system_privacy_flip_applied'`, `target_type = 'user'`, `target_id` = the flipped user's id, `reason = 'premium_lapsed_grace_elapsed'`, `before_state = {"private_profile_opt_in": true, "privacy_flip_scheduled_at": <prior deadline>}`, and `after_state = {"private_profile_opt_in": false, "privacy_flip_scheduled_at": null}`.

#### Scenario: One audit row per flip, attributed to the system actor
- **WHEN** the worker flips `N` users in a run
- **THEN** exactly `N` `admin_actions_log` rows are written, each with `action_type = 'system_privacy_flip_applied'`, `target_type = 'user'`, the flipped user's id as `target_id`, and `admin_id` equal to the seeded `system` sentinel actor id

#### Scenario: A failed audit write rolls back the flip
- **WHEN** the worker would flip a user BUT the `admin_actions_log` INSERT fails within the transaction
- **THEN** that user's `private_profile_opt_in` remains `TRUE` AND `privacy_flip_scheduled_at` remains set (the flip and its audit row are atomic — neither is applied)

### Requirement: The worker is idempotent across re-runs

Because applying a flip clears `privacy_flip_scheduled_at`, a re-run (a Cloud Scheduler retry or the next hourly tick) SHALL match zero already-flipped rows. A run with no newly-elapsed rows SHALL return `flipped_count = 0` and write zero `admin_actions_log` rows.

#### Scenario: A second run with no new elapsed rows is a no-op
- **WHEN** the worker is invoked a second time immediately after a run that flipped all currently-elapsed rows, with no new deadline having elapsed in between
- **THEN** the response is `200` with `flipped_count = 0` AND no `admin_actions_log` row is written

### Requirement: Each run emits one structured INFO log line

The system SHALL emit exactly one structured INFO log line per worker run, carrying the event marker `privacy_flip_applied`, the `flipped_count`, the flipped user ids capped at a fixed maximum with a truncation flag when more than the cap were flipped, and the run duration in milliseconds. The id list SHALL be capped so a large run cannot produce an unbounded log line (mirroring the `suspension-unban-worker` `MAX_LOGGED_USER_IDS` discipline).

#### Scenario: A run logs its flipped count and capped ids
- **WHEN** the worker flips one or more users
- **THEN** exactly one INFO log line is emitted with `event=privacy_flip_applied`, the correct `flipped_count`, the run duration, and the flipped user ids (truncated with an explicit flag when the number of flipped users exceeds the cap)

### Requirement: The worker endpoint authenticates via OIDC on its own route subtree

The system SHALL gate `POST /internal/privacy-flip-worker` with the internal-endpoint OIDC verifier installed on the `/privacy-flip-worker` route subtree ONLY — never on the shared `/internal` node — so the gate cannot capture sibling internal endpoints that authenticate by a different mechanism (notably the RevenueCat webhook at `/internal/revenuecat-webhook`, which authenticates via shared-secret Bearer + HMAC, not Google OIDC). A request without a valid OIDC identity token SHALL be rejected `401` and SHALL flip no rows.

#### Scenario: An unauthenticated call is rejected
- **WHEN** `POST /internal/privacy-flip-worker` is called without a valid OIDC identity token
- **THEN** the response status is `401` AND no `users` row is flipped AND no `admin_actions_log` row is written

#### Scenario: The worker gate does not capture the sibling vendor-auth webhook
- **WHEN** the privacy-flip-worker OIDC gate is mounted under `/internal/privacy-flip-worker` AND a RevenueCat webhook request arrives at `/internal/revenuecat-webhook`
- **THEN** the RevenueCat webhook is authenticated by its own shared-secret Bearer + HMAC path AND is NOT rejected `401` by the privacy-flip-worker's OIDC gate

### Requirement: Handler failures return a classified error without leaking internals

On any exception thrown while processing the request, the endpoint SHALL respond `500` with body `{"error": "<classification>"}` where `<classification>` is one of `timeout`, `connection_refused`, `unknown`. The original exception SHALL be logged with full context at WARN but SHALL NOT appear in the response body.

#### Scenario: A database error is returned as a classified 500
- **WHEN** the worker's query fails with a database timeout or connection error
- **THEN** the response status is `500` with `error` one of `timeout` / `connection_refused` / `unknown` AND the raw exception message does not appear in the response body
