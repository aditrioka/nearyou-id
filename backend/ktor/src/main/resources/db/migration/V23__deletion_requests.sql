-- V23: account deletion foundation (account-deletion-tombstone).
--
-- `deletion_requests` — verbatim from docs/05-Implementation.md § Deletion Requests
-- Schema. Backs the user-initiated deletion lifecycle (request → 30-day grace →
-- cancel) and the daily hard-delete worker. The `source` CHECK enumerates all four
-- canonical values even though this change only PRODUCES `'user'` rows — the other
-- three are reserved for the downstream Apple-S2S + admin paths (no further
-- migration needed there).
--
-- Partial-index-clean: both partial indexes filter on NULL-ness + a constant
-- `source`, never `NOW()` (the partial-index invariant — non-immutable WHERE is
-- rejected by PG and by the lint rule).
--
-- `deletion_log` — append-only record of every executed hard-delete, so the
-- Pre-Launch backup-restore reconciliation ("no tombstoned user resurrected") has a
-- queryable source of truth. `user_id` carries NO FK to `users` (the row must
-- survive a future true row-purge). Append-only is enforced at the role level
-- (INSERT/SELECT granted, UPDATE/DELETE revoked at the app role) — mirroring the
-- `admin_actions_log` immutability posture, provisioned via the role-grant scripts,
-- NOT in this migration (CI/test runs as the owner role).

CREATE TABLE deletion_requests (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    scheduled_hard_delete_at TIMESTAMPTZ NOT NULL,
    cancelled_at             TIMESTAMPTZ,
    executed_at              TIMESTAMPTZ,
    source                   VARCHAR(24) NOT NULL CHECK (source IN (
        'user', 'apple_s2s_consent_revoked', 'apple_s2s_account_delete', 'admin'
    ))
);

-- Daily-worker scan path: due, un-cancelled, un-executed rows.
CREATE INDEX deletion_requests_scheduled_idx
    ON deletion_requests(scheduled_hard_delete_at)
    WHERE executed_at IS NULL AND cancelled_at IS NULL;

-- Apple-S2S immediate-execute backstop path (downstream change; index ships now so
-- that change needs no migration).
CREATE INDEX deletion_requests_immediate_idx
    ON deletion_requests(requested_at)
    WHERE source = 'apple_s2s_account_delete'
      AND executed_at IS NULL
      AND cancelled_at IS NULL;

CREATE TABLE deletion_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source      VARCHAR(24) NOT NULL
);
