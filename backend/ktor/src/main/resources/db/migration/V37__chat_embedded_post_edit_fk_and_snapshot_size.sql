-- Embedded-post chat sharing (chat-embedded-posts) — the two deferred schema pieces.
--
-- The V15 chat foundation shipped chat_messages.embedded_post_id / embedded_post_snapshot /
-- embedded_post_edit_id as null-only, forward-compatible columns; the empty-message CHECK
-- already permits a snapshot-only row. Two pieces were deferred:
--
--   1. embedded_post_edit_id → post_edits(id) ON DELETE SET NULL. Deferred at V15 until
--      post_edits existed (shipped V22). Now backfilled via the V9/V16 deferred-FK pattern
--      (ADD CONSTRAINT ... NOT VALID + VALIDATE CONSTRAINT — no long write lock; existing
--      rows are all NULL so VALIDATE is trivially satisfied). This is the "version-at-share-
--      time" anchor: the mobile thread compares it against the live post's latest edit to
--      decide whether to show the "diedit sejak dibagikan" banner.
--
--   2. embedded_post_snapshot size cap. chat-realtime-broadcast § "Embedded-payload size cap"
--      names chat-embedded-posts as the owner. A schema CHECK bounds the snapshot so an
--      oversized JSONB can never be persisted or broadcast, keeping the Supabase Realtime
--      broadcast payload within its per-message limit. 4096 bytes verified (2026-06-26) to
--      sit far under Supabase Realtime broadcast's configurable max_payload_size_in_kb
--      (hosted default ~256 KB, with a 500-byte padding); a 280-char post + author/city/
--      timestamp metadata serializes well under 4 KB. The `IS NULL OR` guard mirrors the
--      empty-message CHECK shape so a NULL snapshot (every plain message) trivially passes.

-- FK backfill: chat_messages.embedded_post_edit_id → post_edits(id) ON DELETE SET NULL.
-- Replaces the V15-era deferred-FK COMMENT. ON DELETE SET NULL so a hard-deleted edit row
-- does not cascade-delete the chat message (the snapshot stays intact; the anchor just
-- becomes NULL). Existing embedded_post_edit_id values are all NULL → VALIDATE is trivial.
ALTER TABLE chat_messages
    ADD CONSTRAINT chat_messages_embedded_post_edit_id_fkey
    FOREIGN KEY (embedded_post_edit_id) REFERENCES post_edits(id) ON DELETE SET NULL
    NOT VALID;

ALTER TABLE chat_messages VALIDATE CONSTRAINT chat_messages_embedded_post_edit_id_fkey;

COMMENT ON COLUMN chat_messages.embedded_post_edit_id IS
    'FK to post_edits(id) ON DELETE SET NULL — the version-at-share-time anchor; set to NULL when the anchored edit row is hard-deleted, while the embedded_post_snapshot is preserved.';

-- Snapshot size cap. NOT VALID + VALIDATE for consistency with the FK above (all existing
-- snapshots are NULL so VALIDATE passes immediately). The `IS NULL OR` guard short-circuits
-- for every plain (non-embed) message.
ALTER TABLE chat_messages
    ADD CONSTRAINT chat_messages_embedded_snapshot_size_check
    CHECK (embedded_post_snapshot IS NULL OR octet_length(embedded_post_snapshot::text) < 4096)
    NOT VALID;

ALTER TABLE chat_messages VALIDATE CONSTRAINT chat_messages_embedded_snapshot_size_check;
