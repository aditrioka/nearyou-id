-- V38 — embedded-snapshot-author-erasure (follow-up #425).
--
-- chat_messages.embedded_post_snapshot freezes the post author's handle + display name at share
-- time (chat-embedded-posts). On account tombstone the account-hard-delete worker scrubs those two
-- keys at rest to the tombstoned users values; this column is the linkage it keys on — the
-- share-time post author, written by the send path. It is deliberately independent of
-- embedded_post_id, which is ON DELETE SET NULL and is lost once the source post is hard-deleted
-- (docs/06 retention: soft-deleted posts are purged after 30 days). Never serialized to clients.
--
-- Every statement is idempotent (IF NOT EXISTS / IS NULL / IS DISTINCT FROM guards), so
-- MigrationV38SmokeTest can re-execute this file against pre-V38-shaped seed rows.

ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS embedded_post_author_id UUID REFERENCES users(id) ON DELETE SET NULL;

COMMENT ON COLUMN chat_messages.embedded_post_author_id IS
    'Share-time author of the embedded post; FK users(id) ON DELETE SET NULL. Erasure linkage only: '
    'the account-hard-delete worker scrubs embedded_post_snapshot author identity by this column. '
    'Never serialized to clients.';

-- Backfill the linkage for embeds shared before V38. Every pre-V38 embed still has its source
-- post: no production post hard-delete path exists before V38.
UPDATE chat_messages cm
   SET embedded_post_author_id = p.author_id
  FROM posts p
 WHERE p.id = cm.embedded_post_id
   AND cm.embedded_post_author_id IS NULL;

-- Retro-scrub authors tombstoned before V38 shipped (erasure is not only forward-looking). Same
-- overwrite the worker applies: the two identity keys take the tombstoned users values.
UPDATE chat_messages cm
   SET embedded_post_snapshot = cm.embedded_post_snapshot
       || jsonb_build_object('authorUsername', u.username, 'authorDisplayName', u.display_name)
  FROM users u
 WHERE u.id = cm.embedded_post_author_id
   AND u.deleted_at IS NOT NULL
   AND cm.embedded_post_snapshot IS NOT NULL
   AND (cm.embedded_post_snapshot ->> 'authorUsername' IS DISTINCT FROM u.username
        OR cm.embedded_post_snapshot ->> 'authorDisplayName' IS DISTINCT FROM u.display_name);

-- NOW()-free partial index (partial-index invariant); embeds are a small slice of chat rows.
CREATE INDEX IF NOT EXISTS chat_messages_embedded_post_author_idx
    ON chat_messages (embedded_post_author_id)
    WHERE embedded_post_author_id IS NOT NULL;
