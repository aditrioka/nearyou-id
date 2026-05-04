## MODIFIED Requirements

### Requirement: Send-message endpoint

`POST /api/v1/chat/{conversation_id}/messages` SHALL be authenticated. Unauthenticated calls SHALL be rejected with `401 Unauthorized`. A malformed `conversation_id` path parameter SHALL be rejected with `400 Bad Request`. An unknown `conversation_id` (no row in `conversations`) SHALL be rejected with `404 Not Found`. The caller MUST be an active participant; non-participant or `left_at != NULL` SHALL receive `403 Forbidden`. The request body SHALL be `{ content: <string, 1–2000 chars> }`. Bidirectional block check SHALL match the canonical query in [`docs/05-Implementation.md:1304-1308`](../../../../docs/05-Implementation.md): if `user_blocks` contains a row in either direction between caller and the OTHER active participant of the conversation, the response SHALL be `403 Forbidden` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }`. Content length guard SHALL reject content longer than 2000 characters with `400 Bad Request`. Empty content (`""` or whitespace-only) SHALL be rejected with `400 Bad Request`. **Shadow-banned senders SHALL be allowed to send** (per `chat-foundation/design.md` § D9 invisible-actor model — the row persists; non-banned recipients silently never see the row via the `GET /messages` shadow-ban filter). On success, the handler SHALL `INSERT INTO chat_messages` AND `UPDATE conversations SET last_message_at = NOW()` in the same transaction (one transaction; if either operation fails, both roll back), and return `201 Created` with the inserted row.

**Realtime broadcast on success.** When the chat-foundation transaction successfully commits AND the sender does NOT have `users.is_shadow_banned = TRUE`, the handler SHALL invoke `ChatRealtimeClient.publish(conversationId, ChatMessageBroadcast(...))` with the inserted row's projection AFTER the transaction commits. Publish SHALL NOT occur inside the transaction. When the sender has `users.is_shadow_banned = TRUE`, the handler SHALL SKIP the publish call entirely (the row still persists per the invisible-actor model). The publish result SHALL NOT alter the HTTP response: a `PublishResult.Failure` or thrown exception from `publish` SHALL be logged as a structured WARN with `event = "chat_realtime_publish_failed"`, `conversation_id`, `message_id`, `error_class`; the response SHALL remain HTTP 201; the persisted row SHALL NOT be rolled back. Recovery from missed broadcasts is via REST resync (mobile uses the existing `GET /api/v1/chat/{id}/messages` cursor pagination), per [`docs/05-Implementation.md:1213-1216`](../../../../docs/05-Implementation.md). The detailed publish-side behavior — channel name format, payload schema, retry policy, OTel span — is owned by the `chat-realtime-broadcast` capability spec.

Any of `embedded_post_id`, `embedded_post_snapshot`, OR `embedded_post_edit_id` fields present in the request body SHALL be silently ignored by this change (deferred to `chat-embedded-posts`); a structured WARN log line per ignored field SHALL note the field name and the resulting message-id with `event = "chat_send_embedded_field_ignored"`.

The send query SHALL carry the annotation `// @allow-no-block-exclusion: chat-history-readable-after-block` per `chat-foundation/design.md` § D10 (the canonical bidirectional block check is performed via an EXPLICIT query inside the handler, satisfying the 403 contract; the auto-applied NOT-IN-`user_blocks` join is suppressed because it would over-filter the participant lookup).

#### Scenario: Active participant sends a valid message
- **GIVEN** A and B are active participants in X with no `user_blocks` row in either direction; A is NOT shadow-banned
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo B" }`
- **THEN** the response is `201 Created` with the inserted row; a query of `chat_messages` shows the new row with `sender_id = A, conversation_id = X, content = "halo B"`; `conversations.last_message_at` for X is updated to the time of the INSERT; `ChatRealtimeClient.publish` is invoked exactly once after the transaction commits with `conversationId = X` and a `ChatMessageBroadcast` whose fields match the persisted row

#### Scenario: Non-participant rejected
- **GIVEN** A is NOT a participant in conversation Y
- **WHEN** A calls `POST /api/v1/chat/Y/messages { content: "x" }`
- **THEN** the response is `403 Forbidden`, no row is inserted, AND `ChatRealtimeClient.publish` is NOT invoked

#### Scenario: Block in either direction rejects send
- **GIVEN** A and B are active participants in X; a `user_blocks` row exists with `blocker_id = A, blocked_id = B`
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "x" }`
- **THEN** the response is `403 Forbidden` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }`, no row is inserted, AND `ChatRealtimeClient.publish` is NOT invoked

#### Scenario: Block in the reverse direction also rejects send
- **GIVEN** A and B are active participants in X; a `user_blocks` row exists with `blocker_id = B, blocked_id = A`
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "x" }`
- **THEN** the response is `403 Forbidden`, no row is inserted, AND `ChatRealtimeClient.publish` is NOT invoked

#### Scenario: 2001-char content rejected
- **WHEN** A calls `POST /api/v1/chat/X/messages` with `content` of length 2001
- **THEN** the response is `400 Bad Request`, no row is inserted, AND `ChatRealtimeClient.publish` is NOT invoked

#### Scenario: Whitespace-only content rejected
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "   " }`
- **THEN** the response is `400 Bad Request`, no row is inserted, AND `ChatRealtimeClient.publish` is NOT invoked

