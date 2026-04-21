## ADDED Requirements

### Requirement: follows table added via Flyway V6

V6 SHALL introduce the `follows` table referencing `users(id)` on both sides (`follower_id`, `followee_id`) with `ON DELETE CASCADE`. A `users` hard-delete MUST cascade-remove every `follows` row where the deleted user appears on either side. See the `follow-system` capability for the full table definition, indexes, and constraints.

#### Scenario: follows cascade on users delete — follower side
- **WHEN** a `users` row referenced by `follows.follower_id` is hard-deleted
- **THEN** all matching `follows` rows are cascade-deleted AND no orphan row remains

#### Scenario: follows cascade on users delete — followee side
- **WHEN** a `users` row referenced by `follows.followee_id` is hard-deleted
- **THEN** all matching `follows` rows are cascade-deleted AND no orphan row remains

#### Scenario: users hard-delete with both follows and user_blocks rows
- **WHEN** a `users` row has `follows` rows (both as follower and as followee) AND `user_blocks` rows (both as blocker and as blocked) AND the row is hard-deleted at the DB level (no `posts` rows referencing it)
- **THEN** every matching `follows` AND `user_blocks` row is cascade-deleted in the same transaction
