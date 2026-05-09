## ADDED Requirements

### Requirement: Layer 3 (Perspective API) sequencing in the moderation pipeline

The 3-tier text moderation pipeline canonical at [`docs/06-Security-Privacy.md:171-180`](../../../docs/06-Security-Privacy.md) SHALL execute in the order: Layer 1 (sync REJECT pre-INSERT) → Layer 2 (sync FLAG pre-INSERT, INSERT proceeds with queue row) → INSERT post / reply / chat-message → Layer 3 (async POST-INSERT, fail-open, 500 ms budget) for posts and replies only. Chat messages are out of scope of Layer 3 in this change because `chat_messages` (V15) lacks an `is_auto_hidden` column; chat continues to run only Layer 1 + Layer 2 via the existing `TextModerator.moderate(...)` call.

The sync `TextModerator.moderate(...)` call from this capability SHALL run BEFORE the INSERT and never blocks on Layer 3. Layer 3 dispatch is owned by the `content-moderation-perspective-api` capability and is a fire-and-forget operation invoked by the route handler AFTER the INSERT commits AND BEFORE the HTTP response is sent. A `Verdict.Reject` outcome (Layer 1) short-circuits BEFORE the INSERT, so Layer 3 is never invoked against rejected content.

#### Scenario: Layer ordering — Reject short-circuits before INSERT and before Layer 3
- **GIVEN** the active profanity list contains `"badword"`
- **WHEN** caller A POSTs a post containing `"badword"` to `/api/v1/posts`
- **THEN** the response is HTTP 400 AND no `posts` row is inserted AND `PerspectiveDispatcher.dispatchAsync` is NOT invoked

#### Scenario: Layer ordering — Flag proceeds to INSERT and triggers Layer 3 dispatch
- **GIVEN** the active UU ITE list + threshold produce `Verdict.Flag` for content `"flagged but allowed"`
- **WHEN** caller A POSTs `"flagged but allowed"` to `/api/v1/posts`
- **THEN** the response is HTTP 201 AND a `posts` row is inserted AND a `moderation_queue` row with `trigger='uu_ite_keyword_match'` is inserted AND `PerspectiveDispatcher.dispatchAsync(POST, <new_post_id>, "flagged but allowed")` IS invoked

#### Scenario: Layer ordering — Allow proceeds to INSERT and triggers Layer 3 dispatch
- **GIVEN** the active profanity list + UU ITE list produce `Verdict.Allow` for content `"benign content"`
- **WHEN** caller A POSTs `"benign content"` to `/api/v1/posts`
- **THEN** the response is HTTP 201 AND a `posts` row is inserted AND no Layer-1/Layer-2 `moderation_queue` row is inserted AND `PerspectiveDispatcher.dispatchAsync(POST, <new_post_id>, "benign content")` IS invoked

#### Scenario: Chat path stays Layer 1 + Layer 2 only
- **GIVEN** caller A sends a chat message via `POST /api/v1/chat/{conversation_id}/messages`
- **WHEN** the request completes (regardless of whether `TextModerator` returns Allow / Reject / Flag)
- **THEN** `PerspectiveDispatcher.dispatchAsync` is NOT invoked for chat target types AND no Layer 3 code path runs against chat content
