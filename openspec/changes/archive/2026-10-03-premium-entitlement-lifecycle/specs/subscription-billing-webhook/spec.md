## MODIFIED Requirements

### Requirement: Webhook event ingestion is idempotent and recorded at event level

For every authenticated, well-formed event, the backend SHALL record exactly one `subscription_events` row carrying:

- the mapped `event_type` (one of `initial_purchase`, `renewal`, `grant`, `cancellation`, `billing_issue`, `expiration`)
- `source` (`paid` for billing-originated events)
- the `revenuecat_event_id`
- the available entitlement/amount/platform fields

The `revenuecat_event_id` column is `UNIQUE`. A re-delivered event carrying a `revenuecat_event_id` already present MUST NOT create a second row and MUST NOT re-apply its `subscription_status` transition; the handler returns HTTP `200` signalling a duplicate. The event-row write, the `subscription_status` update, and any notification write for a single event MUST occur within one database transaction.

A malformed body MUST be rejected `400` without partial writes. A malformed body is one that cannot be parsed into an envelope, or that lacks a non-blank `type`, `id`, or `app_user_id`. A present, non-blank `app_user_id` that is not a UUID is NOT malformed. For example, a RevenueCat anonymous id `$RCAnonymousID:…` belongs to a purchase made before the mobile client bound its identity. Such an id is an identifier that maps to no `users.id`, so it SHALL be handled as an unknown user: `200` acknowledged (so RevenueCat does not retry), no writes, and a WARN log that records the RevenueCat event id but never the raw anonymous id.

Event-level recording exists because revenue analytics MUST reconstruct transitions from events (a user-level flag loses information). MRR/ARR queries filter `WHERE source = 'paid' AND event_type IN ('initial_purchase', 'renewal')`.

#### Scenario: First delivery records the event and applies state atomically
- **WHEN** an authenticated `INITIAL_PURCHASE` event with a previously-unseen `revenuecat_event_id` is processed
- **THEN** exactly one `subscription_events` row is written with `event_type = 'initial_purchase'`, `source = 'paid'`, and that `revenuecat_event_id` AND the user's `subscription_status` is updated in the same transaction

#### Scenario: Re-delivered event is a no-op duplicate
- **WHEN** an event whose `revenuecat_event_id` already exists in `subscription_events` is delivered again
- **THEN** the response status is `200` indicating a duplicate AND the `subscription_events` table still holds exactly one row for that `revenuecat_event_id` AND the user's `subscription_status` is not re-applied

#### Scenario: Concurrent duplicate deliveries apply the transition at most once
- **WHEN** two requests carrying the same previously-unseen `revenuecat_event_id` are processed concurrently
- **THEN** exactly one `subscription_events` row exists for that `revenuecat_event_id` AND the status transition is applied at most once (the `UNIQUE` constraint with `ON CONFLICT DO NOTHING` serializes the race; the losing writer is treated as a duplicate and does not re-apply the transition)

#### Scenario: Malformed body is rejected without writes
- **WHEN** an authenticated request carries a body that cannot be parsed into a RevenueCat event envelope
- **THEN** the response status is `400` AND no `subscription_events` row is written AND no `subscription_status` is changed

#### Scenario: Event for an unknown user is acknowledged without writes
- **WHEN** an authenticated, well-formed event carries a RevenueCat app-user identifier that maps to no `users.id`
- **THEN** the response status is `200` (acknowledged, so RevenueCat does not retry indefinitely) AND no `subscription_events` row is written AND a WARN log records the orphan event

#### Scenario: Event for a non-UUID (anonymous) app-user id is acknowledged without writes
- **WHEN** an authenticated event carries a non-blank `app_user_id` that is not a UUID (e.g. `$RCAnonymousID:abc123`)
- **THEN** the response status is `200` with status `ignored` (NOT `400`) AND no `subscription_events` row is written AND no `subscription_status` is changed AND a WARN log records the orphan event by `rc_event_id` without logging the raw app-user id

#### Scenario: Unknown event type is ignored
- **WHEN** an authenticated, well-formed event carries an event type the handler does not act on (and is not a deferred type below)
- **THEN** the response status is `200` AND no `subscription_status` is changed AND the event is logged as ignored

#### Scenario: MRR query counts only paid purchases and renewals
- **WHEN** the analytics query `SELECT ... FROM subscription_events WHERE source = 'paid' AND event_type IN ('initial_purchase', 'renewal')` is run after a mix of paid purchase, renewal, billing-issue, and (deferred) grant events
- **THEN** only the `initial_purchase` and `renewal` paid rows are returned (billing-issue, expiration, cancellation, and grant rows are excluded)
