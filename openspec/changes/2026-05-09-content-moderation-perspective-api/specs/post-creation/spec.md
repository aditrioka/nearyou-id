## ADDED Requirements

### Requirement: POST /api/v1/posts dispatches async Layer 3 Perspective API call after INSERT

`POST /api/v1/posts` SHALL invoke `perspectiveDispatcher.dispatchAsync(PerspectiveTargetType.POST, post.id, post.content)` AFTER the canonical single-INSERT transaction commits AND BEFORE the route handler responds with HTTP 201. The dispatch is fire-and-forget — the handler does NOT await the dispatch; the HTTP response is sent immediately after the dispatch is enqueued.

The dispatch SHALL be invoked ONLY when the sync `TextModerator.moderate(...)` produced `Verdict.Allow` or `Verdict.Flag`. On `Verdict.Reject`, the request short-circuits to HTTP 400 BEFORE the INSERT and the dispatcher is NEVER invoked.

The dispatcher's async write path operates on the already-persisted post and writes its results in a separate transaction (per the `content-moderation-perspective-api` spec). This MAY result in a brief observable window (typically <500 ms) where a high-toxicity post is visible in `visible_posts` before the dispatcher's UPDATE flips `is_auto_hidden = TRUE`. This race is documented behavior and acceptable per [`docs/06-Security-Privacy.md:179`](../../../docs/06-Security-Privacy.md) "visible to author, hidden from timeline until reviewed" — the dispatcher's UPDATE is the eventual-consistency boundary.

#### Scenario: Allowed post dispatches Layer 3
- **GIVEN** `TextModerator` returns `Verdict.Allow` for benign content
- **WHEN** caller A POSTs `{"content": "benign content", ...}` to `/api/v1/posts`
- **THEN** the response is HTTP 201 AND `PerspectiveDispatcher.dispatchAsync(POST, <new_post_id>, "benign content")` IS invoked AND the dispatch enqueue completes BEFORE the 201 response is sent (verifiable via test-side spy: dispatch invocation count == 1 before `await response`)

#### Scenario: Flagged post dispatches Layer 3
- **GIVEN** `TextModerator` returns `Verdict.Flag` for content that hits UU ITE threshold
- **WHEN** caller A POSTs the flagged content to `/api/v1/posts`
- **THEN** the response is HTTP 201 (Flag does not block INSERT) AND a Layer-2 `moderation_queue` row is inserted AND `PerspectiveDispatcher.dispatchAsync` IS also invoked (Layer 3 runs in addition to Layer 2's queue row; admins see both rows under the same `(target_type, target_id)` group)

#### Scenario: Rejected post does NOT dispatch Layer 3
- **GIVEN** `TextModerator` returns `Verdict.Reject` for content containing profanity
- **WHEN** caller A POSTs the profanity content to `/api/v1/posts`
- **THEN** the response is HTTP 400 AND no `posts` row is inserted AND `PerspectiveDispatcher.dispatchAsync` is NOT invoked (the reject short-circuit prevents the dispatch enqueue)

#### Scenario: Static call order — INSERT commits before dispatch enqueue
- **WHEN** the source for the post-creation handler is inspected
- **THEN** the textual order is: content-length guard → `textModerator.moderate(...)` → posts repository INSERT (commit) → `perspectiveDispatcher.dispatchAsync(POST, ..., ...)` → `respond(201, post)` (verifiable via the existing `PostCreationCallOrderTest` static-source-scan pattern, extended with the new ordering invariant)

#### Scenario: HTTP 201 returns without awaiting dispatch
- **GIVEN** `FakePerspectiveApiClient.nextScore = 0.9f` AND the fake's `analyze` is configured to take 200 ms
- **WHEN** caller A POSTs valid content to `/api/v1/posts`
- **THEN** the HTTP 201 response is received within 50 ms (the 200 ms `analyze` runs in the dispatcher's coroutine scope, not the request scope)

#### Scenario: Layer 3 high score eventually flips is_auto_hidden
- **GIVEN** `FakePerspectiveApiClient.nextScore = 0.9f`
- **WHEN** caller A POSTs valid content AND receives HTTP 201 AND test calls `dispatcher.awaitInFlight()`
- **THEN** the post's `is_auto_hidden` is now `TRUE` AND a `moderation_queue` row exists with `trigger='perspective_api_high_score'`
