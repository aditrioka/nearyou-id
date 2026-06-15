## ADDED Requirements

### Requirement: Invite code resolves to an inviter

When a signup request carries a non-empty `invite_code`, the system SHALL resolve it to an inviter via an exact-match O(1) lookup on the `users.invite_code_prefix` UNIQUE index (the invite code a user shares IS their `invite_code_prefix`). The lookup MUST restrict to live rows (`deleted_at IS NULL`). An `invite_code` that resolves to no live user SHALL produce no referral ticket, and signup MUST proceed unaffected.

#### Scenario: Valid invite code resolves
- **WHEN** signup is called with `invite_code` equal to a live user's `invite_code_prefix`
- **THEN** that user is selected as the candidate inviter and the flow proceeds to inviter validation

#### Scenario: Unresolvable invite code is ignored
- **WHEN** signup is called with an `invite_code` that matches no `users.invite_code_prefix` (or matches only a soft-deleted user)
- **THEN** no `referral_tickets` row is inserted AND signup returns its normal `201` result

#### Scenario: Absent invite code skips referral entirely
- **WHEN** signup is called with `invite_code` omitted, null, or blank
- **THEN** no inviter lookup is performed AND no `referral_tickets` row is inserted

### Requirement: Inviter eligibility validation

A resolved inviter MUST pass ALL of the following for a ticket to be created: the inviter exists and is not soft-deleted; the inviter is not banned (`is_banned = FALSE`); the inviter's account age is strictly greater than 30 days (`created_at < NOW() - INTERVAL '30 days'`); and the inviter is not the invitee (`inviter_user_id <> invitee_user_id`). Any failed check SHALL produce no ticket, silently.

#### Scenario: Banned inviter produces no ticket
- **WHEN** the resolved inviter has `is_banned = TRUE`
- **THEN** no `referral_tickets` row is inserted AND signup returns its normal `201` result

#### Scenario: Inviter account too new
- **WHEN** the resolved inviter's `created_at` is within the last 30 days
- **THEN** no `referral_tickets` row is inserted AND the rejection reason is audit-logged server-side

#### Scenario: Self-invite produces no ticket
- **WHEN** the resolved inviter id equals the newly created invitee id
- **THEN** no `referral_tickets` row is inserted

### Requirement: Invitee anti-abuse validation at signup time

Before a ticket is created the system SHALL apply two signup-time anti-abuse checks: (1) the invitee's `device_fingerprint_hash` MUST NOT equal the inviter's when both are non-null (a collision blocks the ticket); and (2) the inviter MUST NOT already have 3 referral tickets created within a rolling 7-day window (per-inviter burst rate), enforced via the shared `RateLimiter` over a Redis key of the form `rate:{inviter:<inviter_id>}:referral_ticket`. A failed check SHALL produce no ticket, silently.

#### Scenario: Device fingerprint collision blocks ticket
- **WHEN** the invitee's non-null `device_fingerprint_hash` equals the inviter's non-null `device_fingerprint_hash`
- **THEN** no `referral_tickets` row is inserted AND the rejection reason is audit-logged

#### Scenario: Null fingerprints do not count as a collision
- **WHEN** the invitee's `device_fingerprint_hash` is null
- **THEN** the fingerprint-collision check passes (no collision is inferred from absent data)

#### Scenario: Fourth ticket within the burst window is rejected
- **WHEN** the resolved inviter has already had 3 referral tickets created in the preceding 7 days AND a fourth eligible signup uses their code
- **THEN** no fourth `referral_tickets` row is inserted AND signup returns its normal `201` result

#### Scenario: Burst-rate key uses the cluster-safe hash-tag format
- **WHEN** the per-inviter burst-rate limiter constructs its Redis key
- **THEN** the key contains a `{scope:<value>}` hash tag (e.g. `rate:{inviter:<id>}:referral_ticket`) so multi-key operations co-locate on one cluster slot

### Requirement: Referral ticket persistence

When an invite code resolves and all inviter + invitee checks pass, the system SHALL insert exactly one `referral_tickets` row with `inviter_user_id`, `invitee_user_id`, `status = 'pending_activity'`, and `expires_at = created_at + INTERVAL '14 days'`. The `invitee_user_id` column is UNIQUE, so at most one ticket can ever exist per invitee registration (idempotent).

#### Scenario: Eligible referral creates a pending ticket
- **WHEN** a signup with a valid code passes inviter and invitee validation
- **THEN** a `referral_tickets` row exists with the inviter and invitee ids, `status = 'pending_activity'`, and `expires_at` 14 days after `created_at`

