## RENAMED Requirements

- FROM: `### Requirement: 72h privacy-flip scheduling is deferred`
- TO: `### Requirement: EXPIRATION schedules and re-activation clears the 72h privacy flip`

## MODIFIED Requirements

### Requirement: EXPIRATION schedules and re-activation clears the 72h privacy flip

On an `EXPIRATION` event for a user whose `private_profile_opt_in = TRUE`, the backend SHALL — within the SAME transaction that downgrades `subscription_status` to `free` — set `users.privacy_flip_scheduled_at = COALESCE(users.privacy_flip_scheduled_at, NOW() + INTERVAL '72 hours')` and emit a `privacy_flip_warning` in-app notification for that user. The `COALESCE` makes scheduling idempotent: a re-delivered or subsequent `EXPIRATION` MUST NOT move an already-set deadline. The `privacy_flip_warning` notification SHALL carry `actor_user_id = NULL`, `target_type = NULL`, `target_id = NULL`, and `body_data` carrying the scheduled deadline as `privacy_flip_scheduled_at` (per the V10 notification catalog); its FCM push SHALL be dispatched after the transaction commits (mirroring the existing `subscription_expired` dispatch path), so a rolled-back transaction pushes nothing.

For a user whose `private_profile_opt_in = FALSE`, the backend SHALL NOT set `privacy_flip_scheduled_at` and SHALL NOT emit a `privacy_flip_warning` notification — the `subscription_status` downgrade to `free` and the `subscription_expired` notification are unchanged for all users.

On an `INITIAL_PURCHASE` or `RENEWAL` (Premium re-activation) event, the backend SHALL clear `users.privacy_flip_scheduled_at = NULL` within the SAME transaction that sets `subscription_status = 'premium_active'`. The clear is unconditional and idempotent, so re-subscribing within the 72h window cancels a pending flip and a re-activation with no pending flip leaves the column `NULL`.

The acting hourly worker that performs the flip once the deadline elapses is specified separately by the `privacy-flip-worker` capability; this requirement covers only the scheduling (on downgrade) and clearing (on re-activation) performed by the webhook handler.

#### Scenario: Expiration schedules a 72h flip for a private user
- **WHEN** an authenticated `EXPIRATION` event is processed for a user with `private_profile_opt_in = TRUE` and `privacy_flip_scheduled_at IS NULL`
- **THEN** that user's `subscription_status` becomes `free` AND `users.privacy_flip_scheduled_at` is set to `NOW() + INTERVAL '72 hours'` AND a `privacy_flip_warning` notification with `body_data.privacy_flip_scheduled_at` set is written AND the `subscription_expired` notification AND `expiration` `subscription_events` row are still recorded

#### Scenario: Expiration does not schedule a flip for a public user
- **WHEN** an authenticated `EXPIRATION` event is processed for a user with `private_profile_opt_in = FALSE`
- **THEN** that user's `subscription_status` becomes `free` AND `users.privacy_flip_scheduled_at` remains `NULL` AND no `privacy_flip_warning` notification is written AND the `subscription_expired` notification is still written

#### Scenario: A re-delivered expiration does not move an already-set deadline
- **WHEN** an authenticated `EXPIRATION` event is processed for a private user who already has a non-NULL `privacy_flip_scheduled_at` from a prior expiration (e.g. a second EXPIRATION redelivery carrying a distinct `revenuecat_event_id`)
- **THEN** `users.privacy_flip_scheduled_at` retains its existing earlier value (the `COALESCE` keeps the first-set deadline; the deadline is never pushed later)

#### Scenario: Re-activation clears a pending privacy flip
- **WHEN** an authenticated `INITIAL_PURCHASE` or `RENEWAL` event is processed for a user who has a non-NULL `privacy_flip_scheduled_at` (re-subscribed inside the 72h window)
- **THEN** that user's `subscription_status` becomes `premium_active` AND `users.privacy_flip_scheduled_at` becomes `NULL`

#### Scenario: Re-activation with no pending flip leaves the column NULL
- **WHEN** an authenticated `RENEWAL` event is processed for a user whose `privacy_flip_scheduled_at` is already `NULL`
- **THEN** that user's `subscription_status` becomes `premium_active` AND `users.privacy_flip_scheduled_at` remains `NULL` (the clear is idempotent)
