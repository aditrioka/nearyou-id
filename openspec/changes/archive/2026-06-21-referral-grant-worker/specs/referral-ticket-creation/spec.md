## MODIFIED Requirements

### Requirement: Activity-gate worker is out of scope

The referral-ticket-creation capability SHALL NOT implement the referral activity gate: the signup ticket-creation path adds no worker and flips no ticket out of `pending_activity`. Tickets created here remain `pending_activity` until acted on by the `referral-grant-worker` capability's `/internal/referral-activity-check` worker (shipped), which writes the `'granted'` / `'expired'` status values reserved in the V23 `CHECK` constraint. Ticket creation's own behavior is unchanged — it never transitions a ticket.

#### Scenario: Ticket creation does not itself transition a ticket
- **WHEN** a ticket is created at signup
- **THEN** its `status` is `'pending_activity'` AND the signup / ticket-creation path performs no further status transition (advancing the ticket is the `referral-grant-worker` worker's responsibility)

### Requirement: Entitlement grants are out of scope

The referral-ticket-creation capability SHALL NOT grant Premium entitlements, write the `granted_entitlements` table, dispatch any RevenueCat promotional-entitlement call, or read/write the `users.inviter_reward_claimed_at` sentinel. Those are performed by the `referral-grant-worker` capability (shipped), not by the signup ticket-creation path. Creating a ticket has no entitlement side effects.

#### Scenario: No entitlement side effects at signup
- **WHEN** a referral ticket is created at signup
- **THEN** no entitlement is granted, no `granted_entitlements` row is created, and `users.inviter_reward_claimed_at` is unchanged by the signup path

### Requirement: Richer anti-collision checks are out of scope

The referral-ticket-creation capability SHALL implement only the `device_fingerprint_hash` collision check at signup time. The IP-subnet (/24) overlap check against the inviter's recent login subnets and the recently-seen Google/Apple identifier check (docs/01-Business.md § Bonus Release Criteria item 3) are NOT performed here. These two legs also remain **unimplemented by the shipped `referral-grant-worker`** — they require durable login-history data that does not exist yet and are deferred to a follow-up change. Neither the signup path nor the current activity-check worker enforces them; this capability MUST NOT claim to.

#### Scenario: IP-subnet and identifier-history checks are not applied at signup
- **WHEN** a signup-time referral is evaluated
- **THEN** only the `device_fingerprint_hash` collision check is applied for anti-abuse (alongside the burst-rate limit) AND the IP-subnet and recently-seen-identifier checks are not consulted
