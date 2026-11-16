-- V41: admin_actions_log — partial expression index on after_state ->> 'user_id'.
--
-- Backs the `admin-user-history-enforcement-rows` change (follow-up #496). The
-- admin per-user profile history (`admin-user-management`) reads every audit row
-- that affected the user:
--     WHERE (target_type = 'user' AND target_id = ?)
--        OR (after_state ->> 'user_id') = ?
-- The second arm is the `after_state.user_id` convention: a row keyed to another
-- object (a moderation_queue item, an appeal, a deletion request) names the
-- account it affected in after_state.user_id. V16 `admin_actions_target_idx`
-- serves the first arm; this index serves the second, so the OR plans as a
-- BitmapOr instead of a sequential scan of the append-only, 1-year-retention log
-- (the V17 viewer-index rationale).
--
-- Partial: only the minority of rows that carry the key are indexed (queue author
-- resolutions, appeal decisions, deletion expedites). The predicate is usable
-- with a bind parameter: `(after_state ->> 'user_id') = $1` is a strict-operator
-- clause, so the planner proves it implies `IS NOT NULL`. No NOW() in the
-- predicate (partial-index invariant). IF NOT EXISTS keeps it re-runnable (V17
-- precedent; staging branch deploys may re-apply). Not CONCURRENTLY: Flyway runs
-- each migration in a transaction, and the table is small pre-launch. The plain
-- build takes a ShareLock, blocking audit INSERTs (logins, CSRF events, worker
-- rows) for the milliseconds it runs; reads are unaffected. If this index is ever
-- rebuilt at scale, use CONCURRENTLY in its own migration with
-- `-- flyway:executeInTransaction=false`.
CREATE INDEX IF NOT EXISTS admin_actions_after_user_idx
    ON admin_actions_log ((after_state ->> 'user_id'))
    WHERE (after_state ->> 'user_id') IS NOT NULL;
