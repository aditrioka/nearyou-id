## RENAMED Requirements

- FROM: `### Requirement: Referral GRANT handling is deferred`
- TO: `### Requirement: Referral GRANT events activate Premium and record a referral-source event`

## MODIFIED Requirements

### Requirement: Referral GRANT events activate Premium and record a referral-source event

The backend SHALL handle a RevenueCat `GRANT` event by recording exactly one `subscription_events` row with `event_type = 'grant'`, `source = 'referral'`, the event's `revenuecat_event_id`, and the available entitlement window (`entitlement_start` / `entitlement_end`), and by setting the resolved user's `users.subscription_status = 'premium_active'` — all within one database transaction. `GRANT` events originate from the referral grant dispatch (the `referral-grant-worker` capability calls the RevenueCat promotional-entitlement API; RevenueCat then echoes a `GRANT` webhook); this requirement covers only the webhook's handling of that echo, not the decision to grant.

Idempotency follows the event-ingestion contract: a re-delivered `GRANT` carrying a `revenuecat_event_id` already present MUST NOT write a second row and MUST NOT re-apply the status transition (the handler returns `200`, the duplicate signal). A `GRANT` event whose RevenueCat app-user identifier maps to no `users.id` MUST be acknowledged `200` with a WARN log and no writes (the orphan-event contract). Because the row carries `source = 'referral'`, these grants are excluded from the paid-only MRR/ARR query (`WHERE source = 'paid' AND event_type IN ('initial_purchase', 'renewal')`).

#### Scenario: GRANT activates Premium and records a referral-source event
- **WHEN** an authenticated `GRANT` event with a previously-unseen `revenuecat_event_id` is processed for a resolvable user
- **THEN** that user's `subscription_status` becomes `premium_active` AND exactly one `subscription_events` row with `event_type = 'grant'`, `source = 'referral'`, and that `revenuecat_event_id` is written in the same transaction

#### Scenario: Re-delivered GRANT is a no-op duplicate
- **WHEN** a `GRANT` event whose `revenuecat_event_id` already exists in `subscription_events` is delivered again
- **THEN** the response status is `200` AND no second `subscription_events` row is written AND the `subscription_status` transition is not re-applied

#### Scenario: GRANT for an unknown user is acknowledged without writes
- **WHEN** an authenticated, well-formed `GRANT` event carries a RevenueCat app-user identifier that maps to no `users.id`
- **THEN** the response status is `200` AND no `subscription_events` row is written AND a WARN log records the orphan event

#### Scenario: A referral grant is excluded from the paid MRR query
- **WHEN** the analytics query `SELECT ... FROM subscription_events WHERE source = 'paid' AND event_type IN ('initial_purchase', 'renewal')` is run after a referral `GRANT` event has been recorded
- **THEN** the `source = 'referral'` grant row is NOT returned (it counts toward engagement, not revenue)
