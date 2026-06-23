-- V33: referral grant reliability — adds the 'voided' ticket status (#3) + grant
-- dispatch tracking for the activity-check worker's reconciliation pass (#2).
--
-- (1) referral_tickets.status gains 'voided': the worker voids a ticket whose
-- inviter is banned (docs/01 §233 "shadow or hard") DISTINCTLY from a TTL
-- 'expired', so analytics can tell a banned-inviter void from a 14-day lapse.
-- 'voided' is terminal — it is never scanned (the pending scan filters
-- status='pending_activity'), so no index change is needed.
--
-- (2) granted_entitlements.revenuecat_dispatched_at: NULL until the RevenueCat
-- promotional-grant call succeeds. The worker stamps it on a Dispatched result;
-- a reconciliation pass re-dispatches rows still NULL (a prior run's RC call
-- failed AFTER the ledger row committed, so the grant never reached RevenueCat).
-- NULLable, no default, no index (the daily reconcile scan is small + bounded by
-- the recent-grant volume); re-dispatch is idempotent via RevenueCat + dedup_key.

ALTER TABLE referral_tickets DROP CONSTRAINT referral_tickets_status_check;
ALTER TABLE referral_tickets ADD CONSTRAINT referral_tickets_status_check
    CHECK (status IN ('pending_activity', 'granted', 'expired', 'voided'));

ALTER TABLE granted_entitlements ADD COLUMN revenuecat_dispatched_at TIMESTAMPTZ;
