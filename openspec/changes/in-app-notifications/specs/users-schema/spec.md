## ADDED Requirements

### Requirement: V10 adds notifications.user_id and notifications.actor_user_id FK edges on users(id) with asymmetric delete behavior

The V10 migration (`V10__notifications.sql`) SHALL add two new outgoing FK edges from the `notifications` table to `users(id)`:
- `notifications.user_id → users(id) ON DELETE CASCADE` — the recipient of the notification. CASCADE matches the per-user-PII treatment of the V5 `user_blocks` / V6 `follows` / V7 `post_likes` / V9 `reports.reporter_id` precedent: hard-deleting a user wipes their per-user rolling feed (consistent with the 90-day retention policy; see `in-app-notifications` capability for the retention comment).
- `notifications.actor_user_id → users(id) ON DELETE SET NULL` — the originator of the interaction. SET NULL matches the V9 `reports.reviewed_by` / `moderation_queue.resolved_by` precedent: actor churn must preserve the recipient's historical feed with the actor nulled (rendered client-side as "a deleted user"). SET NULL rather than CASCADE because `notifications.body_data` (excerpts, reply ids) is PII about the recipient's interactions, not the deleted actor.

After V10 runs, `users.id` is referenced by:
- V4 `posts.author_id ON DELETE RESTRICT` (content; requires tombstone worker).
- V5 `user_blocks.blocker_id` + `user_blocks.blocked_id` — both CASCADE.
- V6 `follows.follower_id` + `follows.followee_id` — both CASCADE.
- V7 `post_likes.user_id` CASCADE.
- V8 `post_replies.author_id` RESTRICT (content; requires tombstone worker).
- V9 `reports.reporter_id` CASCADE.
- **V10 `notifications.user_id` CASCADE** (new in V10).
- **V10 `notifications.actor_user_id` SET NULL** (new in V10; first SET NULL edge on `users.id` — all prior edges were CASCADE or RESTRICT).

The tombstone / hard-delete worker (still a separate future change) MUST continue to delete `posts` and `post_replies` rows authored by the user before attempting the `users` row DELETE. V10 does NOT change that worker's responsibilities; CASCADE on `notifications.user_id` means worker code does not need a separate cleanup step for recipient feeds, and SET NULL on `notifications.actor_user_id` means worker code does not need to scrub actor references from OTHER users' feeds.

#### Scenario: user_id FK exists with CASCADE after V10
- **WHEN** querying `information_schema.referential_constraints` for `notifications.user_id`
- **THEN** the row shows `delete_rule = 'CASCADE'` AND references `users(id)`

#### Scenario: actor_user_id FK exists with SET NULL after V10
- **WHEN** querying `information_schema.referential_constraints` for `notifications.actor_user_id`
- **THEN** the row shows `delete_rule = 'SET NULL'` AND references `users(id)`

#### Scenario: Recipient hard-delete cascades their feed
- **WHEN** V10 has run AND a `users` row (Alice) has 20 `notifications` rows as `user_id` AND (no `posts`, `post_replies` independently blocking the delete) AND a direct `DELETE FROM users WHERE id = Alice` is attempted
- **THEN** the DELETE succeeds AND all 20 `notifications` rows for Alice are cascade-deleted in the same transaction

#### Scenario: Actor hard-delete preserves other users' feeds with actor nulled
- **WHEN** V10 has run AND Bob is referenced as `actor_user_id` on 50 `notifications` rows across 30 different users' feeds AND Bob is hard-deleted (via the tombstone worker after content cleanup)
- **THEN** all 50 rows persist with `actor_user_id = NULL` (they are NOT deleted; the other 30 users' feeds remain intact with the actor rendered as "deleted user" on the client)

#### Scenario: User hard-delete still blocked by RESTRICT from V4/V8
- **WHEN** V10 has run AND a `users` row has at least one `posts` row OR `post_replies` row AND a direct `DELETE FROM users` is attempted
- **THEN** the DELETE fails with SQLSTATE `23503` (the V4 and V8 RESTRICT FKs still guard the delete; V10 changes nothing about that contract)

### Requirement: V10 does NOT add any column to the users table

V10 is a pure schema-addition change — it creates one new table (`notifications`) and adds two FK edges from the new table to `users(id)`. It MUST NOT add, modify, or remove any column on the `users` table itself.

#### Scenario: users table column set unchanged after V10
- **WHEN** comparing `information_schema.columns WHERE table_name = 'users'` before and after V10
- **THEN** the two column sets are identical (no column added, removed, or type-changed)
