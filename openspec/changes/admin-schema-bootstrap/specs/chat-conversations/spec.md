## MODIFIED Requirements

### Requirement: Conversation schema

The system SHALL persist 1:1 conversations in three tables: `conversations`, `conversation_participants`, `chat_messages`. The schema SHALL match the canonical specification in [`docs/05-Implementation.md` § Direct Messaging Implementation](../../../../docs/05-Implementation.md) verbatim except for the still-deferred `embedded_post_edit_id` FK constraint (target table `post_edits` does not ship until the future `post-edit-history` change). The `redacted_by` admin-FK is no longer deferred — V16 (`admin-schema-bootstrap`) ships the `admin_users` table AND backfills the FK via `ADD CONSTRAINT ... NOT VALID + VALIDATE CONSTRAINT`.

`conversations` columns: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `last_message_at TIMESTAMPTZ NULL`.

`conversation_participants` columns: `conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE`, `user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `slot SMALLINT NOT NULL CHECK (slot IN (1, 2))`, `joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `left_at TIMESTAMPTZ NULL`, `last_read_at TIMESTAMPTZ NULL`. PRIMARY KEY `(conversation_id, user_id)`. UNIQUE INDEX `conv_slot_unique ON (conversation_id, slot) WHERE left_at IS NULL`. INDEX `(user_id) WHERE left_at IS NULL`. INDEX `(conversation_id)`.

`chat_messages` columns: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE`, `sender_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `content VARCHAR(2000) NULL`, `embedded_post_id UUID NULL REFERENCES posts(id) ON DELETE SET NULL`, `embedded_post_snapshot JSONB NULL`, `embedded_post_edit_id UUID NULL` (FK constraint to `post_edits(id) ON DELETE SET NULL` is DEFERRED until the future `post-edit-history` change ships `post_edits`; the column is documented via `COMMENT ON COLUMN` matching the V9 deferred-FK pattern at [V9__reports_moderation.sql:72-73, 110-111](../../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql). The `post_edits` table does not exist at V14 — verified via migration scan.), `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `redacted_at TIMESTAMPTZ NULL`, `redacted_by UUID NULL REFERENCES admin_users(id) ON DELETE SET NULL` (FK shipped via V16 `admin-schema-bootstrap`; comment replaced from the V15-era deferral text), `redaction_reason TEXT NULL`. CHECKs: empty-message guard `(content IS NOT NULL OR embedded_post_id IS NOT NULL OR embedded_post_snapshot IS NOT NULL)`; redaction atomicity `((redacted_at IS NULL AND redacted_by IS NULL AND redaction_reason IS NULL) OR (redacted_at IS NOT NULL AND redacted_by IS NOT NULL))`. INDEXes: `(conversation_id, created_at DESC)`, `(sender_id, created_at DESC)`, `(redacted_by, redacted_at DESC) WHERE redacted_at IS NOT NULL`.

#### Scenario: Schema applies cleanly via Flyway
- **WHEN** the V15 migration runs against a database that has V1–V14 applied
- **THEN** the three tables, six indexes, and four CHECK constraints are created without error, and `flyway_schema_history` shows V15 as `success = true`

#### Scenario: V15 installs realtime RLS policy with corrected definition
- **WHEN** V15 has applied
- **THEN** the policy `participants_can_subscribe ON realtime.messages` is installed (V15 CREATEs it directly because V2's gated DO block was a no-op when V2 ran with no `conversation_participants` table); the policy body matches V2's intent EXCEPT the subscriber-side `AND NOT EXISTS (SELECT 1 FROM public.users WHERE id = cp.user_id AND is_shadow_banned = TRUE)` clause from V2 lines 81-84 is REMOVED — shadow-banned subscribers are allowed to subscribe to their own conversation realtime channels per the invisible-actor model

#### Scenario: Empty-message CHECK rejects fully empty INSERT
- **WHEN** an INSERT is attempted with `content IS NULL`, `embedded_post_id IS NULL`, AND `embedded_post_snapshot IS NULL`
- **THEN** the INSERT is rejected with a CHECK constraint violation; the row is not persisted

#### Scenario: Empty-message CHECK accepts snapshot-only row
- **WHEN** an INSERT has `content IS NULL`, `embedded_post_id IS NULL`, but `embedded_post_snapshot` populated (the canonical scenario where an embedded post was hard-deleted but the snapshot survives)
- **THEN** the INSERT succeeds

#### Scenario: Redaction atomicity CHECK rejects half-set state
- **WHEN** an UPDATE is attempted that sets `redacted_at` non-null but leaves `redacted_by NULL` (or vice versa)
- **THEN** the UPDATE is rejected with a CHECK constraint violation

#### Scenario: Redaction atomicity CHECK accepts redaction with NULL reason
- **WHEN** an UPDATE sets `redacted_at = NOW()`, `redacted_by = <admin_id>`, and leaves `redaction_reason NULL`
- **THEN** the UPDATE succeeds (the reason is optional free-text)

#### Scenario: Slot CHECK rejects out-of-range values
- **WHEN** an INSERT into `conversation_participants` has `slot = 3` (or 0, or NULL)
- **THEN** the INSERT is rejected with a CHECK constraint violation

#### Scenario: Slot partial unique blocks a third active participant
- **GIVEN** a conversation with two active participants (`slot = 1` and `slot = 2`, both `left_at IS NULL`)
- **WHEN** a third INSERT is attempted with `slot = 1` and `left_at IS NULL`
- **THEN** the INSERT is rejected with a unique constraint violation on `conv_slot_unique`

#### Scenario: redacted_by FK exists post-V16 and is validated
- **WHEN** the integration-test Kotest spec queries `pg_constraint` for `chat_messages` with `contype = 'f'` AND `conname = 'chat_messages_redacted_by_fkey'`
- **THEN** exactly one row is returned with `confrelid` referencing `admin_users` AND `confdeltype = 'n'` (`ON DELETE SET NULL`) AND `convalidated = true`

#### Scenario: redacted_by deferred-comment text removed post-V16
- **WHEN** querying `pg_description` for the `redacted_by` column of `chat_messages`
- **THEN** the comment does NOT contain the substring `'deferred to the Phase 3.5 admin-users migration'` AND describes the now-shipped FK relationship (mentions `admin_users(id)` AND `SET NULL`)

#### Scenario: embedded_post_edit_id FK remains deferred post-V16
- **WHEN** the integration-test Kotest spec queries `pg_constraint` for `chat_messages` looking for a FK on `embedded_post_edit_id`
- **THEN** no row is returned (the FK target table `post_edits` does not exist yet; this deferral is independent of the V16 admin-FK work and waits for the future `post-edit-history` change)

#### Scenario: redacted_by FK rejects INSERT with bogus admin_id
- **WHEN** an INSERT into `chat_messages` is attempted with `redacted_at` and `redaction_reason` set AND `redacted_by` set to a random UUID that does NOT exist in `admin_users(id)`
- **THEN** Postgres rejects the insert with a `foreign_key_violation` (SQLState `23503`) error referencing the `chat_messages_redacted_by_fkey` constraint
