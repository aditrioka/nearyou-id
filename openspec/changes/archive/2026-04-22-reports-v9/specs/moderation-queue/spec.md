## ADDED Requirements

### Requirement: moderation_queue table created via Flyway V9

Migration `V9__reports_moderation.sql` SHALL create the `moderation_queue` table verbatim-aligned with `docs/05-Implementation.md` §789–814 with columns:
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('post', 'reply', 'user', 'chat_message'))`
- `target_id UUID NOT NULL`
- `trigger VARCHAR(32) NOT NULL CHECK (trigger IN ('auto_hide_3_reports', 'perspective_api_high_score', 'uu_ite_keyword_match', 'admin_flag', 'csam_detected', 'anomaly_detection', 'username_flagged'))`
- `status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'resolved'))`
- `resolution VARCHAR(32) CHECK (resolution IS NULL OR resolution IN ('keep', 'hide', 'delete', 'shadow_ban_author', 'suspend_author_7d', 'ban_author', 'accept_flagged_username', 'reject_flagged_username'))` (nullable)
- `priority SMALLINT NOT NULL DEFAULT 5`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `resolved_at TIMESTAMPTZ` (nullable)
- `resolved_by UUID` (nullable; **NO FK** — deferred to the Phase 3.5 admin-users migration)
- `notes TEXT` (nullable)
- `UNIQUE (target_type, target_id, trigger)`

Plus two indexes:
- `moderation_queue_status_idx ON moderation_queue(status, priority, created_at)`
- `moderation_queue_target_idx ON moderation_queue(target_type, target_id)`

The `resolved_by` column MUST carry a `COMMENT ON COLUMN` recording its deferred FK target (`admin_users(id) ON DELETE SET NULL`). The Phase 3.5 admin-users migration will add the constraint via `ALTER TABLE ... ADD CONSTRAINT ... NOT VALID` + `VALIDATE CONSTRAINT` (mirrors the `reports.reviewed_by` pattern).

#### Scenario: Table created in same migration as reports
- **WHEN** Flyway runs `V9__reports_moderation.sql` against a DB at V8
- **THEN** both `reports` and `moderation_queue` tables exist after the migration succeeds

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'moderation_queue'`
- **THEN** every column above is present with its documented type, nullability, and default

#### Scenario: UNIQUE constraint on (target_type, target_id, trigger)
- **WHEN** two INSERTs attempt the same `(target_type, target_id, trigger)` tuple
- **THEN** the second INSERT fails with SQLSTATE `23505` (unique-violation)

#### Scenario: target_type CHECK rejects out-of-enum value
- **WHEN** an INSERT supplies `target_type = 'dm'`
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: Full trigger enum ships at V9 (forward-compatible)
- **WHEN** an INSERT supplies `trigger` from any of the 7 enum values
- **THEN** the INSERT succeeds (each value in `('auto_hide_3_reports', 'perspective_api_high_score', 'uu_ite_keyword_match', 'admin_flag', 'csam_detected', 'anomaly_detection', 'username_flagged')` passes the CHECK)

#### Scenario: trigger CHECK rejects out-of-enum value
- **WHEN** an INSERT supplies `trigger = 'spam_pattern_detected'`
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: status CHECK rejects out-of-enum value
- **WHEN** an INSERT supplies `status = 'in_review'`
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: Full resolution enum ships at V9 (forward-compatible)
- **WHEN** an UPDATE supplies `resolution` from any of the 8 enum values
- **THEN** the UPDATE succeeds (each value in `('keep', 'hide', 'delete', 'shadow_ban_author', 'suspend_author_7d', 'ban_author', 'accept_flagged_username', 'reject_flagged_username')` passes the CHECK)

#### Scenario: resolution CHECK allows NULL
- **WHEN** an INSERT omits `resolution` (leaving it NULL)
- **THEN** the CHECK constraint is satisfied

