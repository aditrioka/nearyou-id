## ADDED Requirements

### Requirement: post_likes table added via Flyway V7

V7 SHALL introduce the `post_likes` table referencing BOTH `users(id)` AND `posts(id)` with `ON DELETE CASCADE`. A `users` hard-delete MUST cascade-remove every `post_likes` row where the deleted user is the liker (`user_id`). A `posts` hard-delete MUST cascade-remove every `post_likes` row for that post (`post_id`). See the `post-likes` capability for the full table definition, PK, and indexes.

The V4 `posts.author_id RESTRICT` invariant from V4 still applies and is NOT affected by V7: a `users` row with posts cannot be directly hard-deleted, independent of that user's `post_likes` rows. When the tombstone worker removes the user's posts and then deletes the user row, the V7 cascade removes the user's liked-by rows AND the per-post like rows in the same transaction as the existing V5 `user_blocks` and V6 `follows` cascades.

#### Scenario: V7 adds two FKs from post_likes
- **WHEN** querying `information_schema.table_constraints WHERE table_name = 'post_likes' AND constraint_type = 'FOREIGN KEY'`
- **THEN** there are exactly two FK rows — one referencing `users(id)` (for `user_id`) and one referencing `posts(id)` (for `post_id`)

#### Scenario: Cascade on users delete — liker side
- **WHEN** a `users` row referenced by `post_likes.user_id` is hard-deleted AND the user has no `posts` rows (so V4 RESTRICT does not block)
- **THEN** all matching `post_likes` rows are cascade-deleted AND no orphan row remains

#### Scenario: Cascade on posts delete — post side
- **WHEN** a `posts` row referenced by `post_likes.post_id` is hard-deleted
- **THEN** all matching `post_likes` rows are cascade-deleted AND no orphan row remains

#### Scenario: V4 RESTRICT still applies after V7
- **WHEN** a user has at least one `posts` row AND a direct `DELETE FROM users WHERE id = ?` is attempted
- **THEN** the DELETE fails with a foreign-key violation (V7 cascade does NOT override V4 RESTRICT)

#### Scenario: users hard-delete with follows, user_blocks, and post_likes rows
- **WHEN** a `users` row has `follows`, `user_blocks`, AND `post_likes` rows (as liker) AND no `posts` rows referencing it AND the row is hard-deleted at the DB level
- **THEN** every matching `follows`, `user_blocks`, AND `post_likes` row is cascade-deleted in the same transaction
