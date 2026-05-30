-- admin-actions-log-viewer (Admin #4): performance index for the audit-log viewer.
-- Spec: openspec/specs/admin-actions-log-viewer (post-archive) — change admin-actions-log-viewer.
--
-- The viewer's default (no-filter) and date-only-filter queries order by
-- (created_at DESC, id DESC) with a keyset predicate (created_at, id) < (?, ?).
-- The three V16 secondary indexes all lead with a different column
-- (admin_id / target_type / action_type), so none serves a bare
-- newest-first scan — without this index Postgres sorts the whole table on
-- every default page load. This composite (created_at DESC, id DESC) index
-- is the covering index for both the ORDER BY and the row-value keyset
-- comparison, keeping the default + date-range views index-served as the
-- append-only log grows (1-year+ retention).
--
-- Non-partial, immutable definition (no WHERE, no NOW()) — satisfies the
-- "Partial indexes: no NOW() in WHERE" invariant by not being partial at all.
-- Idempotent via IF NOT EXISTS so a re-run (or a hand-applied staging index)
-- does not error.

CREATE INDEX IF NOT EXISTS admin_actions_created_idx
    ON admin_actions_log (created_at DESC, id DESC);
