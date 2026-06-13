-- V21: subscription_events — the event-level billing ledger.
--
-- Backs the Phase-4 `revenuecat-subscription-webhook` change: the
-- POST /internal/revenuecat-webhook handler records one row per processed
-- RevenueCat billing event and drives users.subscription_status (the column
-- already exists since V2). Event-level tracking is mandatory because revenue
-- analytics must reconstruct transitions from events — a user-level flag loses
-- information (docs/01-Business.md § Subscription Analytics Integrity).
--
-- Schema mirrors docs/05-Implementation.md § Subscription Event-Level Tracking
-- verbatim (column names, both CHECK vocabularies, the two indexes).
--
-- Provenance note: docs/05 § Referral System previously mis-attributed this
-- table to "(V9)"; it is first created HERE (follow-up #295). The referral
-- system change relies on the `source` vocabulary ('referral'/'manual_admin')
-- being present — those values are reserved by this CHECK but UNUSED until the
-- referral change ships its GRANT handling.
--
-- `revenuecat_event_id` is UNIQUE — the idempotency key. The webhook does
-- `INSERT ... ON CONFLICT (revenuecat_event_id) DO NOTHING`; a re-delivered
-- event is a no-op duplicate (financial events must be exactly-once).

CREATE TABLE subscription_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    event_type VARCHAR(32) NOT NULL
        CHECK (event_type IN ('initial_purchase', 'renewal', 'grant', 'cancellation', 'billing_issue', 'expiration')),
    source VARCHAR(32) NOT NULL CHECK (source IN ('paid', 'referral', 'manual_admin')),
    revenuecat_event_id TEXT UNIQUE,
    entitlement_start TIMESTAMPTZ,
    entitlement_end TIMESTAMPTZ,
    amount_rupiah BIGINT,
    platform VARCHAR(16),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX subscription_events_user_idx ON subscription_events(user_id, created_at DESC);
CREATE INDEX subscription_events_source_idx ON subscription_events(source, event_type, created_at);
