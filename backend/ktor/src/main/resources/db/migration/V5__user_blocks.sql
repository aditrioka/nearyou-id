-- User blocks: bidirectional block list.
--
-- Joint dependency: V3 (`users` table) + V4 (`posts` / `visible_posts`). The Nearby
-- timeline read path consumes this table; see capability `user-blocking` and
-- `nearby-timeline`. Canonical query in docs/05-Implementation.md § Timeline
-- Implementation embeds two NOT-IN subqueries against this table — one excluding
-- viewer-blocked authors, one excluding authors who blocked the viewer.
--
-- Cascade convention for FUTURE block-coupled tables: any later table where a block
-- between two users implies a state cleanup (e.g. follows, mutes, reports) MUST add
-- its own ON DELETE / ON UPSERT cascade or trigger to remove the coupled rows when
-- a block is created or a user is hard-deleted. This migration does NOT add a
-- follows cascade because the `follows` table does not yet exist; that cascade
-- lands with the follow system.

CREATE TABLE user_blocks (
    blocker_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT user_blocks_no_self_block CHECK (blocker_id <> blocked_id),
    PRIMARY KEY (blocker_id, blocked_id)
);

-- The PRIMARY KEY above also serves as the (blocker_id, blocked_id) directional index.
-- The reverse-direction index is needed for the second NOT-IN subquery in the Nearby
-- query, where the predicate is `WHERE blocked_id = :viewer`.
CREATE INDEX user_blocks_blocked_idx ON user_blocks (blocked_id, blocker_id);
