## MODIFIED Requirements

### Requirement: Send-message endpoint

`POST /api/v1/chat/{conversation_id}/messages` SHALL be authenticated. Unauthenticated calls SHALL be rejected with `401 Unauthorized`. A malformed `conversation_id` path parameter SHALL be rejected with `400 Bad Request`. An unknown `conversation_id` (no row in `conversations`) SHALL be rejected with `404 Not Found`. The caller MUST be an active participant; non-participant or `left_at != NULL` SHALL receive `403 Forbidden`. The request body SHALL include a `content` field (string, 1–2000 chars). The body MAY ALSO include `embedded_post_id`, `embedded_post_snapshot`, AND/OR `embedded_post_edit_id` fields, which SHALL be silently ignored by this change (deferred to `chat-embedded-posts`); presence of those fields is non-fatal — they do not change the response — and is logged per the silent-ignore clause below. Bidirectional block check SHALL match the canonical query in [`docs/05-Implementation.md:1304-1308`](../../../../../docs/05-Implementation.md): if `user_blocks` contains a row in either direction between caller and the OTHER active participant of the conversation, the response SHALL be `403 Forbidden` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }`. Content length guard SHALL reject content longer than 2000 characters with `400 Bad Request`. Empty content (`""` or whitespace-only) SHALL be rejected with `400 Bad Request`. **Shadow-banned senders SHALL be allowed to send** (per `chat-foundation/design.md` § D9 invisible-actor model — the row persists; non-banned recipients silently never see the row via the `GET /messages` shadow-ban filter). On success, the handler SHALL `INSERT INTO chat_messages` AND `UPDATE conversations SET last_message_at = NOW()` in the same transaction (one transaction; if either operation fails, both roll back), and return `201 Created` with the inserted row.

**Notification emit on success.** When the chat-foundation transaction has executed `INSERT INTO chat_messages` AND the sender does NOT have `viewer.isShadowBanned = TRUE` on the request principal, the handler SHALL invoke `NotificationEmitter.emit(...)` for the OTHER active participant (the recipient) **inside the same transaction** (after the INSERT, before COMMIT). The emit call SHALL pass `recipient_user_id = <the other participant>`, `type = 'chat_message'`, `actor_user_id = <sender>`, `target_type = 'message'`, `target_id = <chat_messages.id of the just-inserted row>`, `body_data = {"conversation_id": <UUID>, "preview": <string|null>}`. The recipient SHALL be resolved from the participant set the handler already loaded for the auth + active-participant gate (no extra `SELECT conversation_participants` is issued for the emit). When the sender has `viewer.isShadowBanned = TRUE`, the handler SHALL SKIP the emit entirely (the `chat_messages` row still persists per the invisible-actor model; this mirrors `chat-realtime-broadcast`'s publish-skip semantics). The `viewer.isShadowBanned` value SHALL come from the request principal populated by the auth-jwt plugin — no additional `SELECT is_shadow_banned FROM users` is issued for the emit decision (matches the no-extra-SELECT contract in [`chat-realtime-broadcast/spec.md`](../../../../specs/chat-realtime-broadcast/spec.md) § "Publish-side shadow-ban skip"). Block-suppression and self-action-suppression are delegated entirely to `NotificationEmitter` per [`in-app-notifications/spec.md`](../../../../specs/in-app-notifications/spec.md) § "NotificationEmitter write-path with block-suppression and self-action suppression"; the chat handler SHALL NOT add a separate block-check or self-check before the emit.

**`body_data.preview` shape.** `preview` SHALL be the first 80 code points (NOT bytes; matches the existing `post_liked` / `post_replied` excerpt truncation policy in [`in-app-notifications/spec.md`](../../../../specs/in-app-notifications/spec.md) § "body_data shape per emitted type") of `chat_messages.content`. When `chat_messages.content IS NULL` (an embedded-only message permitted by the schema CHECK at [`docs/05-Implementation.md:1273`](../../../../../docs/05-Implementation.md)), `preview` SHALL be JSON `null` (NOT empty string, NOT a placeholder). The preview SHALL be captured at emit time from the inserted row's content; subsequent edits or redactions to `chat_messages.content` SHALL NOT update the already-written `notifications.body_data.preview` (mirrors the frozen-at-emit policy of `post_excerpt`).

**Tx ordering.** The transaction body SHALL run in this strict order:
1. `INSERT INTO chat_messages (...) RETURNING id` (chat-foundation)
2. `NotificationEmitter.emit(...)` for the recipient — IF sender is NOT `viewer.isShadowBanned` (this change). The emitter's internal block-check + INSERT both participate in the same tx.
3. `UPDATE conversations SET last_message_at = NOW() WHERE id = ?` (chat-foundation)
4. **COMMIT**
5. `ChatRealtimeClient.publish(...)` if not shadow-banned — POST-commit (chat-realtime-broadcast)

**Rollback semantics.** If `NotificationEmitter.emit(...)` throws (e.g., the recipient was hard-deleted between auth and emit, causing FK violation on `notifications.user_id`), the entire transaction SHALL roll back: no `chat_messages` row persists, `conversations.last_message_at` is unchanged, `ChatRealtimeClient.publish` SHALL NOT be invoked (publish runs only post-commit). The HTTP response in this case SHALL be `5xx` (the existing chat-foundation error-handling path is reused — no new error contract). This matches the `Emit failure rolls back primary write` scenario in [`in-app-notifications/spec.md`](../../../../specs/in-app-notifications/spec.md).

**Realtime broadcast on success.** When the chat-foundation transaction successfully commits AND the sender does NOT have `users.is_shadow_banned = TRUE`, the handler SHALL invoke `ChatRealtimeClient.publish(conversationId, ChatMessageBroadcast(...))` with the inserted row's projection AFTER the transaction commits. Publish SHALL NOT occur inside the transaction. When the sender has `users.is_shadow_banned = TRUE`, the handler SHALL SKIP the publish call entirely (the row still persists per the invisible-actor model). The publish result SHALL NOT alter the HTTP response: a `PublishResult.Failure` or thrown exception from `publish` SHALL be logged as a structured WARN with `event = "chat_realtime_publish_failed"`, `conversation_id`, `message_id`, `error_class`; the response SHALL remain HTTP 201; the persisted row SHALL NOT be rolled back. Recovery from missed broadcasts is via REST resync (mobile uses the existing `GET /api/v1/chat/{id}/messages` cursor pagination), per [`docs/05-Implementation.md:1213-1216`](../../../../../docs/05-Implementation.md). The detailed publish-side behavior — channel name format, payload schema, retry policy, OTel span — is owned by the `chat-realtime-broadcast` capability spec.

Any of `embedded_post_id`, `embedded_post_snapshot`, OR `embedded_post_edit_id` fields present in the request body SHALL be silently ignored by this change (deferred to `chat-embedded-posts`); a structured WARN log line per ignored field SHALL note the field name and the resulting message-id with `event = "chat_send_embedded_field_ignored"`.

The send query SHALL carry the annotation `// @allow-no-block-exclusion: chat-history-readable-after-block` per `chat-foundation/design.md` § D10 (the canonical bidirectional block check is performed via an EXPLICIT query inside the handler, satisfying the 403 contract; the auto-applied NOT-IN-`user_blocks` join is suppressed because it would over-filter the participant lookup).

