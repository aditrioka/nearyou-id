-- V31: content-moderation appeal loop (content-moderation-appeal).
--
-- `appeals` — user-submitted appeals against account-level moderation actions
-- (7-day suspension / permanent ban), per docs/08 Open Decision #2 + docs/03 §234.
-- A banned/suspended user submits one pending appeal (reachable via the ban-exempt
-- appeal realm + the limited appeal token — see auth-jwt / auth-signin); an admin
-- approves (→ unban) or rejects it (admin-appeal-review).
--
-- FKs: user_id ON DELETE CASCADE (the appeal is moot once the account is gone);
-- reviewed_by → admin_users(id) ON DELETE SET NULL (admin-session FK invariant —
-- admin deletion must not cascade-destroy appeal/audit history; mirrors
-- reports.reviewed_by / moderation_queue.resolved_by from V16).
--
-- Partial-index-clean: the one-pending-per-user uniqueness + the pending-queue
-- index both filter on a constant `status = 'pending'`, never NOW() (the
-- partial-index invariant — non-immutable WHERE is rejected by PG and the lint rule).
--
-- appeal_text / decision_reason length CHECKs are the schema backstop to the
-- content-length-guard invariant (the route-level guard is primary). action_type is
-- server-derived (suspended_until IS NULL → 'permanent_ban', else 'suspension'),
-- never client-supplied.

CREATE TABLE appeals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action_type     TEXT NOT NULL CHECK (action_type IN ('suspension', 'permanent_ban')),
    appeal_text     TEXT NOT NULL CHECK (char_length(appeal_text) BETWEEN 1 AND 1000),
    status          TEXT NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'approved', 'rejected')),
    decision_reason TEXT CHECK (decision_reason IS NULL OR char_length(decision_reason) <= 1000),
    reviewed_by     UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    reviewed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One pending appeal per user (anti-spam): a second submission while one is pending
-- hits this partial-unique → mapped to 409 appeal_already_pending.
CREATE UNIQUE INDEX appeals_one_pending_per_user
    ON appeals(user_id)
    WHERE status = 'pending';

-- Admin pending-queue scan path (admin-appeal-review): list awaiting appeals oldest-first.
CREATE INDEX appeals_pending_created_idx
    ON appeals(created_at)
    WHERE status = 'pending';
