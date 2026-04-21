## ADDED Requirements

### Requirement: post_replies table added via Flyway V8

V8 SHALL introduce the `post_replies` table with two FK references — one to `posts(id)` with `ON DELETE CASCADE` (on `post_id`) and one to `users(id)` with `ON DELETE RESTRICT` (on `author_id`). See the `post-replies` capability for the full table definition, partial indexes, and constraints.

The `author_id RESTRICT` behavior MUST mirror V4 `posts.author_id`: a `users` row with at least one non-tombstoned `post_replies` row CANNOT be hard-deleted directly — the tombstone / hard-delete worker (separate future change) is responsible for removing the user's replies before the user row. This is the FIRST `RESTRICT`-side FK on `users(id)` added since V4, after V5 `user_blocks` (CASCADE on both sides), V6 `follows` (CASCADE on both sides), and V7 `post_likes` (CASCADE on both sides).

The `post_id CASCADE` behavior mirrors V7 `post_likes.post_id`: a `posts` hard-delete cascades through both tables in the same transaction.

The V4 `posts.author_id RESTRICT` invariant still applies and is NOT affected by V8. The V5 `user_blocks`, V6 `follows`, and V7 `post_likes` CASCADE behaviors are likewise unchanged.

#### Scenario: V8 adds two FKs from post_replies
- **WHEN** querying `information_schema.table_constraints WHERE table_name = 'post_replies' AND constraint_type = 'FOREIGN KEY'`
- **THEN** there are exactly two FK rows — one referencing `users(id)` (for `author_id`) and one referencing `posts(id)` (for `post_id`)

#### Scenario: author_id FK delete rule is RESTRICT
- **WHEN** querying `information_schema.referential_constraints` for the FK on `post_replies.author_id`
- **THEN** the `delete_rule` column equals `RESTRICT` (or equivalently `NO ACTION` with the documented RESTRICT semantics per the V4 convention)

#### Scenario: post_id FK delete rule is CASCADE
- **WHEN** querying `information_schema.referential_constraints` for the FK on `post_replies.post_id`
- **THEN** the `delete_rule` column equals `CASCADE`

#### Scenario: Hard-delete of user blocked while live replies exist
- **WHEN** a `users` row has at least one `post_replies` row with `deleted_at IS NULL` AND no `posts` rows AND a direct `DELETE FROM users WHERE id = ?` is attempted
- **THEN** the DELETE fails with a foreign-key violation (SQLSTATE `23503`)

#### Scenario: Hard-delete of user succeeds after replies removed
- **WHEN** all `post_replies` rows for a user are hard-deleted (NOT just soft-deleted) AND the user has no `posts` rows AND `DELETE FROM users WHERE id = ?` is attempted
- **THEN** the user row is deleted successfully (and V5 `user_blocks`, V6 `follows`, V7 `post_likes` CASCADE behaviors run as usual)

#### Scenario: Soft-deleted replies still block user hard-delete
- **WHEN** every `post_replies` row for a user has `deleted_at IS NOT NULL` but the rows still exist AND `DELETE FROM users WHERE id = ?` is attempted
- **THEN** the DELETE fails with a foreign-key violation — RESTRICT looks at row existence, not at the `deleted_at` column

#### Scenario: Cascade on posts delete — post side
- **WHEN** a `posts` row referenced by `post_replies.post_id` is hard-deleted
- **THEN** all matching `post_replies` rows are cascade-deleted AND no orphan row remains

#### Scenario: V4 RESTRICT still applies after V8
- **WHEN** a user has at least one `posts` row AND a direct `DELETE FROM users WHERE id = ?` is attempted
- **THEN** the DELETE fails with a foreign-key violation (V8 does NOT override V4 RESTRICT)

#### Scenario: Full cascade chain on user hard-delete
- **WHEN** a `users` row has `follows`, `user_blocks`, `post_likes`, AND `post_replies` rows (the last hard-deleted first) AND no `posts` rows AND the row is hard-deleted at the DB level
- **THEN** every matching `follows`, `user_blocks`, and `post_likes` row is cascade-deleted in the same transaction AND the `post_replies` table's RESTRICT is not tripped (because all reply rows were pre-removed)
