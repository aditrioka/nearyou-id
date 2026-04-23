## ADDED Requirements

### Requirement: Successful follow emits a followed notification for the followee

V10 introduces the FIRST notification-write side-effect for follows. On a successful `POST /api/v1/users/{user_id}/follow` that inserts a new row into `follows`, the `FollowService` SHALL invoke `NotificationEmitter.emit(type = 'followed', recipient = followee.id, actor = caller, target_type = NULL, target_id = NULL, body_data = {})` in the SAME DB transaction as the `follows` INSERT.

The emit is subject to the `NotificationEmitter` suppression rules (see `in-app-notifications` capability):
- Self-action: the `follows` table CHECK constraint (`follower_id <> followee_id`) already prevents self-follow at the DB layer, so this case is defended-in-depth but unreachable via the endpoint; the emitter's self-action short-circuit is a belt-and-suspenders match to the schema invariant.
- Block: if the follower and followee have a `user_blocks` row in either direction, no notification row is written. Whether the block prevents the follow insertion itself is a V6 `follow-system` concern that V10 does NOT change — V10 only decides whether to emit a notification given that a follow row was inserted.

Unfollow (`DELETE /api/v1/users/{user_id}/follow`) MUST NOT emit a counter-notification (no `unfollowed` type exists in the V10 enum). The previously-written `followed` notification MAY remain in the followee's feed as a historical record.

Re-follow after an unfollow (which produces a NEW `follows` row given the cascade on unfollow) SHOULD emit a new `followed` notification (each distinct follow relationship produces one notification). If the follow row insertion is idempotent (e.g. unique-constraint-ON-CONFLICT-DO-NOTHING), only the first (actually-inserted) follow emits.

If the notification INSERT fails (e.g. followee hard-deleted between request and emit), the encompassing transaction rolls back; the `follows` INSERT does NOT persist.

Response shapes for `POST /follow` and `DELETE /follow` MUST NOT change. The `is_following` / `followed_by_viewer` fields on user profiles / timelines are unchanged.

#### Scenario: Bob follows Alice produces followed notification for Alice
- **WHEN** Bob POSTs `/api/v1/users/{aliceId}/follow` AND no block exists between Alice and Bob
- **THEN** HTTP 2xx (per V6 contract) AND exactly one `notifications` row exists with `user_id = Alice.id, type = 'followed', actor_user_id = Bob.id, target_type = NULL, target_id = NULL, body_data = {}`

#### Scenario: Self-follow rejection still fires first
- **WHEN** Alice POSTs `/api/v1/users/{aliceId}/follow` on her own id
- **THEN** the V6 CHECK constraint / endpoint logic rejects the follow (per the V6 contract) AND zero `notifications` rows are inserted

#### Scenario: Follow from blocked user produces no notification (Alice blocked Bob)
- **WHEN** Alice has a `user_blocks` row `(blocker_id = Alice, blocked_id = Bob)` AND Bob successfully inserts a follow row (whatever the V6 block semantics permit)
- **THEN** zero `notifications` rows are inserted for Alice

#### Scenario: Follow from blocked user produces no notification (Bob blocked Alice)
- **WHEN** Bob has a `user_blocks` row `(blocker_id = Bob, blocked_id = Alice)` AND Bob successfully inserts a follow row
- **THEN** zero `notifications` rows are inserted for Alice (bidirectional suppression)

#### Scenario: body_data is empty object, target_type and target_id are NULL
- **WHEN** Bob follows Alice
- **THEN** the emitted notification's `target_type IS NULL` AND `target_id IS NULL` AND `body_data = {}` (matches the `docs/05-Implementation.md:857` catalog)

#### Scenario: Unfollow does NOT emit a counter-notification
- **WHEN** Bob has followed Alice (producing a notification) AND Bob DELETEs `/api/v1/users/{aliceId}/follow`
- **THEN** HTTP 2xx AND no new `notifications` row is inserted (the original `followed` row for Alice persists as a historical record)

#### Scenario: Re-follow after unfollow emits a new notification
- **WHEN** Bob has followed Alice AND then unfollowed AND then re-follows (producing a new `follows` row insert)
- **THEN** a second `notifications` row with `type = 'followed'` is inserted for Alice (each distinct follow relationship emits once)

#### Scenario: Notification INSERT failure rolls back the follow
- **WHEN** Bob attempts to follow Alice AND Alice is hard-deleted between the follow validation and the notification INSERT
- **THEN** the transaction rolls back AND zero `follows` rows persist

#### Scenario: Follow endpoint response shapes unchanged
- **WHEN** inspecting the response bodies of `POST /follow` and `DELETE /follow` pre-V10 and post-V10
- **THEN** each matches the V6 contract (V10 does not alter the follow endpoint response shapes)
