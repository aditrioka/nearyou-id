## ADDED Requirements

### Requirement: POST /api/v1/chat/{conversation_id}/messages runs `TextModerator.moderate(...)` before INSERT

`POST /api/v1/chat/{conversation_id}/messages` SHALL invoke `TextModerator.moderate(request.content)` AFTER the existing daily rate limit, the conversation-existence + active-participant lookup, the bidirectional block check, and the 1–2000-character content-length guard, AND BEFORE the canonical `chat_messages` INSERT, AND BEFORE the `ChatRealtimeClient.publish(...)` broadcast.

The verdict mapping is:
- **`Verdict.Reject(matchedKeywords)`** → respond HTTP 400 with `error.code = "content_moderated_profanity"` + canonical Bahasa Indonesia message. NO `chat_messages` row inserted, NO `moderation_queue` row written, NO broadcast publish, NO `chat_message` notification, NO `conversations.last_message_at` update. The matched keywords are NOT in the response body.
- **`Verdict.Flag(matchedKeywords)`** → INSERT proceeds; in the same SQL transaction, INSERT one row into `moderation_queue` with `target_type = 'chat_message'`, `target_id = <new_message_id>`, `trigger = 'uu_ite_keyword_match'`, `priority = 5`, `status = 'pending'`, using `ON CONFLICT (target_type, target_id, trigger) DO NOTHING`. The broadcast publish proceeds normally; the `chat_message` notification is emitted normally; `conversations.last_message_at` is updated normally. The recipient sees the message — Layer 2 is soft-flag for admin review, NOT a hide-from-recipient action.
- **`Verdict.Allow`** → INSERT proceeds; no queue row; broadcast + notification + `last_message_at` update unchanged.

The chat content schema permits `content` to be NULL (when an embedded post stands alone — see existing `### Requirement: Send-message endpoint`). When `content` is NULL the moderator MUST be skipped — `TextModerator.moderate(null)` is not called; only the `embedded_post_*` columns are populated. Embedded-post snapshots are created at chat-initiation time from already-moderated `posts` content; they do not need re-moderation at every send.

#### Scenario: Profanity hit rejects the chat send, no INSERT or broadcast
- **GIVEN** caller A has an active conversation `C` with a non-blocked recipient B AND the active profanity list contains `"badword"`
- **WHEN** caller A POSTs `{"content": "this badword message"}` to `/api/v1/chat/{C}/messages`
- **THEN** the response is HTTP 400 with `error.code = "content_moderated_profanity"` AND no `chat_messages` row exists for this send AND `conversations.last_message_at` is unchanged AND no `ChatRealtimeClient.publish(...)` call was made AND no `chat_message` notification was emitted AND no `moderation_queue` row exists

#### Scenario: UU ITE flag at threshold inserts message + queue row, broadcasts and notifies normally
- **GIVEN** the active UU ITE list is `["sara1", "sara2", "sara3"]` AND threshold is 3 AND caller A has an active conversation C with B
- **WHEN** caller A POSTs `{"content": "soal sara1 dan sara2 dan sara3"}` to `/api/v1/chat/{C}/messages`
- **THEN** the response is HTTP 201 AND exactly one `chat_messages` row is inserted AND exactly one `moderation_queue` row exists with `target_type = 'chat_message'`, `target_id = <new_message_id>`, `trigger = 'uu_ite_keyword_match'` AND `ChatRealtimeClient.publish(...)` was called once AND `conversations.last_message_at` is updated AND a `chat_message` `notifications` row is emitted for B

#### Scenario: Embedded-post-only chat (NULL content) bypasses moderator
- **GIVEN** caller A has an active conversation C AND posts a chat message with `content = null` AND `embedded_post_id = <visible post P>` populated
- **WHEN** the send-message handler executes
- **THEN** `TextModerator.moderate(...)` is NOT called for this request (no content to moderate) AND the response is HTTP 201 AND the message is broadcast AND `chat_message` notification is emitted

#### Scenario: Allowed chat message writes no moderation_queue row
- **GIVEN** caller A has an active conversation C AND the active lists do not match the content
- **WHEN** caller A POSTs `{"content": "halo, apa kabar?"}`
- **THEN** the response is HTTP 201 AND no `moderation_queue` row exists for the new message

#### Scenario: Flag transaction is atomic — INSERT failure rolls back queue row AND broadcast does not fire
- **GIVEN** a Flag verdict produces a target message id AND the `chat_messages` INSERT subsequently fails (simulated rollback)
- **WHEN** the transaction is committed
- **THEN** no `chat_messages` row exists AND no `moderation_queue` row exists for the target id AND `ChatRealtimeClient.publish(...)` was NOT called (broadcast is post-COMMIT) AND no notification was emitted

### Requirement: Moderator runs AFTER rate limit + block check + length guard, BEFORE INSERT and broadcast (call order)

The integration of `TextModerator.moderate(...)` into `POST /api/v1/chat/{conversation_id}/messages` SHALL be at a call site where:
- The 50/day rate limit (existing `### Requirement: Daily send-rate cap — 50/day Free, unlimited Premium, with WIB stagger`) has already passed.
- The conversation existence + active-participant check has passed (existing `### Requirement: Send-message endpoint`).
- The bidirectional block check has passed (existing block-cascade enforcement).
- The 2000-character content length guard has passed (existing chat-foundation length guard).
- No `INSERT INTO chat_messages ...` has yet been issued in the request transaction.
- `ChatRealtimeClient.publish(...)` has NOT been called yet.

#### Scenario: Chat send rate-limited at 51st send does not invoke moderator
- **GIVEN** Free-tier caller A has 50 successful chat sends in the WIB day AND attempts a 51st
- **WHEN** the request reaches the rate-limit gate
- **THEN** the response is HTTP 429 with the canonical rate-limit code (NOT `content_moderated_profanity`) AND no `TextModerator.moderate(...)` call is recorded for this request

#### Scenario: Block check rejects before moderator runs
- **GIVEN** caller A has been blocked by recipient B (or vice versa)
- **WHEN** caller A POSTs `{"content": "any content here"}` to a conversation with B
- **THEN** the response is HTTP 403 with the canonical block-rejection code (NOT `content_moderated_profanity`) AND no `TextModerator.moderate(...)` call is recorded

#### Scenario: Oversized payload short-circuits before moderator runs
- **WHEN** caller A POSTs `{"content": "<2001-char string>"}` to a valid conversation
- **THEN** the response is HTTP 400 with the existing length-guard error code AND no `TextModerator.moderate(...)` call is recorded

#### Scenario: Reject response body does not leak matched keywords
- **GIVEN** content matched 1 profanity keyword AND `Verdict.Reject(matchedKeywords = listOf("k1"))` was produced
- **WHEN** the response body is captured AND scanned for `"k1"`
- **THEN** the literal does NOT appear in the response body