**`BlockExclusionJoinRule` composition with the new emit step.** The existing `// @allow-no-block-exclusion: chat-history-readable-after-block` annotation continues to apply ONLY to the chat-foundation participant-lookup query inside `ChatService.send(...)`. The new `NotificationEmitter.emit(...)` invocation introduced by this change runs `SELECT 1 FROM user_blocks WHERE ...` internally (per [`in-app-notifications/spec.md`](../../../../specs/in-app-notifications/spec.md) § "NotificationEmitter write-path with block-suppression and self-action suppression") — this is itself the bidirectional block check the `BlockExclusionJoinRule` Detekt rule looks for, so the emitter's SQL satisfies the rule on its own merits and does NOT require a new annotation. The chat handler SHALL NOT add a new `@allow-no-block-exclusion` annotation for the emit; the emitter's existing block-check is the canonical exclusion mechanism for the notification INSERT. Reviewers checking the chat send-handler diff for `BlockExclusionJoinRule` compliance should verify (a) the existing annotation's scope is unchanged and (b) the new emit call site delegates to `NotificationEmitter` (not a hand-rolled `INSERT INTO notifications` that would bypass the emitter's block check).

#### Scenario: Active participant sends a valid message
- **GIVEN** A and B are active participants in X with no `user_blocks` row in either direction; A is NOT shadow-banned
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo B" }`
- **THEN** the response is `201 Created` with the inserted row; a query of `chat_messages` shows the new row with `sender_id = A, conversation_id = X, content = "halo B"`; `conversations.last_message_at` for X is updated to the time of the INSERT; `ChatRealtimeClient.publish` is invoked exactly once after the transaction commits with `conversationId = X` and a `ChatMessageBroadcast` whose fields match the persisted row; **a `notifications` row is inserted for B** with `type = 'chat_message'`, `actor_user_id = A`, `target_type = 'message'`, `target_id = <the inserted chat_messages id>`, `body_data = {"conversation_id": "<X>", "preview": "halo B"}`

