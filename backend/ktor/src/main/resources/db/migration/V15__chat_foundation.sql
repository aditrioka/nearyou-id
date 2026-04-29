-- Chat foundation: 1:1 conversations + participants (slot-based 2-cap) + chat_messages.
-- Canonical DDL verbatim from docs/05-Implementation.md § Direct Messaging Implementation.
--
-- Dependency shape:
--   * `conversations.created_by` and `chat_messages.sender_id` use ON DELETE RESTRICT
--     (canonical) — hard-delete of a user with chat history is a Phase 3.5 cleanup-
--     worker concern; see chat-foundation/design.md § D2.
--   * `conversation_participants.user_id` uses ON DELETE RESTRICT for the same reason;
--     `conversation_id` uses ON DELETE CASCADE so deleting a conversation cleans up
--     participant rows.
--   * `chat_messages.conversation_id` uses ON DELETE CASCADE.
--   * `chat_messages.embedded_post_id` uses ON DELETE SET NULL (the snapshot column
--     preserves the embed render after the post is hard-deleted).
--   * Deferred-FK convention (matches the V9 pattern at V9__reports_moderation.sql:72-73,
--     110-111): `chat_messages.redacted_by` and `chat_messages.embedded_post_edit_id`
--     ship as plain UUID columns with no FK constraint. Each carries a COMMENT ON
--     COLUMN documenting the deferred target so grep/schema-diff tooling stays aware.
--     - `redacted_by` → admin_users(id) ON DELETE SET NULL, deferred to Phase 3.5
--     - `embedded_post_edit_id` → post_edits(id) ON DELETE SET NULL, deferred to the
--       future `post-edit-history` change which will ship `post_edits`.
--
-- Realtime RLS policy: V2 drafted `participants_can_subscribe ON realtime.messages`
-- inside a DO block gated on `conversation_participants` existing. When V2 ran,
-- the table did not exist, so the policy was NEVER installed (Flyway is forward-
-- only; V2 cannot re-run). V15 therefore CREATEs the policy directly with the
-- corrected definition: V2's subscriber-side `AND NOT EXISTS (SELECT 1 FROM
-- public.users WHERE id = cp.user_id AND is_shadow_banned = TRUE)` clause is
-- REMOVED — shadow-banned users are allowed to subscribe to their own conversation
-- realtime channels per the invisible-actor model in chat-foundation/design.md § D9.
-- Hiding shadow-banned senders from OTHER readers is a publish-side concern owned
-- by the future `chat-realtime-broadcast` change.

CREATE TABLE conversations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    last_message_at  TIMESTAMPTZ
);

CREATE TABLE conversation_participants (
    conversation_id  UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    slot             SMALLINT NOT NULL CHECK (slot IN (1, 2)),
    joined_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at          TIMESTAMPTZ,
    -- last_read_at: read-receipt / unread-count storage; written by future chat-read-receipts change
    last_read_at     TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, user_id)
);

-- Slot-based 2-active-participant cap: only one row per (conversation_id, slot)
-- among rows with left_at IS NULL. A soft-left row freeing its slot lets a new
-- INSERT take the same slot.
CREATE UNIQUE INDEX conv_slot_unique
    ON conversation_participants (conversation_id, slot)
    WHERE left_at IS NULL;

CREATE INDEX conversation_participants_user_active_idx
    ON conversation_participants (user_id)
    WHERE left_at IS NULL;

CREATE INDEX conversation_participants_conversation_idx
    ON conversation_participants (conversation_id);

CREATE TABLE chat_messages (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id         UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id               UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content                 VARCHAR(2000),
    embedded_post_id        UUID REFERENCES posts(id) ON DELETE SET NULL,
    embedded_post_snapshot  JSONB,
    embedded_post_edit_id   UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    redacted_at             TIMESTAMPTZ,
    redacted_by             UUID,
    redaction_reason        TEXT,
    CHECK (
        content IS NOT NULL
        OR embedded_post_id IS NOT NULL
        OR embedded_post_snapshot IS NOT NULL
    ),
    CHECK (
        (redacted_at IS NULL AND redacted_by IS NULL AND redaction_reason IS NULL)
        OR (redacted_at IS NOT NULL AND redacted_by IS NOT NULL)
    )
);

CREATE INDEX chat_messages_conv_idx
    ON chat_messages (conversation_id, created_at DESC);

CREATE INDEX chat_messages_sender_idx
    ON chat_messages (sender_id, created_at DESC);

CREATE INDEX chat_messages_redacted_idx
    ON chat_messages (redacted_by, redacted_at DESC)
    WHERE redacted_at IS NOT NULL;

COMMENT ON COLUMN chat_messages.redacted_by IS
    'FK to admin_users(id) ON DELETE SET NULL — deferred to the Phase 3.5 admin-users migration.';

COMMENT ON COLUMN chat_messages.embedded_post_edit_id IS
    'FK to post_edits(id) ON DELETE SET NULL — deferred to the post-edit-history change which ships post_edits.';

-- Realtime RLS policy. Plain Postgres has no `realtime` schema so the whole block
-- is skipped (matches the V2 gate at V2__auth_foundation.sql:64-66). On Supabase,
-- V15 installs the policy directly because V2's gated DO block was a one-time
-- no-op (conversation_participants did not exist at V2-time). The subscriber-side
-- `is_shadow_banned` clause from V2 lines 81-84 is REMOVED here per
-- chat-foundation/design.md § D9 — shadow-banned subscribers retain their own
-- realtime view; hiding their messages from other readers is a publish-side concern.
-- Postgres has no CREATE POLICY IF NOT EXISTS, so DROP+CREATE is the idempotent pattern.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'realtime') THEN
        EXECUTE $policy$
            DROP POLICY IF EXISTS participants_can_subscribe ON realtime.messages;
            CREATE POLICY participants_can_subscribe ON realtime.messages
            FOR SELECT USING (
                realtime.topic() ~ '^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                AND EXISTS (
                    SELECT 1 FROM public.conversation_participants cp
                    WHERE cp.conversation_id = (split_part(realtime.topic(), ':', 2))::uuid
                        AND cp.user_id = ((auth.jwt()->>'sub')::uuid)
                        AND cp.left_at IS NULL
                )
            )
        $policy$;
    END IF;
END $$;
