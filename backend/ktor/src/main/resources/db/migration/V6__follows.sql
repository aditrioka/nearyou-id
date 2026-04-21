-- Follows: directional social graph.
--
-- Dependency shapes (distinct — V5 was the first joint schema-level dependency on V3+V4;
-- V6 is the first split schema-vs-runtime dependency):
--   * Schema-level: FK targets on V3 (`users.id`) via ON DELETE CASCADE on both sides.
--   * Runtime-level: couples to V5 (`user_blocks`) in business code — `BlockService.block()`
--     now executes a transactional INSERT INTO user_blocks + bidirectional DELETE FROM
--     follows, and POST /api/v1/follows/{user_id} reads `user_blocks` before the INSERT to
--     reject mutual-block follows with 409. See capability specs `user-blocking` and
--     `follow-system` for the full contract. NO DB trigger / FK coupling between
--     `user_blocks` and `follows` is created here; the invariant is enforced in app code.
--
-- Column-naming override: docs/05-Implementation.md §669–687 uses the name `followed_id`.
-- V6 deliberately renames that column to `followee_id` for grammatical symmetry with
-- `follower_id` (the one being followed), and because `followed_id` — a past-participle
-- form — collides with the idiomatic boolean column `followed` that future code may want.
-- docs/05-Implementation.md §669, §1057–1067, §1124, and §1286–1300 are updated in the
-- same PR so the doc and schema agree.
--
-- Read-path consumers that MUST bidirectionally join `user_blocks` on top of
-- `visible_posts` (per the `block-exclusion-lint` rule): `nearby-timeline` (shipped in
-- V5) and `following-timeline` (this change). The enumerated set grows — it never
-- shrinks. Future Global timeline joins the same convention.
--
-- Future-cascade convention inherited from V5: if new tables couple to `follows`
-- relationships (e.g. "recommended because X follows Y"), they MUST add their own
-- cascade on `follows` row delete.

CREATE TABLE follows (
    follower_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    followee_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT follows_no_self_follow CHECK (follower_id <> followee_id),
    PRIMARY KEY (follower_id, followee_id)
);

-- Directional indexes supporting the two canonical access patterns:
--  * `follows_follower_idx`: "who does X follow (newest first)" — keyset on
--    `(follower_id, created_at DESC)`; powers GET /users/{id}/following and the
--    Following timeline's inner `SELECT followee_id FROM follows WHERE follower_id = :viewer`.
--  * `follows_followee_idx`: "who follows X (newest first)" — keyset on
--    `(followee_id, created_at DESC)`; powers GET /users/{id}/followers.
CREATE INDEX follows_follower_idx ON follows (follower_id, created_at DESC);
CREATE INDEX follows_followee_idx ON follows (followee_id, created_at DESC);