#### Scenario: Notification emit invoked exactly once per successful send
- **GIVEN** A and B are active participants; A is not shadow-banned; no block; a test-double `FakeNotificationEmitter` records every emit invocation
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo" }` AND the request returns 201
- **THEN** `FakeNotificationEmitter.emitInvocations.size == 1` AND the recorded invocation's `(recipient_user_id, type, actor_user_id, target_type, target_id, body_data)` exactly matches the persisted row's projection

#### Scenario: Notification emit recipient is the OTHER participant (not the sender)
- **GIVEN** A and B are active participants; A sends
- **WHEN** the emit invocation is captured
- **THEN** the captured `recipient_user_id == B` (NOT A); the captured `actor_user_id == A`

#### Scenario: Notification emit reuses already-loaded participants (no extra SELECT)
- **GIVEN** A successfully sends a message; a JDBC statement spy captures every SQL statement issued during the request
- **WHEN** the request completes successfully
- **THEN** the captured statements contain exactly ONE `SELECT ... FROM conversation_participants` (the chat-foundation auth + active-participant gate) — there is NO second `SELECT conversation_participants` issued by or for the emit step

#### Scenario: Shadow-banned sender — emit AND publish both skipped, row persists
- **GIVEN** A is shadow-banned (`viewer.isShadowBanned = TRUE`) and is an active participant in conversation X with B; no `user_blocks` row in either direction
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo" }`
- **THEN** the response is `201 Created` with the inserted row; the row exists in `chat_messages` with `sender_id = A`; B's subsequent `GET /api/v1/chat/X/messages` does NOT return this row (per the shadow-ban read-path filter); `ChatRealtimeClient.publish` is NOT invoked for this send (publish-side shadow-ban skip per `chat-realtime-broadcast`); `NotificationEmitter.emit` is NOT invoked for this send (emit-side shadow-ban skip per this change); zero `notifications` rows exist for B for this message

#### Scenario: Shadow-ban skip reads from principal, not a fresh DB SELECT
- **GIVEN** A's request principal has `viewer.isShadowBanned = TRUE`; a JDBC statement spy captures every SQL statement
- **WHEN** A successfully sends a message (HTTP 201, row persists)
- **THEN** the captured statements contain ZERO `SELECT is_shadow_banned FROM users` issued by the chat send handler for the emit decision

#### Scenario: Block in either direction rejects send before emit
- **GIVEN** A and B are active participants in X; a `user_blocks` row exists with `blocker_id = A, blocked_id = B`
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "x" }`
- **THEN** the response is `403 Forbidden` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }`, no row is inserted, AND `ChatRealtimeClient.publish` is NOT invoked, AND `NotificationEmitter.emit` is NOT invoked (the chat-foundation 403 short-circuits before the emit step)

