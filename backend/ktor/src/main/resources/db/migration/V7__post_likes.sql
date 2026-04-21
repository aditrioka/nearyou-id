-- Post likes: caller-scoped engagement primitive on `posts`.
--
-- Dependency shape (third distinct shape in the migration pipeline):
--   * Schema-level joint FK: references V3 (`users.id`) AND V4 (`posts.id`), both with
--     ON DELETE CASCADE. A `users` hard-delete cascades through `post_likes` (liker
--     side); a `posts` hard-delete cascades through `post_likes` (post side). V4's
--     `posts.author_id ON DELETE RESTRICT` invariant is NOT affected here.
--   * No runtime coupling — unlike V6 (`follows` + bidirectional mutual-block read in
--     BlockService / FollowService), V7 introduces no cross-migration runtime join. The
--     new LEFT JOIN in NearbyTimelineService / FollowingTimelineService ships in the
--     same artifact as V7 (single-release coupling, not runtime dependency across
--     migrations).
--
-- V7-era consumers of `post_likes`:
--   * LikeService writes (POST/DELETE /api/v1/posts/{post_id}/like).
--   * Likes count endpoint (GET /api/v1/posts/{post_id}/likes/count), joining
--     `visible_users` for shadow-ban-aware aggregation.
--   * NearbyTimelineService + FollowingTimelineService `liked_by_viewer` LEFT JOIN.
--
-- Column names are verbatim from docs/05-Implementation.md §694–702 — no renames.
--
-- Future-cascade convention: if a later table references a `post_likes` row (e.g. a
-- `like_notifications` table), it MUST add its own cascade on `post_likes` row delete.
-- Likewise, bidirectional block cascade onto `post_likes` is deliberately OUT of scope
-- for V7 (see post-likes-v7 design Decision 10).

CREATE TABLE post_likes (
    post_id    UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (post_id, user_id)
);

-- Directional indexes supporting the two canonical access patterns:
--  * `post_likes_user_idx`: "which posts has X liked (newest first)" — keyset on
--    (user_id, created_at DESC). Powers future "my likes" list endpoint.
--  * `post_likes_post_idx`: "who has liked this post (newest first)" — keyset on
--    (post_id, created_at DESC). Powers the count query (filter by post_id) and
--    future liker-list endpoint.
CREATE INDEX post_likes_user_idx ON post_likes (user_id, created_at DESC);
CREATE INDEX post_likes_post_idx ON post_likes (post_id, created_at DESC);

-- visible_users view: mirrors docs/05-Implementation.md §1813. Counter aggregations
-- (likes, replies) JOIN this view to exclude shadow-banned contributors from public
-- counters. This is the first V7-era consumer; `visible_posts` already joins `users`
-- internally for the same reason, but code reading `post_likes` directly (the count
-- query) needs the view in its own right.
CREATE VIEW visible_users AS
    SELECT u.*
    FROM users u
    WHERE u.deleted_at IS NULL
      AND u.is_shadow_banned = FALSE;
