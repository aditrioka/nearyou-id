# referral-ticket-creation Specification

## Purpose

The referral-ticket-creation capability creates a referral ticket at signup — the entry point of the freemium referral/growth loop. `POST /api/v1/auth/signup` accepts an optional `invite_code`; when it resolves (O(1) on the shipped `users.invite_code_prefix` UNIQUE index) to an eligible inviter — existing, not soft-deleted, not banned, account age > 30 days, not the invitee — and the invitee passes the signup-time anti-abuse checks (device-fingerprint non-collision + a per-inviter ≤ 3-per-rolling-7-day burst limit), the system inserts exactly one `referral_tickets` row in `pending_activity` state (`invitee_user_id` UNIQUE → one ticket per registration, idempotent). Ticket creation is best-effort, non-blocking, silent to the invitee (anti-probing), and audit-logged server-side only — signup always returns its normal result regardless of the referral outcome. This is the first slice of the referral system: the activity-gate worker that advances a ticket out of `pending_activity`, the `granted_entitlements` table and entitlement grants (incl. the inviter lifetime single-grant), and the richer IP-subnet / recently-seen-identifier anti-collision legs are explicitly out of scope, captured here as negative-guard requirements for follow-on changes to MODIFY.
## Requirements
### Requirement: Invite code resolves to an inviter

When a signup request carries a non-empty `invite_code`, the system SHALL resolve it to an inviter via an exact-match O(1) lookup on the `users.invite_code_prefix` UNIQUE index (the invite code a user shares IS their `invite_code_prefix`, matched in full by exact string equality). Because `invite_code_prefix` is `VARCHAR(8)` today, every live code is 8 characters; the resolver is length-agnostic (a plain equality match) so it needs no length-specific handling if the column is ever widened for the `InviteCodePrefixDeriver` 10-char collision fallback (currently unreachable under the `VARCHAR(8)` width — see `InviteCodePrefixDeriver` KDoc). The lookup MUST restrict to live rows (`deleted_at IS NULL`). An `invite_code` that resolves to no live user SHALL produce no referral ticket, and signup MUST proceed unaffected.

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

A resolved inviter MUST pass ALL of the following for a ticket to be created: the inviter exists and is live (the resolver's `deleted_at IS NULL` filter already guarantees liveness — re-stated here as the eligibility contract, not a second query); the inviter is not banned (`is_banned = FALSE`); the inviter's account age is strictly greater than 30 days (`created_at < NOW() - INTERVAL '30 days'`); and the inviter is not the invitee (`inviter_user_id <> invitee_user_id`). Any failed check SHALL produce no ticket, silently.

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

Before a ticket is created the system SHALL apply two signup-time anti-abuse checks: (1) the invitee's `device_fingerprint_hash` MUST NOT equal the inviter's when both are non-null (a collision blocks the ticket); and (2) the inviter MUST NOT already have 3 referral tickets created within a rolling 7-day window (per-inviter burst rate), enforced via the shared `RateLimiter` axis-agnostic entry point over the Redis key `{scope:rate_referral_ticket}:{inviter:<inviter_id>}`. A failed check SHALL produce no ticket, silently.

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
- **THEN** the key has the two-segment hash-tagged shape `{scope:rate_referral_ticket}:{inviter:<id>}` (matching the `rate-limit-infrastructure` non-per-user key contract) so multi-key operations co-locate on one cluster slot AND the scope does not end in `_day` (it stays a sliding window, not a fixed daily window)

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