#### Scenario: Block in the reverse direction also rejects send before emit
- **GIVEN** A and B are active participants in X; a `user_blocks` row exists with `blocker_id = B, blocked_id = A`
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "x" }`
- **THEN** the response is `403 Forbidden`, no row is inserted, AND `ChatRealtimeClient.publish` is NOT invoked, AND `NotificationEmitter.emit` is NOT invoked

#### Scenario: 2001-char content rejected before emit
- **WHEN** A calls `POST /api/v1/chat/X/messages` with `content` of length 2001
- **THEN** the response is `400 Bad Request`, no row is inserted, AND `ChatRealtimeClient.publish` is NOT invoked, AND `NotificationEmitter.emit` is NOT invoked

#### Scenario: Whitespace-only content rejected before emit
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "   " }`
- **THEN** the response is `400 Bad Request`, no row is inserted, AND `ChatRealtimeClient.publish` is NOT invoked, AND `NotificationEmitter.emit` is NOT invoked

#### Scenario: Send updates last_message_at atomically with notification emit
- **GIVEN** A is an active participant in X; X.last_message_at = T1
- **WHEN** A successfully sends a message at clock time T2 > T1
- **THEN** within the same DB transaction as the chat_messages INSERT, the notification emit's INSERT INTO notifications also runs, AND X.last_message_at is updated to T2; if the chat_messages INSERT rolls back, BOTH last_message_at AND the notifications row also roll back; `ChatRealtimeClient.publish` is invoked AFTER the transaction commits (not within it)

