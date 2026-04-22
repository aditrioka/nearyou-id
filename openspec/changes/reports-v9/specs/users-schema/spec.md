## ADDED Requirements

### Requirement: V9 adds reports.reporter_id FK edge on users(id) with CASCADE

The V9 migration (`V9__reports_moderation.sql`) SHALL add a new outgoing FK edge from `reports.reporter_id` to `users(id)` with `ON DELETE CASCADE`. This continues the V5 `user_blocks` / V6 `follows` / V7 `post_likes` CASCADE pattern (as opposed to the V4 `posts` / V8 `post_replies` RESTRICT pattern for content-bearing rows). The CASCADE choice is deliberate: a user's submitted reports are included in their Data Export (`docs/05-Implementation.md` §763–768), so cascade-deletion of their reports on user hard-delete is compatible with the privacy retention policy.

After V9 runs, `users.id` is referenced by:
- V4 `posts.author_id ON DELETE RESTRICT` (content; requires tombstone worker).
- V5 `user_blocks.blocker_id` + `user_blocks.blocked_id` — both `ON DELETE CASCADE`.
- V6 `follows.follower_id` + `follows.followee_id` — both `ON DELETE CASCADE`.
- V7 `post_likes.user_id ON DELETE CASCADE`.
- V8 `post_replies.author_id ON DELETE RESTRICT` (content; requires tombstone worker).
- **V9 `reports.reporter_id ON DELETE CASCADE`** (new in V9).

The tombstone / hard-delete worker (still a separate future change) MUST continue to delete `posts` and `post_replies` rows authored by the user before attempting the `users` row DELETE. V9 does NOT change that worker's responsibilities; CASCADE on `reports.reporter_id` means worker code does not need a separate cleanup step for reports.

#### Scenario: reporter_id FK exists with CASCADE after V9
- **WHEN** querying `information_schema.referential_constraints` for `reports.reporter_id`
- **THEN** the row shows `delete_rule = 'CASCADE'` AND references `users(id)`

#### Scenario: User hard-delete cascades reports
- **WHEN** V9 has run AND a `users` row has 1 `reports` row as reporter AND (no `posts`, `post_replies` independently blocking the delete) AND a direct `DELETE FROM users WHERE id = ?` is attempted
- **THEN** the DELETE succeeds AND the `reports` row is cascade-deleted in the same transaction

#### Scenario: User hard-delete still blocked by RESTRICT from V4/V8
- **WHEN** V9 has run AND a `users` row has at least one `posts` row OR `post_replies` row AND a direct `DELETE FROM users` is attempted
- **THEN** the DELETE fails with SQLSTATE `23503` (the V4 and V8 RESTRICT FKs still guard the delete)

### Requirement: V9 does NOT add any column to the users table

V9 is a pure schema-addition change — it creates new tables (`reports`, `moderation_queue`) and adds one FK edge from the new `reports.reporter_id` to `users(id)`. It MUST NOT add, modify, or remove any column on the `users` table itself.

#### Scenario: users table column set unchanged after V9
- **WHEN** comparing `information_schema.columns WHERE table_name = 'users'` before and after V9
- **THEN** the two column sets are identical (no column added, removed, or type-changed)
