-- Post edit history (race-safe temporal versioning) per docs/05-Implementation.md
-- § Post Edit History §367. Backs the `premium-post-editing` capability:
-- `PATCH /api/v1/posts/{post_id}` records the BEFORE-edit content + location as an
-- append-only `post_edits` row inside the same transaction that updates the post.
--
-- Append-only, retention until parent post hard-delete (ON DELETE CASCADE). No
-- `posts` ALTER — the existing `content` / `updated_at` columns carry the edit.
--
-- Partial-index-clean: neither index has a `WHERE` clause, so no `NOW()`-in-WHERE
-- (partial-index invariant). The temporal UNIQUE index is the second line of
-- defence behind the service's `SELECT ... FOR UPDATE` serialization; the
-- sub-microsecond `clock_timestamp()` collision edge is handled by a single
-- app-level retry → 409 "Coba lagi sebentar." (docs/05 § Transactional Atomicity).

CREATE TABLE post_edits (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id           UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    edited_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    content_snapshot  VARCHAR(280) NOT NULL,
    location_snapshot GEOGRAPHY NOT NULL,
    edited_by         UUID NOT NULL REFERENCES users(id)
);

-- Temporal uniqueness: guarantees no two snapshots for one post share an
-- `edited_at`, so the "Versi ke-N" ROW_NUMBER ordering is total + stable.
CREATE UNIQUE INDEX post_edits_temporal_idx ON post_edits(post_id, edited_at);

-- History read path (most-recent-first); also serves the cascade-delete lookup.
CREATE INDEX post_edits_post_id_idx ON post_edits(post_id, edited_at DESC);
