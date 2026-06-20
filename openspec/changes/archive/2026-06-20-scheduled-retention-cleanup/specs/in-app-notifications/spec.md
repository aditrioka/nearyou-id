## RENAMED Requirements

- FROM: `### Requirement: 90-day retention documented (enforcement deferred)`
- TO: `### Requirement: 90-day retention documented (enforcement shipped in scheduled-retention-cleanup)`

## MODIFIED Requirements

### Requirement: 90-day retention documented (enforcement shipped in scheduled-retention-cleanup)

The V10 migration SHALL include a `COMMENT ON TABLE notifications IS 'Per-user notification feed; 90-day retention policy; purge worker lands in the Phase 3.5 admin-panel change.'` (the historical V10 comment is immutable and retained as-is). Enforcement is now implemented by the [`scheduled-retention-cleanup`](../../specs/scheduled-retention-cleanup/spec.md) worker (`POST /internal/cleanup`), which executes `DELETE FROM notifications WHERE created_at < NOW() - INTERVAL '90 days'` on its scheduled run. V10 itself does NOT include a purge worker.

#### Scenario: Retention comment present after V10
- **WHEN** querying `obj_description('public.notifications'::regclass, 'pg_class')`
- **THEN** the returned comment contains both the phrases `90-day` AND `purge`
