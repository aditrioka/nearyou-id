-- V23: referral_tickets — the referral funnel's first-stage ledger.
--
-- Backs the Phase-4 `referral-ticket-creation` change: POST /api/v1/auth/signup
-- resolves an optional invite_code to the inviter via users.invite_code_prefix
-- (UNIQUE, V2) and inserts ONE pending_activity ticket per invitee registration.
-- Ticket creation is best-effort + silent to the invitee (anti-probing); see
-- docs/01-Business.md § Referral System + docs/08-Roadmap-Risk.md § Phase 4 #22.
--
-- Scope boundary (this migration ships ONLY ticket creation): the 'granted' and
-- 'expired' status values are reserved in the CHECK for the deferred
-- /internal/referral-activity-check worker (docs/08 #23) and the entitlement-
-- grant change (docs/08 #9). NOTHING in this change transitions a ticket out of
-- pending_activity, and granted_entitlements is a separate future migration.
--
-- FK ON DELETE CASCADE on both parties mirrors the user-referencing precedent
-- (follows V6, reports.reporter_id V9, user_blocks V5): a ticket without both
-- parties is meaningless. invitee_user_id is UNIQUE — one ticket per invitee
-- registration, making the best-effort double-submit idempotent.
--
-- referral_tickets_pending_idx is PARTIAL on the constant status predicate only
-- (no NOW() in WHERE — partial-index immutability invariant); the deferred worker
-- supplies NOW() at query time. referral_tickets_inviter_status_idx backs the
-- future per-inviter 5th-referral counter.

CREATE TABLE referral_tickets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inviter_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invitee_user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    status          VARCHAR(32) NOT NULL
        CHECK (status IN ('pending_activity', 'granted', 'expired')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX referral_tickets_pending_idx
    ON referral_tickets (expires_at)
    WHERE status = 'pending_activity';

CREATE INDEX referral_tickets_inviter_status_idx
    ON referral_tickets (inviter_user_id, status);
