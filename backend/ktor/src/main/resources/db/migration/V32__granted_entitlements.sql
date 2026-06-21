-- V32: granted_entitlements — the referral funnel's grant ledger + DB-level
-- idempotency / lifetime-cap authority.
--
-- Backs the Phase-4 `referral-grant-worker` change (docs/08 #9/#21/#23): the
-- /internal/referral-activity-check worker flips a pending_activity ticket to
-- 'granted' and records ONE row here per recipient grant (invitee every
-- successful ticket; inviter once at the confirmed 5th). The row is the
-- authoritative "did we grant" record; users.subscription_status is written by
-- the subscription-billing-webhook GRANT echo, not here. See docs/01-Business.md
-- §137-143 (Granted Entitlement Stacking) + docs/05-Implementation.md §1180-1188.
--
-- Idempotency (docs/01 §143): UNIQUE (referral_ticket_id, user_id) guards the
-- invitee grant + duplicate-5th-milestone attempts; the worker inserts with
-- ON CONFLICT DO NOTHING so a re-run / concurrent invocation never double-grants.
--
-- Inviter lifetime cap is enforced TWO ways (belt + suspenders, both DB-level):
-- the partial UNIQUE index granted_entitlements_inviter_once_idx (one inviter-role
-- grant per recipient, ever) AND the pre-existing users.inviter_reward_claimed_at
-- sentinel (V2), set in the same transaction as the inviter grant. No NOW() /
-- volatile expression appears in any index predicate (partial-index immutability
-- invariant) — the only partial predicate is the constant grant_role = 'inviter'.
--
-- FK ON DELETE CASCADE on both refs mirrors referral_tickets (V23): a grant for a
-- hard-deleted user or ticket is meaningless. dedup_key is the RevenueCat-call
-- idempotency token (<referral_ticket_id>:<grant_role>), UNIQUE so a retried
-- dispatch is a no-op on our side too.

CREATE TABLE granted_entitlements (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    referral_ticket_id UUID NOT NULL REFERENCES referral_tickets(id) ON DELETE CASCADE,
    user_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    grant_role         VARCHAR(16) NOT NULL
        CHECK (grant_role IN ('invitee', 'inviter')),
    entitlement_start  TIMESTAMPTZ NOT NULL,
    entitlement_end    TIMESTAMPTZ NOT NULL,
    dedup_key          TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (referral_ticket_id, user_id)
);

-- One inviter-role grant per recipient, for their entire lifetime.
CREATE UNIQUE INDEX granted_entitlements_inviter_once_idx
    ON granted_entitlements (user_id)
    WHERE grant_role = 'inviter';

-- RevenueCat-dispatch idempotency token.
CREATE UNIQUE INDEX granted_entitlements_dedup_key_idx
    ON granted_entitlements (dedup_key);
