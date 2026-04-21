## ADDED Requirements

### Requirement: user_blocks table referenced from users via V5

As of V5, `users.id` MUST be referenced TWICE by `user_blocks` (once as `blocker_id`, once as `blocked_id`), each with `ON DELETE CASCADE`. A hard-delete of a `users` row SHALL therefore cascade to remove all outbound and inbound block rows for that user. The `posts.author_id RESTRICT` invariant from V4 still applies; the V5 cascade does not relax it (a user with posts still cannot be hard-deleted directly — posts must be removed first by the tombstone worker).

#### Scenario: V5 adds two FKs from user_blocks to users
- **WHEN** querying `information_schema.table_constraints WHERE table_name = 'user_blocks' AND constraint_type = 'FOREIGN KEY'`
- **THEN** there are exactly two FK rows referencing `users(id)` (one for `blocker_id`, one for `blocked_id`)

#### Scenario: Cascade removes block rows for hard-deleted user (both directions)
- **WHEN** a user A has `user_blocks` rows `(A, X)` and `(Y, A)` AND A has no `posts` rows AND `DELETE FROM users WHERE id = A` is executed
- **THEN** the DELETE succeeds AND `SELECT 1 FROM user_blocks WHERE blocker_id = A OR blocked_id = A` returns zero rows

#### Scenario: V4 RESTRICT still applies after V5
- **WHEN** a user has at least one `posts` row AND a direct `DELETE FROM users WHERE id = ?` is attempted
- **THEN** the DELETE fails with a foreign-key violation (V5 cascade does NOT override V4 RESTRICT)