#### Scenario: resolution CHECK rejects out-of-enum non-NULL value
- **WHEN** an UPDATE supplies `resolution = 'escalate_to_legal'`
- **THEN** the UPDATE fails with a check-constraint violation

#### Scenario: priority defaults to 5
- **WHEN** an INSERT omits `priority`
- **THEN** the inserted row has `priority = 5`

#### Scenario: Both indexes exist
- **WHEN** querying `pg_indexes WHERE tablename = 'moderation_queue'`
- **THEN** the result contains `moderation_queue_status_idx` AND `moderation_queue_target_idx`

#### Scenario: resolved_by has no FK constraint in V9
- **WHEN** querying `information_schema.referential_constraints` for the `moderation_queue` table
- **THEN** no row is returned for `resolved_by` (the column exists as a plain UUID)

#### Scenario: resolved_by column carries deferred-FK documentation
- **WHEN** querying `pg_description` for the `resolved_by` column of `moderation_queue`
- **THEN** the comment mentions `admin_users(id)` AND the phrase `deferred`

### Requirement: V9 writes auto_hide_3_reports rows only

The V9 change ships exactly one writer into `moderation_queue`: the reports auto-hide path (see `reports` capability). That path MUST insert rows with `trigger = 'auto_hide_3_reports'`. No other trigger value is written by V9 code. Future changes (Phase 2 Perspective API, Phase 3.5 admin-flag, Phase 4 CSAM / anomaly / username-flagged) introduce their own writers.

#### Scenario: Only auto_hide_3_reports rows written in V9
- **WHEN** the V9-era codebase is fully deployed and `moderation_queue` has been populated
- **THEN** every row's `trigger` column equals `'auto_hide_3_reports'`

#### Scenario: No writer path exists for the other 6 trigger values in V9
- **WHEN** searching the `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/` tree for inserts into `moderation_queue`
- **THEN** all insert statements use the literal `'auto_hide_3_reports'` as the `trigger` value (no parameterized path reaching other enum values from V9 code)

### Requirement: Idempotent insert via ON CONFLICT DO NOTHING

The reports auto-hide writer MUST use `INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (?, ?, 'auto_hide_3_reports') ON CONFLICT (target_type, target_id, trigger) DO NOTHING`. Repeat submissions that cross the threshold again (e.g., 4th, 5th reporter, or a racing concurrent submission) MUST NOT produce duplicate queue rows.

#### Scenario: Second auto-hide attempt on same target is a no-op
- **WHEN** the threshold has already been crossed (queue row exists) AND a 4th aged reporter submits
- **THEN** the `INSERT ... ON CONFLICT DO NOTHING` succeeds as a no-op AND `SELECT COUNT(*) FROM moderation_queue WHERE target_type = ? AND target_id = ? AND trigger = 'auto_hide_3_reports'` returns exactly 1

#### Scenario: Concurrent race — both INSERTs attempt, only one row persists
- **WHEN** two concurrent reports both cross the threshold on the same target (tx A and tx B)
- **THEN** exactly one `moderation_queue` row exists for that (target_type, target_id, 'auto_hide_3_reports') tuple AND neither transaction fails

### Requirement: Priority stays at default for V9 writes

V9 MUST NOT customize `priority` on auto-hide inserts; the default value of 5 is authoritative. Future changes MAY introduce tiered priorities (e.g., CSAM writes with `priority = 1`), but V9 does not.

#### Scenario: auto_hide_3_reports rows have priority = 5
- **WHEN** querying `SELECT priority FROM moderation_queue WHERE trigger = 'auto_hide_3_reports'`
- **THEN** every returned row has `priority = 5`

### Requirement: No reader endpoint in V9

V9 MUST NOT expose any admin or user-facing endpoint that reads `moderation_queue`. The Phase 3.5 admin panel owns that reader. V9 code is a write-only producer.

#### Scenario: No GET endpoint for moderation_queue
- **WHEN** the V9-era backend is fully deployed
- **THEN** no route matches `GET /admin/moderation-queue` or any V9-introduced route that returns `moderation_queue` rows
