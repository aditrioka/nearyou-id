# subscription-billing-webhook Specification

## Purpose
The subscription-billing-webhook capability turns RevenueCat billing events into the authoritative writer of `users.subscription_status`. It exposes the vendor-authenticated (`Authorization: Bearer` + optional HMAC) `POST /internal/revenuecat-webhook` endpoint — mounted outside the OIDC `/internal` gate — which idempotently records every event in the `subscription_events` ledger (keyed by `revenuecat_event_id UNIQUE`) and drives the paid-path 3-state status machine (`free` / `premium_active` / `premium_billing_retry`), writing the billing-issue and expiry notifications. Premium-gated features (e.g. premium search) read the status this capability owns. On `EXPIRATION` it also schedules the 72h privacy flip for private profiles (and clears it on re-activation) — the acting worker is owned by the `privacy-flip-worker` capability. Referral `GRANT` entitlement stacking, the time-based grace-elapse worker, the cancellation confirmation notification, and mandatory production signing remain deferred to their owning changes.
## Requirements
### Requirement: RevenueCat webhook authenticates via shared-secret Bearer token and HMAC signature

The backend SHALL expose `POST /internal/revenuecat-webhook`, authenticated by its own vendor credentials and NOT by the OIDC `InternalEndpointAuth` plugin (it is a vendor-webhook opt-out per the `internal-endpoint-auth` capability). Every request MUST present an `Authorization: Bearer <token>` header whose value equals the `revenuecat-webhook-secret`, compared with a constant-time comparison. When an `X-RevenueCat-Signature` header is present, its HMAC-SHA256 over the raw request body MUST verify against the `revenuecat-webhook-hmac-secret`. Both secrets MUST be read through the `secretKey(env, name)` helper.

Both secret **slot names** MUST be resolved through the `secretKey(env, name)` env-namespacing helper and their **values** fetched via the secret resolver — a direct secret-name read is forbidden (secrets invariant).

A request failing any auth check MUST be rejected with HTTP `401 Unauthorized` before any handler logic runs. The `401` response body MUST NOT echo the offending token, the configured secret, the computed/received signature, or any verifier exception detail. Each auth failure MUST be logged at WARN as a security event so the operator's webhook-auth-failure anomaly signal can observe it (a missing/bad **Bearer** and a bad **HMAC signature** are logged distinguishably, so the "signature fail rate" alert keys on genuine signature failures rather than ordinary unauthenticated internet scans). A valid OIDC bearer token alone MUST NOT admit the request.

#### Scenario: Missing Authorization header is rejected
- **WHEN** a request to `POST /internal/revenuecat-webhook` is sent with no `Authorization` header
- **THEN** the response status is `401 Unauthorized` AND no event is recorded AND no `subscription_status` is changed

#### Scenario: Wrong bearer token is rejected
- **WHEN** a request presents `Authorization: Bearer <value>` whose value does not equal the configured `revenuecat-webhook-secret`
- **THEN** the response status is `401 Unauthorized` AND a WARN security-event log entry is written that does NOT contain the offending token

#### Scenario: HMAC signature mismatch is rejected
- **WHEN** a request presents a valid bearer token AND an `X-RevenueCat-Signature` header whose HMAC-SHA256 over the raw body does not verify against `revenuecat-webhook-hmac-secret`
- **THEN** the response status is `401 Unauthorized` AND no event is recorded

#### Scenario: A valid OIDC token does not bypass vendor auth
- **WHEN** a request to `POST /internal/revenuecat-webhook` presents a valid Google OIDC bearer token but no RevenueCat shared-secret Bearer / HMAC headers
- **THEN** the response status is `401 Unauthorized` from the vendor auth (the route does NOT inherit OIDC verification)

#### Scenario: Valid vendor credentials admit the request
- **WHEN** a request presents the correct `revenuecat-webhook-secret` bearer token AND (when signature checking is enabled) a valid `X-RevenueCat-Signature`
- **THEN** the request is admitted to the handler AND processed per the event-type requirements below

### Requirement: Mandatory production webhook signing is deferred

