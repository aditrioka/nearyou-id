## ADDED Requirements

### Requirement: Successful reply emits a post_replied notification for the parent post author

V10 introduces the FIRST notification-write side-effect for replies. On a successful `POST /api/v1/posts/{post_id}/replies` that inserts a new row into `post_replies`, the `ReplyService` SHALL invoke `NotificationEmitter.emit(type = 'post_replied', recipient = parentPost.author_id, actor = caller, target_type = 'post', target_id = parentPost.id, body_data = {reply_id: <inserted reply uuid>, reply_excerpt: <first 80 code points of reply.content>})` in the SAME DB transaction as the `post_replies` INSERT.

The emit is subject to the `NotificationEmitter` suppression rules (see `in-app-notifications` capability):
- Self-action: if `parentPost.author_id == caller` (replying to own post), no notification row is written.
- Block: if the parent post author and the replier have a block row in either direction, no notification row is written.

Note: `target_type = 'post'` (the parent post) rather than `'reply'`. The notification points the recipient to the post whose comment thread got a new reply; the `reply_id` inside `body_data` lets the client deep-link to the specific reply if desired. This is the documented event-catalog shape at `docs/05-Implementation.md:856`.

Reply deletion (`DELETE /api/v1/posts/{post_id}/replies/{reply_id}`) MUST NOT emit a counter-notification (no `reply_deleted` type in the enum). The previously-written `post_replied` notification MAY remain in the parent author's feed as a historical record; `target_id` continues to point to the parent post (still exists), though `body_data.reply_id` points to a now-soft-deleted reply. Client handling of stale `reply_id` deep-links is the mobile app's responsibility (redirect to parent post).

If the notification INSERT fails (e.g. parent post author hard-deleted between request and emit), the encompassing transaction rolls back; the `post_replies` INSERT does NOT persist.

The V8 reply endpoint response shapes (POST / GET / DELETE on `/replies`) MUST NOT change. The `reply_count` field on timelines is also unchanged.

#### Scenario: Bob replies to Alice's post produces post_replied notification for Alice
- **WHEN** Bob POSTs `/api/v1/posts/{alicePostId}/replies` with content "ayo ketemu" AND no block exists between Alice and Bob
- **THEN** HTTP 2xx (per V8 contract) AND exactly one `notifications` row exists with `user_id = Alice.id, type = 'post_replied', actor_user_id = Bob.id, target_type = 'post', target_id = alicePostId, body_data.reply_id = <new reply uuid>, body_data.reply_excerpt = "ayo ketemu"`

#### Scenario: Self-reply produces no notification
- **WHEN** Alice POSTs a reply on her own post
- **THEN** the reply is inserted AND zero `notifications` rows are inserted

#### Scenario: Reply from blocked user produces no notification (Alice blocked Bob)
- **WHEN** Alice has a `user_blocks` row `(blocker_id = Alice, blocked_id = Bob)` AND Bob POSTs a reply on Alice's post (the post is still resolvable via direct link / cache)
- **THEN** the reply-insertion path outcome follows the existing `post-replies` contract (block-aware or not per V8) AND IF a reply is inserted, zero `notifications` rows are inserted for Alice

#### Scenario: Reply from blocked user produces no notification (Bob blocked Alice)
- **WHEN** Bob has a `user_blocks` row `(blocker_id = Bob, blocked_id = Alice)` AND Bob successfully inserts a reply on Alice's post
- **THEN** zero `notifications` rows are inserted for Alice (bidirectional suppression)

#### Scenario: target_type is 'post' (parent) not 'reply'
- **WHEN** Bob replies to Alice's post
- **THEN** the emitted notification's `target_type = 'post'` AND `target_id = alicePostId` AND `body_data.reply_id = <the new reply uuid>`

#### Scenario: Reply soft-delete does NOT remove existing notification
- **WHEN** A `post_replied` notification exists for Alice (from Bob's reply) AND Bob soft-deletes the reply
- **THEN** the `notifications` row for Alice persists unchanged; no new counter-notification is emitted

#### Scenario: Notification INSERT failure rolls back the reply
- **WHEN** Bob attempts to reply to Alice's post AND Alice is hard-deleted between the reply validation and the notification INSERT
- **THEN** the transaction rolls back AND zero `post_replies` rows persist

#### Scenario: V8 reply endpoint response shapes unchanged
- **WHEN** inspecting the response bodies of `POST /replies`, `GET /replies`, `DELETE /replies/{id}` pre-V10 and post-V10
- **THEN** each matches the V8 contract byte-for-byte (V10 does not alter the reply endpoint response shapes)
