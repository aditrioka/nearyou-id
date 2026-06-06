-- admin-report-queue-viewer (Admin #6): performance index for the report-queue viewer.
-- Spec: openspec/specs/admin-report-queue (post-archive) — change admin-report-queue-viewer.
--
-- The viewer's default (no-filter) and date-only-filter queries order by
-- (created_at DESC, id DESC) with a keyset predicate (created_at, id) < (?, ?).
-- The three V9 secondary indexes all lead with a different column
-- (status / target_type / reporter_id) and none carries `id`, so none serves a
-- bare newest-first keyset scan — without this index Postgres sorts the whole
-- table on every default page load. This composite (created_at DESC, id DESC)
-- index is the covering index for both the ORDER BY and the row-value keyset
-- comparison, keeping the default + date-range views index-served as the
-- report backlog grows.
--
-- Non-partial, immutable definition (no WHERE, no NOW()) — satisfies the
-- "Partial indexes: no NOW() in WHERE" invariant by not being partial at all.
-- Idempotent via IF NOT EXISTS so a re-run (or a hand-applied staging index)
-- does not error. Mirrors V17__admin_actions_log_created_idx.sql verbatim
-- (no CONCURRENTLY — Flyway's transactional migration cannot run it; `reports`
-- is small pre-launch so the brief lock is acceptable).

CREATE INDEX IF NOT EXISTS reports_created_idx
    ON reports (created_at DESC, id DESC);