In this change, HMAC signature verification is conditional — it applies only when the `X-RevenueCat-Signature` header is present — so a request with a valid bearer token and no signature header is admitted (Bearer is the always-on gate; HMAC is defense-in-depth that activates once the operator enables signing in the RevenueCat dashboard). This change SHALL NOT reject a missing-signature request, including in production. Making signing mandatory in production (rejecting any unsigned request when the environment is production) is deferred to a future hardening change, gated on the operator confirming dashboard signing is enabled — enforcing it prematurely would reject every live billing webhook. Replay of a captured `(body, signature)` pair is bounded by the `revenuecat_event_id` idempotency below (a replayed event is a no-op duplicate), not by an auth-layer freshness check; the future hardening change will MODIFY this requirement to add the production-mandatory-signing rejection.

#### Scenario: An unsigned request is accepted on a valid Bearer token alone
- **WHEN** a request presents the correct bearer token AND no `X-RevenueCat-Signature` header
- **THEN** the request is admitted (this change does not require a signature; mandatory production signing is deferred to a hardening change)

### Requirement: Webhook event ingestion is idempotent and recorded at event level

For every authenticated, well-formed event, the backend SHALL record exactly one `subscription_events` row carrying the mapped `event_type` (one of `initial_purchase`, `renewal`, `grant`, `cancellation`, `billing_issue`, `expiration`), `source` (`paid` for billing-originated events), the `revenuecat_event_id`, and the available entitlement/amount/platform fields. The `revenuecat_event_id` column is `UNIQUE`. A re-delivered event carrying a `revenuecat_event_id` already present MUST NOT create a second row and MUST NOT re-apply its `subscription_status` transition; the handler returns HTTP `200` signalling a duplicate. The event-row write, the `subscription_status` update, and any notification write for a single event MUST occur within one database transaction. A malformed body MUST be rejected `400` without partial writes.

Event-level recording exists because revenue analytics MUST reconstruct transitions from events (a user-level flag loses information); MRR/ARR queries filter `WHERE source = 'paid' AND event_type IN ('initial_purchase', 'renewal')`.

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

#### Scenario: Unknown event type is ignored
- **WHEN** an authenticated, well-formed event carries an event type the handler does not act on (and is not a deferred type below)
- **THEN** the response status is `200` AND no `subscription_status` is changed AND the event is logged as ignored

#### Scenario: MRR query counts only paid purchases and renewals
- **WHEN** the analytics query `SELECT ... FROM subscription_events WHERE source = 'paid' AND event_type IN ('initial_purchase', 'renewal')` is run after a mix of paid purchase, renewal, billing-issue, and (deferred) grant events
- **THEN** only the `initial_purchase` and `renewal` paid rows are returned (billing-issue, expiration, cancellation, and grant rows are excluded)

### Requirement: Purchase and renewal events activate Premium

The backend SHALL set `users.subscription_status = 'premium_active'` when it processes an `INITIAL_PURCHASE` or `RENEWAL` event for that user. The user is resolved from the event's RevenueCat app-user identifier, which is the `users.id` UUID.

#### Scenario: Initial purchase activates Premium
- **WHEN** an authenticated `INITIAL_PURCHASE` event is processed for a `free` user
- **THEN** that user's `subscription_status` becomes `premium_active` AND a `subscription_events` row with `event_type = 'initial_purchase'`, `source = 'paid'` is recorded

#### Scenario: Renewal keeps the user Premium
- **WHEN** an authenticated `RENEWAL` event is processed for a `premium_active` user
- **THEN** that user's `subscription_status` remains `premium_active` AND a `subscription_events` row with `event_type = 'renewal'`, `source = 'paid'` is recorded

#### Scenario: Renewal after a billing issue heals the user back to active
- **WHEN** an authenticated `RENEWAL` event is processed for a user currently in `premium_billing_retry` (a prior billing issue that has since cleared)
- **THEN** that user's `subscription_status` becomes `premium_active` (the renewal transition is unconditional — it does not require the user to already be `premium_active`) AND a `subscription_events` row with `event_type = 'renewal'`, `source = 'paid'` is recorded

### Requirement: Billing-issue events enter the grace state without revoking access

The backend SHALL set `users.subscription_status = 'premium_billing_retry'` when it processes a `BILLING_ISSUE` event. Premium access MUST remain effective in this state (`premium_billing_retry` is an effective-Premium state alongside `premium_active`). The handler SHALL write a `subscription_billing_issue` notification for the user whose `body_data` carries the grace-window end timestamp (`grace_end_at`, 7 days from the event), and record a `billing_issue` event.