#### Scenario: One ticket per invitee
- **WHEN** a second ticket insert is attempted for the same `invitee_user_id`
- **THEN** the UNIQUE constraint on `invitee_user_id` rejects the duplicate AND the duplicate is swallowed without affecting signup

### Requirement: Referral creation is best-effort, non-blocking, and silent

Referral ticket creation SHALL run only after the inviting `users` row is committed, in its own transaction, and MUST NOT alter the signup outcome. Any referral failure — missing/blank code, unresolvable code, failed inviter or invitee validation, burst-rate rejection, duplicate-invitee conflict, or an unexpected error in the referral path — MUST be swallowed: signup still returns HTTP `201` with the identical `{ access_token, refresh_token, expires_in }` body and MUST NOT introduce any new error code on the signup path. Every referral attempt (created OR rejected-with-reason) MUST be recorded via server-side structured logging only; the outcome MUST NOT be observable by the caller (anti-probing).

#### Scenario: Referral failure never fails signup
- **WHEN** any referral validation fails or the referral path throws
- **THEN** the signup response is HTTP `201` with the normal token-pair body AND no signup error code is emitted

#### Scenario: Outcome is indistinguishable to the caller
- **WHEN** two otherwise-identical signups are made — one whose code creates a ticket, one whose code is rejected
- **THEN** the two signup responses are byte-identical apart from the freshly issued token values

#### Scenario: Every attempt is audit-logged server-side
- **WHEN** a referral attempt is processed (whether a ticket is created or rejected)
- **THEN** a structured server-side log entry records the outcome and, on rejection, the reason — with no caller-visible signal

### Requirement: referral_tickets schema

A Flyway migration SHALL create the `referral_tickets` table with: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`; `inviter_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`; `invitee_user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE`; `status VARCHAR(32) NOT NULL CHECK (status IN ('pending_activity', 'granted', 'expired'))`; `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`; `expires_at TIMESTAMPTZ NOT NULL`. It SHALL create a partial index for the future activity-gate worker scan over pending tickets and an index supporting per-inviter status counts. No index predicate may contain `NOW()` (partial-index immutability invariant).

#### Scenario: Status vocabulary is constrained
- **WHEN** a `referral_tickets` row is inserted with a `status` outside `('pending_activity', 'granted', 'expired')`
- **THEN** the CHECK constraint rejects the insert

#### Scenario: Pending-scan index predicate is immutable
- **WHEN** the migration creates the partial index used to scan pending tickets
- **THEN** its `WHERE` clause uses only the constant `status = 'pending_activity'` predicate (no `NOW()` / volatile expression) so the migration applies cleanly

#### Scenario: User hard-delete cascades tickets
- **WHEN** a user referenced as inviter or invitee is hard-deleted
- **THEN** the user's `referral_tickets` rows are removed by the `ON DELETE CASCADE` FK

### Requirement: Activity-gate worker is out of scope

This change SHALL NOT implement the referral activity gate. No `/internal/referral-activity-check` worker is added, and no code path flips a ticket out of `pending_activity`. Tickets created by this change remain `pending_activity` until a future change ships the worker. The `'granted'` and `'expired'` status values are reserved in the CHECK constraint for that future change but are never written here.

#### Scenario: Tickets stay pending after creation
- **WHEN** a ticket is created and any amount of time passes within this change's scope
- **THEN** the ticket's `status` remains `'pending_activity'` (nothing in this change transitions it)

### Requirement: Entitlement grants are out of scope

This change SHALL NOT grant Premium entitlements, create a `granted_entitlements` table, dispatch any RevenueCat Granted-Entitlements API call, or enforce the inviter lifetime single-grant cap. The `users.inviter_reward_claimed_at` sentinel MUST NOT be read or written by this change.

#### Scenario: No entitlement side effects
- **WHEN** a referral ticket is created
- **THEN** no entitlement is granted, no `granted_entitlements` row is created, and `users.inviter_reward_claimed_at` is unchanged

### Requirement: Richer anti-collision checks are out of scope

This change SHALL implement only the `device_fingerprint_hash` collision check at signup time. The IP-subnet (/24) overlap check against the inviter's recent login subnets and the recently-seen Google/Apple identifier check (docs/01-Business.md § Bonus Release Criteria item 3) are NOT performed here; they belong to the deferred activity-gate worker, which evaluates the full multi-stage gate. This change MUST NOT claim to enforce those checks.

#### Scenario: IP-subnet and identifier-history checks are not applied at signup
- **WHEN** a signup-time referral is evaluated
- **THEN** only the `device_fingerprint_hash` collision check is applied for anti-abuse (alongside the burst-rate limit) AND the IP-subnet and recently-seen-identifier checks are not consulted
