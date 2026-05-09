## ADDED Requirements

### Requirement: POST /api/v1/posts/{post_id}/replies dispatches async Layer 3 Perspective API call after INSERT

`POST /api/v1/posts/{post_id}/replies` SHALL invoke `perspectiveDispatcher.dispatchAsync(PerspectiveTargetType.REPLY, reply.id, reply.content)` AFTER the canonical INSERT transaction commits AND BEFORE the route handler responds with HTTP 201. The dispatch is fire-and-forget; the handler does NOT await the dispatch.

The dispatch SHALL run ONLY when the sync `TextModerator.moderate(...)` produced `Verdict.Allow` or `Verdict.Flag`. On `Verdict.Reject`, the request short-circuits to HTTP 400 BEFORE the INSERT and the dispatcher is NEVER invoked.

The notification emission (`post_replied` notification to the parent post's author) and the rate-limit accounting both stay BEFORE the dispatch enqueue at the route-handler level — neither is affected by the async Layer 3 code path. Specifically: the call order is `resolveVisiblePost` → `rateLimit` → `textModerator.moderate(...)` → `replies.insertInTx(...)` (commit) → `perspectiveDispatcher.dispatchAsync(REPLY, reply.id, content)` → `notifications.emit(...)` → `respond(201, reply)`.

The dispatcher's async write path uses `post_replies.is_auto_hidden = TRUE` for the high-score auto-hide path (mirrors V8 schema; the column exists from V8 per `post-replies` capability). The soft-delete idempotency guard uses `post_replies.deleted_at IS NULL` per V8 schema — the same `deleted_at TIMESTAMPTZ` shape as `posts` from V4.

#### Scenario: Allowed reply dispatches Layer 3
- **GIVEN** `TextModerator` returns `Verdict.Allow` for benign content
- **WHEN** caller A POSTs `{"content": "benign reply"}` to `/api/v1/posts/<post_id>/replies` (post is visible, no rate-limit hit)
- **THEN** the response is HTTP 201 AND `PerspectiveDispatcher.dispatchAsync(REPLY, <new_reply_id>, "benign reply")` IS invoked

#### Scenario: Rejected reply does NOT dispatch Layer 3
- **GIVEN** `TextModerator` returns `Verdict.Reject`
- **WHEN** caller A POSTs the rejected content
- **THEN** the response is HTTP 400 AND no `post_replies` row is inserted AND no `post_replied` notification is emitted AND `PerspectiveDispatcher.dispatchAsync` is NOT invoked

#### Scenario: Rate-limited reply does NOT dispatch Layer 3
- **GIVEN** caller A has hit the 20/day Free reply rate limit (per `reply-rate-limit` capability)
- **WHEN** caller A's 21st reply attempt arrives at `/api/v1/posts/<post_id>/replies`
- **THEN** the response is HTTP 429 AND `TextModerator.moderate` is NOT called AND `PerspectiveDispatcher.dispatchAsync` is NOT invoked

#### Scenario: Static call order — INSERT commits before dispatch and before notification
- **WHEN** the source for the post-replies handler is inspected
- **THEN** the textual order is: `resolveVisiblePost` → rate-limit gate → `textModerator.moderate(...)` → `replies.insertInTx(...)` (commit) → `perspectiveDispatcher.dispatchAsync(REPLY, reply.id, content)` → `notifications.emit(post_replied, ...)` → `respond(201, reply)` (verifiable via the existing `PostRepliesCallOrderTest` static-source-scan pattern, extended with the new ordering invariant)

#### Scenario: Layer 3 high score eventually flips post_replies.is_auto_hidden
- **GIVEN** `FakePerspectiveApiClient.nextScore = 0.9f`
- **WHEN** caller A POSTs a valid reply AND receives HTTP 201 AND test calls `dispatcher.awaitInFlight()`
- **THEN** the reply's `post_replies.is_auto_hidden` is now `TRUE` AND a `moderation_queue` row exists with `target_type='reply'`, `target_id=<the_reply_id>`, `trigger='perspective_api_high_score'`