#### Scenario: Send updates last_message_at atomically
- **GIVEN** A is an active participant in X; X.last_message_at = T1
- **WHEN** A successfully sends a message at clock time T2 > T1
- **THEN** within the same DB transaction as the chat_messages INSERT, X.last_message_at is updated to T2; if the INSERT rolls back, last_message_at also rolls back; `ChatRealtimeClient.publish` is invoked AFTER the transaction commits (not within it)

#### Scenario: embedded_post_id silently ignored
- **WHEN** A sends `POST /api/v1/chat/X/messages { content: "halo", embedded_post_id: <uuid> }`
- **THEN** the response is `201 Created`; the inserted row has `embedded_post_id IS NULL`; a structured WARN log line records the ignored field with the message-id, `event = "chat_send_embedded_field_ignored"`, and field name `embedded_post_id`; the broadcast payload's `embedded_post_id` field is `null`

#### Scenario: embedded_post_snapshot silently ignored
- **WHEN** A sends `POST /api/v1/chat/X/messages { content: "halo", embedded_post_snapshot: { ... } }`
- **THEN** the response is `201 Created`; the inserted row has `embedded_post_snapshot IS NULL`; a structured WARN log line records the ignored field with field name `embedded_post_snapshot`; the broadcast payload's `embedded_post_snapshot` field is `null`

#### Scenario: embedded_post_edit_id silently ignored
- **WHEN** A sends `POST /api/v1/chat/X/messages { content: "halo", embedded_post_edit_id: <uuid> }`
- **THEN** the response is `201 Created`; the inserted row has `embedded_post_edit_id IS NULL`; a structured WARN log line records the ignored field with field name `embedded_post_edit_id`; the broadcast payload's `embedded_post_edit_id` field is `null`

#### Scenario: Shadow-banned sender's message persists but is NOT broadcast
- **GIVEN** A is shadow-banned (`is_shadow_banned = TRUE`) and is an active participant in conversation X with B; no `user_blocks` row in either direction
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo" }`
- **THEN** the response is `201 Created` with the inserted row; the row exists in `chat_messages` with `sender_id = A`; B's subsequent `GET /api/v1/chat/X/messages` does NOT return this row (per the shadow-ban read-path filter); `ChatRealtimeClient.publish` is NOT invoked for this send (publish-side shadow-ban skip per `chat-realtime-broadcast` capability)

#### Scenario: last_message_at rollback on INSERT failure
- **GIVEN** an active participant A in conversation X with X.last_message_at = T1; a fault is induced post-INSERT (e.g., the trigger or constraint surfacing causes the transaction to roll back)
- **WHEN** A calls the endpoint and the transaction rolls back
- **THEN** `conversations.last_message_at` for X remains T1 (the UPDATE was inside the same transaction and rolled back together with the failed INSERT — verifying the same-transaction guarantee from `chat-foundation/design.md` § D3); `ChatRealtimeClient.publish` is NOT invoked (publish runs only after a successful commit)

#### Scenario: Publish failure does not roll back persisted INSERT
- **GIVEN** A is an active participant in X, not shadow-banned, no block; the test-double `FakeChatRealtimeClient.publish` is configured to return `PublishResult.Failure("simulated outage")`
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo" }` AND the chat-foundation transaction commits AND publish then returns Failure
- **THEN** the response is `201 Created` (NOT 5xx); the `chat_messages` row remains persisted; `conversations.last_message_at` reflects the new send; a structured WARN log line is emitted with `event = "chat_realtime_publish_failed"`, the matching `message_id` and `conversation_id`, and `error_class = "Failure"`

#### Scenario: Publish exception does not roll back persisted INSERT
- **GIVEN** the test-double `FakeChatRealtimeClient.publish` is configured to throw `IOException("Connection refused")`
- **WHEN** A successfully sends a message AND publish throws after commit
- **THEN** the HTTP response is `201 Created`; the row persists; a WARN log line is emitted with `error_class = "IOException"`

#### Scenario: Unknown conversation id returns 404
- **WHEN** A calls `POST /api/v1/chat/<uuid that is not in conversations>/messages { content: "x" }`
- **THEN** the response is `404 Not Found` AND `ChatRealtimeClient.publish` is NOT invoked

#### Scenario: Malformed UUID path parameter returns 400
- **WHEN** A calls `POST /api/v1/chat/not-a-uuid/messages { content: "x" }`
- **THEN** the response is `400 Bad Request` AND `ChatRealtimeClient.publish` is NOT invoked

#### Scenario: Unauthenticated call rejected
- **WHEN** the endpoint is called without an Authorization header
- **THEN** the response is `401 Unauthorized` AND `ChatRealtimeClient.publish` is NOT invoked
