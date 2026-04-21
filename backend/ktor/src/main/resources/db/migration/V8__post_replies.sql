-- Post replies: author-soft-deletable, block-aware, 280-char text replies on posts.
-- Canonical DDL verbatim from docs/05-Implementation.md §716–729 — no renames.
--
-- Dependency shape (fourth distinct shape in the migration pipeline):
--   * Schema-level joint FK: references V3 (`users.id`) AND V4 (`posts.id`). Unlike
--     V5 (`user_blocks`, both-side CASCADE), V6 (`follows`, both-side CASCADE), and
--     V7 (`post_likes`, both-side CASCADE), V8 uses ASYMMETRIC delete rules:
--       - `post_id   REFERENCES posts(id) ON DELETE CASCADE`
--       - `author_id REFERENCES users(id) ON DELETE RESTRICT`
--     This is the FIRST new `RESTRICT`-side FK on `users(id)` since V4
--     `posts.author_id`. Rationale: replies carry user-visible content (a 280-char
--     string) plus moderation state (`is_auto_hidden`), and the tombstone /
--     hard-delete worker (separate future change) must explicitly remove replies
--     before the author row — a bare `DELETE FROM users` must not silently
--     vaporize content + audit trail. The V4 `posts.author_id` RESTRICT is the
--     precedent; V8 mirrors it.
--   * No runtime coupling — no other service's behavior changes when V8 applies.
--     Unlike V6 (BlockService.block() cascades `follows` + FollowService reads
--     `user_blocks` for mutual-block rejection), V8 introduces no cross-migration
--     runtime join. The new `ReplyService` POST/GET/DELETE and the timelines'
--     `LEFT JOIN LATERAL` reply-counter ship in the same deploy as V8 (single-
--     release coupling, not runtime dependency across migrations). Same "no
--     runtime coupling" shape as V7.
--
-- V8-era consumers of `post_replies`:
--   * ReplyService POST `/api/v1/posts/{post_id}/replies` (create).
--   * ReplyService GET  `/api/v1/posts/{post_id}/replies` (keyset list, block-
--     aware, soft-delete-hiding, auto-hidden-with-author-bypass).
--   * ReplyService DELETE `/api/v1/posts/{post_id}/replies/{reply_id}` (author-only
--     soft-delete; opaque 204 across not-yours / already-tombstoned / never-existed).
--   * NearbyTimelineService + FollowingTimelineService `reply_count` projection via
--     `LEFT JOIN LATERAL (SELECT COUNT(*) ... JOIN visible_users ...)` — shadow-ban
--     exclusion applied; viewer-block exclusion deliberately NOT applied (privacy
--     tradeoff; per-viewer count leaks block state).
--   * BlockExclusionJoinLintTest gains the first production fixtures exercising
--     `post_replies` reader queries (positive-pass + positive-fail variants).
--
-- Future-cascade convention: if a later table references a `post_replies` row (e.g.
-- `reports.target_type = 'reply'`), it MUST add its own cascade on `post_replies`
-- row delete. Bidirectional block cascade onto `post_replies` is deliberately OUT
-- of scope for V8 (same rationale as V7 post-likes-v7 design Decision 10 — the
-- block-exclusion join at read time is sufficient).

CREATE TABLE post_replies (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id        UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    author_id      UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content        VARCHAR(280) NOT NULL,
    is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ,
    deleted_at     TIMESTAMPTZ
);

-- Directional partial indexes filtered by `deleted_at IS NULL`. Soft-deleted rows
-- stay in the table for moderation tooling + the eventual tombstone-and-cascade
-- worker, but never appear on the hot read path; the partial filter keeps both
-- indexes lean.
--  * `post_replies_post_idx`: canonical reply-list access pattern — "visible
--    replies on post P, newest first" — keyset on (post_id, created_at DESC).
--    Powers GET /api/v1/posts/{post_id}/replies AND the timelines' LATERAL
--    reply-count sub-scalar.
--  * `post_replies_author_idx`: "my replies, newest first" — keyset on
--    (author_id, created_at DESC). Powers future "my replies" list endpoint +
--    admin-side per-user reply audit.
CREATE INDEX post_replies_post_idx   ON post_replies (post_id, created_at DESC)   WHERE deleted_at IS NULL;
CREATE INDEX post_replies_author_idx ON post_replies (author_id, created_at DESC) WHERE deleted_at IS NULL;