#### Scenario: Emit failure rolls back the entire chat send
- **GIVEN** A is an active participant in X with B; A is not shadow-banned; B is hard-deleted between auth and emit (the FK on `notifications.user_id` no longer resolves)
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo" }`
- **THEN** the response is `5xx` (chat-foundation's existing error-handling path); zero `chat_messages` rows persist for this request; `conversations.last_message_at` is unchanged; zero `notifications` rows exist; `ChatRealtimeClient.publish` is NOT invoked (publish runs only post-commit, and the tx never committed)

#### Scenario: 80-code-point preview truncation
- **GIVEN** A is an active participant; not shadow-banned; no block; `content` is a 100-character ASCII string
- **WHEN** A successfully sends the message AND the emit invocation is captured
- **THEN** the captured `body_data.preview` is exactly the first 80 characters of `content` (the next 20 characters are NOT present); the captured `body_data.conversation_id` matches X

#### Scenario: Multi-byte (emoji) content truncates at code-point boundary
- **GIVEN** A is an active participant; not shadow-banned; no block; `content` is a 90-character string where positions 78–80 (1-indexed) are a 4-byte emoji `🎉`
- **WHEN** A successfully sends the message AND the emit invocation is captured
- **THEN** the captured `body_data.preview` is exactly 80 code points long; the truncation does NOT split the emoji mid-bytes — either the emoji is fully included (if it ends at code point ≤80) or fully excluded (if it would have crossed code point 80)

#### Scenario: Preview boundary at exactly 80 code points
- **GIVEN** A is an active participant; not shadow-banned; no block; `content` is a 80-character ASCII string (exactly at the boundary)
- **WHEN** A successfully sends the message AND the emit invocation is captured
- **THEN** the captured `body_data.preview` is exactly the 80-character `content` string (NO truncation; the truncation point coincides with end-of-string)

#### Scenario: Preview boundary at 81 code points
- **GIVEN** A is an active participant; not shadow-banned; no block; `content` is an 81-character ASCII string (one past the boundary)
- **WHEN** A successfully sends the message AND the emit invocation is captured
- **THEN** the captured `body_data.preview` is exactly the first 80 characters of `content`; the 81st character is NOT present in the preview

#### Scenario: Non-BMP code-point semantics — codePoints, not chars
- **GIVEN** A is an active participant; not shadow-banned; no block; `content` is a string of exactly 80 non-BMP emoji (e.g., 80 `🎉` U+1F389 code points; in UTF-16 this is 160 chars because each emoji is a surrogate pair, but in code points it's 80)
- **WHEN** A successfully sends the message AND the emit invocation is captured
- **THEN** the captured `body_data.preview` contains all 80 emoji (truncation is on code-point count, not Java `String.length` which would count UTF-16 chars). This locks in `String.codePointCount` / `String.codePoints().limit(80)` semantics — NOT `String.substring(0, 80)` which would split an emoji.

#### Scenario: `body_data.conversation_id` uses canonical lowercase RFC 4122 form
- **GIVEN** A successfully sends a message to a conversation X with `id = UUID.fromString("11111111-2222-3333-4444-555555555555")`
- **WHEN** the emit invocation is captured AND the persisted `notifications.body_data.conversation_id` is read back via JSONB extraction
- **THEN** the captured / persisted value is exactly the lowercase hyphenated string `"11111111-2222-3333-4444-555555555555"` (matches the canonical form in [`chat-realtime-broadcast/spec.md`](../../../../specs/chat-realtime-broadcast/spec.md) § "Channel name format" — no uppercase, no hyphen-stripped form)

#### Scenario: Embedded-only message produces null preview
- **GIVEN** A is an active participant; the schema CHECK permits an INSERT with `content = NULL` AND a non-null `embedded_post_snapshot`; a synthetic test path exercises this insert (e.g., via a `@VisibleForTesting`-annotated repository hook on `ChatRepository` that is package-private and accepts a `chat_messages` row directly, bypassing the public endpoint's content-required guard; the hook MUST NOT be reachable from any production code path — verified by Detekt rule and grep for `@VisibleForTesting` on the call site)
- **WHEN** the emit step fires for this row
- **THEN** the captured `body_data.preview` is JSON `null` (NOT the empty string, NOT a placeholder); the captured `body_data.conversation_id` matches the conversation

#### Scenario: Mid-flight `is_shadow_banned` flip race (sender flips after auth, before emit)
- **GIVEN** Caller A's request principal has `viewer.isShadowBanned = FALSE` (state at auth time) AND a moderator flips `users.is_shadow_banned = TRUE` for A in a separate connection AFTER auth completed but BEFORE the handler reaches the emit step (sequencing pinned via test-injectable hook between auth completion and emit dispatch — NOT via Thread.sleep, which would be flaky); A is an active participant of conversation X with B; no `user_blocks` row in either direction
- **WHEN** A's send completes (the handler reads `viewer.isShadowBanned = FALSE` from the principal — stale)
- **THEN** the response is HTTP `201` AND `NotificationEmitter.emit(...)` IS invoked (the in-flight notification slips through; this race acceptance mirrors `chat-realtime-broadcast`'s D2 broadcast-side race acceptance — both notification and broadcast skip on stale principal state) AND `ChatRealtimeClient.publish(...)` IS invoked. All subsequent sends from A in new requests will see `viewer.isShadowBanned = TRUE` (loaded fresh per-request) and correctly skip both emit and publish.

#### Scenario: Preview frozen at emit (later content edit does not mutate notification)
- **GIVEN** A successfully sends a message at T1 with content "halo"; the resulting notification row has `body_data.preview = "halo"`; an admin later redacts the message at T2 (via the future Phase 3.5 redaction endpoint, simulated in test by a direct UPDATE)
- **WHEN** the recipient queries `GET /api/v1/notifications` after the redaction
- **THEN** the returned `body_data.preview` for the original notification row is still `"halo"` (frozen at emit time, not regenerated on read)

#### Scenario: Composite dispatcher fan-out — one push per emit
- **GIVEN** A and B are active participants; A not shadow-banned; no block; the production composite `NotificationDispatcher` is bound (with `FakeFcmDispatcher` swapped in for the FCM leg in test wiring)
- **WHEN** A successfully sends a message
- **THEN** `FakeFcmDispatcher.dispatchInvocations` records exactly one entry for `recipient = B` with the new notification's id

#### Scenario: Recipient sees the notification via GET /api/v1/notifications
- **GIVEN** A successfully sent a message to B; the resulting notification has been written
- **WHEN** B (authenticated) calls `GET /api/v1/notifications`
- **THEN** the response includes a `NotificationDto` for the chat message with `type = "chat_message"`, `actor_user_id = A`, `target_type = "message"`, `target_id = <the chat_messages id>`, `body_data.conversation_id = X`, `body_data.preview = "halo B"`, `read_at = null`

#### Scenario: Non-participant rejected
- **GIVEN** A is NOT a participant in conversation Y
- **WHEN** A calls `POST /api/v1/chat/Y/messages { content: "x" }`
- **THEN** the response is `403 Forbidden`, no row is inserted, AND `ChatRealtimeClient.publish` is NOT invoked, AND `NotificationEmitter.emit` is NOT invoked

#### Scenario: embedded_post_id silently ignored
- **WHEN** A sends `POST /api/v1/chat/X/messages { content: "halo", embedded_post_id: <uuid> }`
- **THEN** the response is `201 Created`; the inserted row has `embedded_post_id IS NULL`; a structured WARN log line records the ignored field with the message-id, `event = "chat_send_embedded_field_ignored"`, and field name `embedded_post_id`; the broadcast payload's `embedded_post_id` field is `null`; the notification's `body_data.preview` is the first 80 code points of `content` (NOT influenced by the silently-ignored embedded field)

#### Scenario: embedded_post_snapshot silently ignored
- **WHEN** A sends `POST /api/v1/chat/X/messages { content: "halo", embedded_post_snapshot: { ... } }`
- **THEN** the response is `201 Created`; the inserted row has `embedded_post_snapshot IS NULL`; a structured WARN log line records the ignored field with field name `embedded_post_snapshot`; the broadcast payload's `embedded_post_snapshot` field is `null`

#### Scenario: embedded_post_edit_id silently ignored
- **WHEN** A sends `POST /api/v1/chat/X/messages { content: "halo", embedded_post_edit_id: <uuid> }`
- **THEN** the response is `201 Created`; the inserted row has `embedded_post_edit_id IS NULL`; a structured WARN log line records the ignored field with field name `embedded_post_edit_id`; the broadcast payload's `embedded_post_edit_id` field is `null`

#### Scenario: last_message_at rollback on INSERT failure (also rolls back notification)
- **GIVEN** an active participant A in conversation X with X.last_message_at = T1; a fault is induced post-INSERT (e.g., the trigger or constraint surfacing causes the transaction to roll back) — for this scenario, induce the fault BEFORE the emit step runs
- **WHEN** A calls the endpoint and the transaction rolls back
- **THEN** `conversations.last_message_at` for X remains T1; zero `notifications` rows exist for the failed send; `ChatRealtimeClient.publish` is NOT invoked

#### Scenario: Publish failure does not roll back persisted INSERT or notification
- **GIVEN** A is an active participant in X, not shadow-banned, no block; the test-double `FakeChatRealtimeClient.publish` is configured to return `PublishResult.Failure("simulated outage")`
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo" }` AND the chat-foundation transaction commits AND publish then returns Failure
- **THEN** the response is `201 Created` (NOT 5xx); the `chat_messages` row remains persisted; the `notifications` row for B remains persisted (notification INSERT was inside the committed tx; broadcast failure happens post-commit and CANNOT roll back the notification); `conversations.last_message_at` reflects the new send; a structured WARN log line is emitted with `event = "chat_realtime_publish_failed"`, the matching `message_id` and `conversation_id`, and `error_class = "Failure"`

#### Scenario: Publish exception does not roll back persisted INSERT or notification
- **GIVEN** the test-double `FakeChatRealtimeClient.publish` is configured to throw `IOException("Connection refused")`
- **WHEN** A successfully sends a message AND publish throws after commit
- **THEN** the HTTP response is `201 Created`; the chat_messages row persists; the notification row persists; a WARN log line is emitted with `error_class = "IOException"`

#### Scenario: Unknown conversation id returns 404 before emit
- **WHEN** A calls `POST /api/v1/chat/<uuid that is not in conversations>/messages { content: "x" }`
- **THEN** the response is `404 Not Found` AND `ChatRealtimeClient.publish` is NOT invoked AND `NotificationEmitter.emit` is NOT invoked

#### Scenario: Malformed UUID path parameter returns 400 before emit
- **WHEN** A calls `POST /api/v1/chat/not-a-uuid/messages { content: "x" }`
- **THEN** the response is `400 Bad Request` AND `ChatRealtimeClient.publish` is NOT invoked AND `NotificationEmitter.emit` is NOT invoked

#### Scenario: Unauthenticated call rejected before emit
- **WHEN** the endpoint is called without an Authorization header
- **THEN** the response is `401 Unauthorized` AND `ChatRealtimeClient.publish` is NOT invoked AND `NotificationEmitter.emit` is NOT invoked
