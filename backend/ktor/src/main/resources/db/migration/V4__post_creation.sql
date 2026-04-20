-- Post creation: posts table (PostGIS dual-location) + visible_posts view + indexes.
-- Canonical schema per docs/05-Implementation.md § Posts Schema.
--
-- FK semantics: author_id ON DELETE RESTRICT. The tombstone / hard-delete worker
-- (deferred) deletes posts before the author row; RESTRICT guards against a bare
-- DELETE FROM users leaving orphaned posts or losing moderation state.
--
-- FTS-specific columns (content_tsv + GIN indexes) are deferred to the Search change.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE posts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id           UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content             VARCHAR(280) NOT NULL,
    display_location    GEOGRAPHY(POINT, 4326) NOT NULL,
    actual_location     GEOGRAPHY(POINT, 4326) NOT NULL,
    city_name           TEXT,
    city_match_type     VARCHAR(16),
    image_id            TEXT,
    is_auto_hidden      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX posts_display_location_idx ON posts USING GIST(display_location);
CREATE INDEX posts_actual_location_idx  ON posts USING GIST(actual_location);

-- Timeline cursor (business code MUST go via visible_posts view; the partial
-- predicate on deleted_at is still useful once soft-delete lands).
CREATE INDEX posts_timeline_cursor_idx
    ON posts(created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- Nearby+time composite spatial cursor. Requires btree_gist for timestamptz.
CREATE INDEX posts_nearby_cursor_idx
    ON posts USING GIST(display_location, created_at)
    WHERE deleted_at IS NULL;

-- Business-code read path: all non-admin, non-own-content queries MUST use this
-- view. Enforced at commit time by the `RawFromPostsRule` Detekt rule. Block-
-- exclusion joins land in a later change that introduces `user_blocks`.
CREATE VIEW visible_posts AS
    SELECT * FROM posts WHERE is_auto_hidden = FALSE;
