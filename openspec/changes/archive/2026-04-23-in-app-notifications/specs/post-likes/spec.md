## ADDED Requirements

### Requirement: Successful like emits a post_liked notification for the post author

V10 introduces the FIRST notification-write side-effect for likes. On a successful `POST /api/v1/posts/{post_id}/like` whose `INSERT ... ON CONFLICT (post_id, user_id) DO NOTHING` actually inserts a new row (not a re-like of an already-liked post), the `LikeService` SHALL invoke `NotificationEmitter.emit(type = 'post_liked', recipient = post.author_id, actor = caller, target_type = 'post', target_id = post.id, body_data = {post_excerpt: <first 80 code points of post.content>})` in the SAME DB transaction as the `post_likes` INSERT.

The emit is subject to the `NotificationEmitter` suppression rules (see `in-app-notifications` capability):
- Self-action: if `post.author_id == caller`, no notification row is written.
- Block: if Alice (the post author) blocked Bob (the caller), or Bob blocked Alice, no notification row is written.

Re-liking an already-liked post (which today returns 204 via `ON CONFLICT DO NOTHING`) MUST NOT emit a notification — the emit only fires on the transition from "not liked" to "liked". Unlike (`DELETE /like`) MUST NOT emit a counter-notification (no `unliked` type exists in the V10 enum).

If the notification INSERT fails (e.g. recipient user hard-deleted between request and emit), the encompassing transaction rolls back; the `post_likes` INSERT does NOT persist. This matches the strict-coupling contract in the `in-app-notifications` capability.

Response shapes for `POST /like` and `DELETE /like` MUST NOT change. The `liked_by_viewer` field on timelines is also unchanged.

#### Scenario: Bob likes Alice's post produces post_liked notification for Alice
- **WHEN** Bob POSTs `/api/v1/posts/{alicePostId}/like` AND no block exists between Alice and Bob
- **THEN** HTTP 204 AND exactly one `notifications` row exists with `user_id = Alice.id, type = 'post_liked', actor_user_id = Bob.id, target_type = 'post', target_id = alicePostId, body_data.post_excerpt = <first 80 code points of post>`

#### Scenario: Self-like produces no notification
- **WHEN** Alice POSTs `/api/v1/posts/{alicePostId}/like` on her own post
- **THEN** HTTP 204 AND zero `notifications` rows are inserted

#### Scenario: Like from blocked user produces no notification (Alice blocked Bob)
- **WHEN** Alice has a `user_blocks` row `(blocker_id = Alice, blocked_id = Bob)` AND Bob POSTs `/api/v1/posts/{alicePostId}/like`
- **THEN** HTTP 204 AND zero `notifications` rows are inserted for Alice

#### Scenario: Like from blocked user produces no notification (Bob blocked Alice)
- **WHEN** Bob has a `user_blocks` row `(blocker_id = Bob, blocked_id = Alice)` AND Bob POSTs `/api/v1/posts/{alicePostId}/like`
- **THEN** HTTP 204 AND zero `notifications` rows are inserted for Alice

#### Scenario: Re-like does NOT emit duplicate notification
- **WHEN** Bob has already liked Alice's post (producing one notification) AND Bob POSTs `/api/v1/posts/{alicePostId}/like` again
- **THEN** HTTP 204 (idempotent) AND still exactly one `notifications` row for this (post, liker, recipient) combination

#### Scenario: Unlike does NOT emit a counter-notification
- **WHEN** Bob has liked Alice's post AND Bob DELETEs `/api/v1/posts/{alicePostId}/like`
- **THEN** HTTP 204 AND no new `notifications` row is inserted (the existing `post_liked` row is NOT deleted either — it remains in Alice's feed as a historical record)

#### Scenario: Notification INSERT failure rolls back the like
- **WHEN** Bob attempts to like Alice's post AND Alice is hard-deleted between the like validation and the notification INSERT (causing FK violation on `notifications.user_id`)
- **THEN** the transaction rolls back AND zero `post_likes` rows persist

#### Scenario: Like/Unlike response shapes unchanged
- **WHEN** inspecting the response bodies of `POST /like` and `DELETE /like` pre-V10 and post-V10
- **THEN** both remain HTTP 204 with no body (V10 does not alter the response contract)
