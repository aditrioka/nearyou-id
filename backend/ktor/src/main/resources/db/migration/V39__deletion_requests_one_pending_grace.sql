-- V39: at most ONE pending grace-period deletion request per user (issue #347).
--
-- The request / consent-revoked inserts (AccountDeletionRepository) guard with a
-- NOT EXISTS CTE, which is atomic per statement but not across transactions: a
-- concurrent double-POST could land two pending rows. This partial-unique index
-- makes the guarantee hold under concurrency; the inserts pair it with
-- ON CONFLICT DO NOTHING.
--
-- `apple_s2s_account_delete` is deliberately EXCLUDED: that path is an unconditional
-- immediate insert that escalates an already-pending grace row (apple-s2s-deletion-flows
-- design D7) — the moot grace row is then no-op'd by the worker's per-user guard.
--
-- Partial-index-clean: the predicate is NULL-ness + a constant `source`, no NOW().
-- IF NOT EXISTS keeps a staging branch-deploy re-apply harmless. Staging had zero
-- duplicate pending grace rows at authoring time (2026-09-27), so no dedupe step.

CREATE UNIQUE INDEX IF NOT EXISTS deletion_requests_one_pending_grace_idx
    ON deletion_requests(user_id)
    WHERE cancelled_at IS NULL
      AND executed_at IS NULL
      AND source <> 'apple_s2s_account_delete';