#### Scenario: Billing issue moves the user to retry while keeping access
- **WHEN** an authenticated `BILLING_ISSUE` event is processed for a `premium_active` user
- **THEN** that user's `subscription_status` becomes `premium_billing_retry` AND a `subscription_billing_issue` notification with `body_data.grace_end_at` set is written AND a `billing_issue` `subscription_events` row is recorded AND the user is still treated as effectively Premium

### Requirement: Expiration events downgrade the user to Free

The backend SHALL set `users.subscription_status = 'free'` when it processes an `EXPIRATION` event — the `EXPIRATION` event is the authoritative terminal billing signal. Any `EXPIRATION` event is terminal **regardless of its `expiration_reason`** (per `docs/01` § Payment Stack: "only EXPIRATION flips to free" — a `BILLING_ERROR` lapse and a voluntary lapse alike); the reason MUST NOT gate the downgrade and is NOT persisted in this change (`subscription_events` carries no reason column per `docs/05`). The handler SHALL write a `subscription_expired` notification for the user and record an `expiration` event.

#### Scenario: Expiration downgrades to Free and notifies
- **WHEN** an authenticated `EXPIRATION` event is processed for a `premium_billing_retry` user
- **THEN** that user's `subscription_status` becomes `free` AND a `subscription_expired` notification is written AND an `expiration` `subscription_events` row is recorded

### Requirement: Cancellation preserves access until the period ends

The backend SHALL NOT change `users.subscription_status` when it processes a `CANCELLATION` event — a cancellation keeps the user `premium_active` until the period actually ends (only an `EXPIRATION` event downgrades). The handler records a `cancellation` event. No notification is written for cancellation in this change (the confirmation-notification type is not yet in the catalog — see the deferral requirement).

#### Scenario: Cancellation records the event without changing status
- **WHEN** an authenticated `CANCELLATION` event is processed for a `premium_active` user
- **THEN** that user's `subscription_status` remains `premium_active` AND a `cancellation` `subscription_events` row is recorded AND no notification is written

### Requirement: Referral GRANT handling is deferred

This change SHALL NOT grant entitlements for referral `GRANT` events — the referral entitlement-stacking logic (and its `granted_entitlements` / `referral_tickets` tables) does not exist yet and is owned by a future referral-system change. When a `GRANT` event is received, the handler MUST NOT change any user's `subscription_status` and MUST NOT grant Premium; it logs the event and returns `200`. The future referral change will MODIFY this requirement to add full `GRANT` handling (recording `source = 'referral'` events and applying entitlement stacking).

#### Scenario: GRANT event grants nothing in this change
- **WHEN** a `GRANT` event is received at the webhook
- **THEN** no user's `subscription_status` is changed AND no Premium entitlement is granted AND the response status is `200` AND the event is logged for follow-up handling

### Requirement: Time-based grace-elapse auto-downgrade is deferred

This change SHALL set `premium_billing_retry` and record the grace-window end on a `BILLING_ISSUE` event, but SHALL NOT implement a time-based worker that auto-downgrades a stuck `premium_billing_retry` user to `free` after the grace window elapses — that daily grace-state worker is a separate future change. In this change, only an `EXPIRATION` event downgrades a `premium_billing_retry` user.

#### Scenario: A user remains in retry until an explicit expiration event
- **WHEN** a user has been in `premium_billing_retry` past the 7-day grace window AND no `EXPIRATION` event has been delivered
- **THEN** this change does NOT auto-downgrade that user to `free` (the time-based downgrade is owned by the deferred grace-state worker; the user is downgraded only upon an `EXPIRATION` event)

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

#### Scenario: Cancellation neither schedules nor clears a privacy flip
- **WHEN** an authenticated `CANCELLATION` event is processed for a user (whose `subscription_status` stays `premium_active`)
- **THEN** `users.privacy_flip_scheduled_at` is left unchanged — only an `EXPIRATION` schedules and only an `INITIAL_PURCHASE`/`RENEWAL` clears; a cancellation touches the column in neither direction (a private user keeps any pending deadline, a user with none stays NULL)

