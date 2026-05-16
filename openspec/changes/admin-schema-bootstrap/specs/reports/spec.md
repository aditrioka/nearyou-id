## MODIFIED Requirements

### Requirement: reports table created via Flyway V9

A migration `V9__reports_moderation.sql` SHALL create the `reports` table verbatim-aligned with `docs/05-Implementation.md` §745–775 with columns:
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('post', 'reply', 'user', 'chat_message'))`
- `target_id UUID NOT NULL`
- `reason_category VARCHAR(32) NOT NULL CHECK (reason_category IN ('spam', 'hate_speech_sara', 'harassment', 'adult_content', 'misinformation', 'self_harm', 'csam_suspected', 'other'))`
- `reason_note VARCHAR(200)` (nullable)
- `status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'actioned', 'dismissed'))`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `reviewed_at TIMESTAMPTZ` (nullable)
- `reviewed_by UUID` (nullable; FK to `admin_users(id) ON DELETE SET NULL` shipped via the V16 `admin-schema-bootstrap` migration; the FK is validated via `ADD CONSTRAINT ... NOT VALID + VALIDATE CONSTRAINT` per the deferral comment's prescription)
- `UNIQUE (reporter_id, target_type, target_id)`

Plus three indexes:
- `reports_status_idx ON reports(status, created_at DESC)`
- `reports_target_idx ON reports(target_type, target_id)`
- `reports_reporter_idx ON reports(reporter_id, created_at DESC)`

The `reviewed_by` column carries a `COMMENT ON COLUMN` describing its FK to `admin_users(id) ON DELETE SET NULL`. The V9 migration shipped the column with a deferral comment ("deferred to the Phase 3.5 admin-users migration"); V16 replaces that comment with the now-shipped FK description.

#### Scenario: Migration runs cleanly from V8
- **WHEN** Flyway runs `V9__reports_moderation.sql` against a DB at V8
- **THEN** the migration succeeds AND `flyway_schema_history` records V9

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'reports'`
- **THEN** every column above is present with its documented type, nullability, and default

#### Scenario: UNIQUE constraint on (reporter_id, target_type, target_id)
- **WHEN** two INSERTs attempt the same `(reporter_id, target_type, target_id)` tuple
- **THEN** the second INSERT fails with SQLSTATE `23505` (unique-violation)

#### Scenario: target_type CHECK rejects out-of-enum value
- **WHEN** an INSERT supplies `target_type = 'meme'`
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: reason_category CHECK rejects out-of-enum value
- **WHEN** an INSERT supplies `reason_category = 'political_disagreement'`
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: status CHECK rejects out-of-enum value
- **WHEN** an INSERT supplies `status = 'escalated'`
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: All three indexes exist
- **WHEN** querying `pg_indexes WHERE tablename = 'reports'`
- **THEN** the result contains `reports_status_idx`, `reports_target_idx`, AND `reports_reporter_idx`

#### Scenario: reporter_id CASCADE removes reports on user delete
- **WHEN** a `users` row is hard-deleted (e.g. via the tombstone worker when no other RESTRICT FKs block)
- **THEN** every `reports` row referencing that user as `reporter_id` is cascade-deleted in the same transaction

#### Scenario: reviewed_by FK exists post-V16 and is validated
- **WHEN** the integration-test Kotest spec queries `pg_constraint` for `reports` with `contype = 'f'` AND `conname = 'reports_reviewed_by_fkey'`
- **THEN** exactly one row is returned with `confrelid` referencing `admin_users` AND `confdeltype = 'n'` (`ON DELETE SET NULL`) AND `convalidated = true`

#### Scenario: reviewed_by deferred-comment text removed post-V16
- **WHEN** querying `pg_description` for the `reviewed_by` column of `reports`
- **THEN** the comment does NOT contain the substring `'deferred to the Phase 3.5 admin-users migration'` AND describes the now-shipped FK relationship (mentions `admin_users(id)` AND `SET NULL`)

#### Scenario: Admin row hard-DELETE SETs reports.reviewed_by to NULL
- **GIVEN** an `admin_users` row exists with no `admin_actions_log` references (e.g., a freshly inserted test-fixture admin) AND a `reports` row exists with `reviewed_by` referencing that admin
- **WHEN** the `admin_users` row is hard-deleted
- **THEN** the `reports.reviewed_by` column on that row is updated to NULL atomically by the FK's ON DELETE SET NULL action
