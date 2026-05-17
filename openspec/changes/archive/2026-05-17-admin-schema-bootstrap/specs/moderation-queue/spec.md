## MODIFIED Requirements

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
- `resolved_by UUID` (nullable; FK to `admin_users(id) ON DELETE SET NULL` shipped via the V16 `admin-schema-bootstrap` migration; the FK is validated via `ADD CONSTRAINT ... NOT VALID + VALIDATE CONSTRAINT`)
- `notes TEXT` (nullable)
- `UNIQUE (target_type, target_id, trigger)`

Plus two indexes:
- `moderation_queue_status_idx ON moderation_queue(status, priority, created_at)`
- `moderation_queue_target_idx ON moderation_queue(target_type, target_id)`

The `resolved_by` column carries a `COMMENT ON COLUMN` describing its FK to `admin_users(id) ON DELETE SET NULL`. The V9 migration shipped the column with a deferral comment ("deferred to the Phase 3.5 admin-users migration"); V16 replaces that comment with the now-shipped FK description (mirrors the `reports.reviewed_by` pattern).

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

#### Scenario: resolved_by FK exists post-V16 and is validated
- **WHEN** the integration-test Kotest spec queries `pg_constraint` for `moderation_queue` with `contype = 'f'` AND `conname = 'moderation_queue_resolved_by_fkey'`
- **THEN** exactly one row is returned with `confrelid` referencing `admin_users` AND `confdeltype = 'n'` (`ON DELETE SET NULL`) AND `convalidated = true`

#### Scenario: resolved_by deferred-comment text removed post-V16
- **WHEN** querying `pg_description` for the `resolved_by` column of `moderation_queue`
- **THEN** the comment does NOT contain the substring `'deferred to the Phase 3.5 admin-users migration'` AND describes the now-shipped FK relationship (mentions `admin_users(id)` AND `SET NULL`)
