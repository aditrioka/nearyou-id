## Why

The referral system is the growth lever in the freemium revenue loop (docs/01-Business.md § Referral System; docs/08-Roadmap-Risk.md § Phase 4 items 21–23) but is entirely unbuilt — `SignupService` ignores invite codes and no `referral_tickets` table exists (docs/05-Implementation.md § Referral System — DESIGN). The foundational sentinel columns are already shipped (`users.invite_code_prefix` UNIQUE + `users.inviter_reward_claimed_at`, V2), so the dependency-first slice — **creating a referral ticket at signup** — is cleanly bounded and unblocks the activity-gate worker and entitlement-grant changes that follow.

## What Changes

- **`POST /api/v1/auth/signup` accepts an optional `invite_code`** (snake_case wire field, default null). Supplying it is the only way to enter the referral funnel; omitting it leaves signup behavior byte-identical to today.
- **Best-effort referral ticket creation** runs after the existing atomic user insert: the `invite_code` resolves O(1) to an inviter via the shipped `users.invite_code_prefix` UNIQUE index (the code a user shares *is* their `invite_code_prefix`). On passing validation, a `referral_tickets` row is inserted with `status = 'pending_activity'`.
- **Inviter validation**: exists, `deleted_at IS NULL`, not banned, account age > 30 days, not self.
- **Invitee validation (signup-time subset)**: `device_fingerprint_hash` does not collide with the inviter's; per-inviter burst rate ≤ 3 ticket-creations / 7-day window (via the shipped `RateLimiter` + Redis sliding window).
- **Anti-probing guarantee**: every referral failure is **silent to the invitee** — signup still returns `201` with the identical token-pair body regardless of referral outcome. Referral work is **non-blocking / best-effort**: a missing, malformed, unresolvable, or rejected code never fails signup. Every attempt (created or rejected-with-reason) is **audit-logged via structured logging** (the existing `SignupService` `log.warn("signup.blocked …")` precedent), never surfaced to the caller.
- **New `referral_tickets` table** (V23 migration) with a partial index for the future worker scan and a per-inviter counter index.
- **Explicitly deferred** (captured as negative-guard requirements so the follow-ons have something to MODIFY): the `/internal/referral-activity-check` worker + 14-day activity gate; the `granted_entitlements` table + RevenueCat grant dispatch + inviter lifetime single-grant enforcement; and the richer signup-time anti-collision checks (IP-subnet / recently-seen-identifier) that require login-history data not yet tracked.

## Capabilities

### New Capabilities
- `referral-ticket-creation`: resolving an invite code to an inviter, validating inviter + invitee at signup time, and creating a single idempotent `referral_tickets` row in `pending_activity` state (best-effort, silent-fail, audit-logged). Owns the `referral_tickets` schema, the ticket status vocabulary, the burst-rate limit, and the negative-guard requirements scoping out the worker / grants / richer anti-collision.

### Modified Capabilities
- `auth-signup`: the signup endpoint gains an optional `invite_code` request field and a best-effort, non-blocking, silent referral hook that runs after user creation. The signup status code, response body, and all existing rejection paths are unchanged; referral outcome never affects them.

## Impact

- **API**: `POST /api/v1/auth/signup` request gains optional `invite_code` (response unchanged).
- **Schema**: new `V23__referral_tickets.sql` (table + 2 indexes). No change to `users` (sentinel columns already exist). FKs `REFERENCES users(id) ON DELETE CASCADE` (follows/reports/user_blocks precedent).
- **Code**: new `id.nearyou.app.referral` package (`ReferralService`, `ReferralRepository`, a narrow `ReferralTicketCreator` interface); `SignupService` gains one best-effort call after the user insert; Koin wiring; the `invite_code` DTO field + `AuthWireFormatTest` surface.
- **Infra**: reuses the shipped `RateLimiter`/Redis (new `rate:{inviter:<id>}:referral_ticket` key, hash-tag format) and the existing `invite-code-secret` (no new secret slot, no new library).
- **Docs**: flips docs/05-Implementation.md § Referral System from DESIGN toward shipped for the ticket-creation slice (reconciled during proposal review).
