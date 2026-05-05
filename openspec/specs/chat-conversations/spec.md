# chat-conversations Specification

## Purpose
TBD - created by archiving change chat-foundation. Update Purpose after archive.
## Requirements
### Requirement: Conversation schema

The system SHALL persist 1:1 conversations in three tables: `conversations`, `conversation_participants`, `chat_messages`. The schema SHALL match the canonical specification in [`docs/05-Implementation.md` § Direct Messaging Implementation](../../../../docs/05-Implementation.md) verbatim except for the deferred `redacted_by` admin-FK constraint.

`conversations` columns: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `last_message_at TIMESTAMPTZ NULL`.

`conversation_participants` columns: `conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE`, `user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `slot SMALLINT NOT NULL CHECK (slot IN (1, 2))`, `joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `left_at TIMESTAMPTZ NULL`, `last_read_at TIMESTAMPTZ NULL`. PRIMARY KEY `(conversation_id, user_id)`. UNIQUE INDEX `conv_slot_unique ON (conversation_id, slot) WHERE left_at IS NULL`. INDEX `(user_id) WHERE left_at IS NULL`. INDEX `(conversation_id)`.

`chat_messages` columns: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE`, `sender_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `content VARCHAR(2000) NULL`, `embedded_post_id UUID NULL REFERENCES posts(id) ON DELETE SET NULL`, `embedded_post_snapshot JSONB NULL`, `embedded_post_edit_id UUID NULL` (FK constraint to `post_edits(id) ON DELETE SET NULL` is DEFERRED until the future `post-edit-history` change ships `post_edits`; the column is documented via `COMMENT ON COLUMN` matching the V9 deferred-FK pattern at [V9__reports_moderation.sql:72-73, 110-111](../../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql). The `post_edits` table does not exist at V14 — verified via migration scan.), `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `redacted_at TIMESTAMPTZ NULL`, `redacted_by UUID NULL` (FK constraint to `admin_users(id) ON DELETE SET NULL` is DEFERRED to Phase 3.5; the column is documented via `COMMENT ON COLUMN` matching the V9 pattern), `redaction_reason TEXT NULL`. CHECKs: empty-message guard `(content IS NOT NULL OR embedded_post_id IS NOT NULL OR embedded_post_snapshot IS NOT NULL)`; redaction atomicity `((redacted_at IS NULL AND redacted_by IS NULL AND redaction_reason IS NULL) OR (redacted_at IS NOT NULL AND redacted_by IS NOT NULL))`. INDEXes: `(conversation_id, created_at DESC)`, `(sender_id, created_at DESC)`, `(redacted_by, redacted_at DESC) WHERE redacted_at IS NOT NULL`.

#### Scenario: Schema applies cleanly via Flyway
- **WHEN** the V15 migration runs against a database that has V1–V14 applied
- **THEN** the three tables, six indexes, and four CHECK constraints are created without error, and `flyway_schema_history` shows V15 as `success = true`

#### Scenario: V15 installs realtime RLS policy with corrected definition
- **WHEN** V15 has applied
- **THEN** the policy `participants_can_subscribe ON realtime.messages` is installed (V15 CREATEs it directly because V2's gated DO block was a no-op when V2 ran with no `conversation_participants` table); the policy body matches V2's intent EXCEPT the subscriber-side `AND NOT EXISTS (SELECT 1 FROM public.users WHERE id = cp.user_id AND is_shadow_banned = TRUE)` clause from V2 lines 81-84 is REMOVED — shadow-banned subscribers are allowed to subscribe to their own conversation realtime channels per the invisible-actor model

#### Scenario: Empty-message CHECK rejects fully empty INSERT
- **WHEN** an INSERT is attempted with `content IS NULL`, `embedded_post_id IS NULL`, AND `embedded_post_snapshot IS NULL`
- **THEN** the INSERT is rejected with a CHECK constraint violation; the row is not persisted

#### Scenario: Empty-message CHECK accepts snapshot-only row
- **WHEN** an INSERT has `content IS NULL`, `embedded_post_id IS NULL`, but `embedded_post_snapshot` populated (the canonical scenario where an embedded post was hard-deleted but the snapshot survives)
- **THEN** the INSERT succeeds

#### Scenario: Redaction atomicity CHECK rejects half-set state
- **WHEN** an UPDATE is attempted that sets `redacted_at` non-null but leaves `redacted_by NULL` (or vice versa)
- **THEN** the UPDATE is rejected with a CHECK constraint violation

#### Scenario: Redaction atomicity CHECK accepts redaction with NULL reason
- **WHEN** an UPDATE sets `redacted_at = NOW()`, `redacted_by = <admin_id>`, and leaves `redaction_reason NULL`
- **THEN** the UPDATE succeeds (the reason is optional free-text)

#### Scenario: Slot CHECK rejects out-of-range values
- **WHEN** an INSERT into `conversation_participants` has `slot = 3` (or 0, or NULL)
- **THEN** the INSERT is rejected with a CHECK constraint violation

#### Scenario: Slot partial unique blocks a third active participant
- **GIVEN** a conversation with two active participants (`slot = 1` and `slot = 2`, both `left_at IS NULL`)
- **WHEN** a third INSERT is attempted with `slot = 1` and `left_at IS NULL`
- **THEN** the INSERT is rejected with a unique constraint violation on `conv_slot_unique`

### Requirement: Slot-race serialization via two advisory locks

The system SHALL serialize concurrent conversation creation for the same canonical-ordered user pair via a per-user-pair advisory lock at create-or-return time, AND serialize concurrent slot assignment within an existing conversation via a per-conversation advisory lock at participant-insert time.

The user-pair lock key SHALL be derived as `hashtext(LEAST(:caller_id, :recipient_id)::text || ':' || GREATEST(:caller_id, :recipient_id)::text)`. The per-conversation lock key SHALL be derived as `hashtext(:conversation_id::text)` per [`docs/05-Implementation.md:1252`](../../../../docs/05-Implementation.md). Both locks SHALL be taken via `pg_advisory_xact_lock(...)` so they release at transaction commit/rollback.

#### Scenario: Concurrent create-or-return for the same pair produces one conversation
- **GIVEN** ten concurrent `POST /api/v1/conversations { recipient_user_id: B }` calls from caller A AND ten concurrent `POST /api/v1/conversations { recipient_user_id: A }` calls from caller B (twenty total, all targeting the canonical pair (A, B))
- **WHEN** the calls execute against the running backend
- **THEN** exactly one row in `conversations` exists for the (A, B) pair, exactly two rows in `conversation_participants` exist (one for A with `slot = 1`, one for B with `slot = 2`, or symmetric), and all twenty responses return either `200 OK` or `201 Created` with the same `conversation_id`

#### Scenario: Per-conversation lock held during participant insert
- **WHEN** the create-or-return path INSERTs the two participant rows
- **THEN** the surrounding transaction has called `pg_advisory_xact_lock(hashtext(:conversation_id::text))` before the first participant INSERT

#### Scenario: User-pair lock keyed on canonical-ordered pair
- **WHEN** the user-pair lock is taken
- **THEN** the input to `hashtext` is exactly `LEAST(caller, recipient)::text || ':' || GREATEST(caller, recipient)::text` (canonical-ordered, colon-separated)

### Requirement: Create-or-return endpoint

`POST /api/v1/conversations` SHALL be authenticated. The body SHALL be `{ recipient_user_id: <uuid> }`. The response SHALL be:
- `201 Created` with the conversation row + both participant rows when a new conversation is created.
- `200 OK` with the existing conversation row + both participant rows when an existing conversation between the canonical pair is returned (idempotent). The status-code differential between 201 (new) and 200 (existing) is the ONLY observable difference between the two cases and is part of the contract.
- `403 Forbidden` with the user-facing string `"Tidak dapat mengirim pesan ke user ini"` when EITHER direction in `user_blocks` between caller and recipient exists.
- `400 Bad Request` when `recipient_user_id == caller_user_id` (self-DM rejected) — the self-DM check SHALL run BEFORE the user-pair advisory lock is acquired.
- `404 Not Found` when the recipient user does not exist in `users` (raw lookup, NOT `visible_users` — the recipient-existence check SHALL NOT leak shadow-ban state via a 201/404 differential).

The endpoint SHALL take the user-pair advisory lock for the duration of the create-or-return transaction (after the self-DM check).

#### Scenario: First call between two users creates a conversation
- **GIVEN** users A and B with no prior conversation between them and no `user_blocks` row in either direction
- **WHEN** A calls `POST /api/v1/conversations { recipient_user_id: B }`
- **THEN** the response status is `201 Created` (specifically 201, not 200) with a conversation row + two participant rows (A `slot = 1`, B `slot = 2`)

#### Scenario: Second call between the same pair returns the existing conversation
- **GIVEN** an existing conversation X between A and B with no participant having `left_at != NULL`
- **WHEN** A or B calls `POST /api/v1/conversations` for the canonical pair
- **THEN** the response status is `200 OK` (specifically 200, not 201) with the conversation row of X (same `id` as the first creation)

#### Scenario: Block in either direction rejects create
- **GIVEN** a `user_blocks` row with `blocker_id = A, blocked_id = B`
- **WHEN** A calls `POST /api/v1/conversations { recipient_user_id: B }`
- **THEN** the response is `403 Forbidden` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }`

#### Scenario: Block in the reverse direction rejects create
- **GIVEN** a `user_blocks` row with `blocker_id = B, blocked_id = A`
- **WHEN** A calls `POST /api/v1/conversations { recipient_user_id: B }`
- **THEN** the response is `403 Forbidden` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }`

#### Scenario: Self-DM rejected
- **WHEN** A calls `POST /api/v1/conversations { recipient_user_id: A }`
- **THEN** the response is `400 Bad Request`

#### Scenario: Recipient does not exist
- **WHEN** A calls `POST /api/v1/conversations { recipient_user_id: <uuid that is not in users> }`
- **THEN** the response is `404 Not Found`

#### Scenario: Recipient is shadow-banned does NOT 404 (no shadow-ban oracle)
- **GIVEN** B exists in `users` with `is_shadow_banned = TRUE`
- **WHEN** A (not shadow-banned) calls `POST /api/v1/conversations { recipient_user_id: B }`
- **THEN** the response is `201 Created` (or `200 OK` if a conversation already existed) — NOT 404. The recipient-existence check reads RAW `users`, not `visible_users`, so shadow-ban state is not leaked via a 201/404 differential.

#### Scenario: Self-DM check happens before lock acquisition
- **WHEN** A calls `POST /api/v1/conversations { recipient_user_id: A }`
- **THEN** the response is `400 Bad Request` AND no `pg_advisory_xact_lock` for the user-pair lock-key has been acquired in the request transaction (verifiable via `pg_locks` introspection on a separate connection in tests)

#### Scenario: Unauthenticated call rejected
- **WHEN** the endpoint is called without an Authorization header
- **THEN** the response is `401 Unauthorized`

### Requirement: List-conversations endpoint

`GET /api/v1/conversations` SHALL be authenticated. It SHALL return a cursor-paginated list of conversations the caller is an active participant in (`conversation_participants.user_id = caller AND left_at IS NULL`), ordered by `last_message_at DESC NULLS LAST, created_at DESC`. Each row SHALL include the conversation id, `created_at`, `last_message_at`, and the OTHER participant's profile fields `id`, `username`, `display_name`, `is_premium` sourced via **LEFT JOIN `visible_users`** with `COALESCE` to placeholder values (`username = 'akun_dihapus'`, `display_name = 'Akun Dihapus'`, `is_premium = FALSE`) for partners that are shadow-banned or otherwise filtered out by `visible_users` — the conversation row SHALL surface even when the partner is shadow-banned. **The list-conversations query SHALL NOT exclude conversations based on `user_blocks` in either direction** — per the canonical block-aware-chat contract at [`docs/02-Product.md:234`](../../../../docs/02-Product.md), "Existing conversations remain visible in history" applies symmetrically to the list view AND the messages view; only `POST /api/v1/conversations` (create) and `POST /api/v1/chat/{id}/messages` (send) enforce the bidirectional block. The query site SHALL carry the annotation `// @allow-no-block-exclusion: chat-history-readable-after-block` to suppress `BlockExclusionJoinRule` on the partner-lookup join, matching the carve-out applied to list-messages and send-message participant-lookup queries. Default page size 50; query parameter `?cursor` accepts an opaque cursor token. Malformed cursor tokens SHALL be rejected with `400 Bad Request`. Unauthenticated calls SHALL be rejected with `401 Unauthorized`.

#### Scenario: Caller sees their active conversations
- **GIVEN** A is an active participant in conversations X, Y, Z with last_message_at descending Z > Y > X
- **WHEN** A calls `GET /api/v1/conversations`
- **THEN** the response is `200 OK` with a list ordered Z, Y, X

#### Scenario: Empty conversation (no messages yet) appears at the bottom
- **GIVEN** A has conversation X with `last_message_at = NULL` and conversation Y with `last_message_at = recent`
- **WHEN** A calls `GET /api/v1/conversations`
- **THEN** Y appears before X in the list (NULLS LAST ordering)

#### Scenario: Conversation where caller has left does not surface
- **GIVEN** A's row in conversation X has `left_at != NULL`
- **WHEN** A calls `GET /api/v1/conversations`
- **THEN** X is not in the list

#### Scenario: Other-participant profile masks shadow-banned partner
- **GIVEN** A is a participant in conversation X with B; B has `is_shadow_banned = TRUE`
- **WHEN** A calls `GET /api/v1/conversations`
- **THEN** X is in the list, AND the partner profile fields are `username = 'akun_dihapus'`, `display_name = 'Akun Dihapus'`, `is_premium = FALSE` (the COALESCE-to-placeholder masks B's profile while keeping the conversation row visible)

#### Scenario: Conversation where partner has blocked caller still surfaces
- **GIVEN** A is a participant in conversation X with B; a `user_blocks` row exists with `blocker_id = B, blocked_id = A`
- **WHEN** A calls `GET /api/v1/conversations`
- **THEN** X IS in the response list — list-view does not enforce block exclusion (consistent with `docs/02-Product.md:234` "Existing conversations remain visible in history" and the list-messages history-readable-after-block contract)

#### Scenario: Conversation where caller has blocked partner still surfaces
- **GIVEN** A is a participant in conversation X with B; a `user_blocks` row exists with `blocker_id = A, blocked_id = B`
- **WHEN** A calls `GET /api/v1/conversations`
- **THEN** X IS in the response list — same canonical block-aware-chat contract; the partner profile fields are still rendered via `visible_users` (no special masking for the block, only for shadow-ban)

#### Scenario: Malformed cursor returns 400
- **WHEN** A calls `GET /api/v1/conversations?cursor=not-a-base64-token`
- **THEN** the response is `400 Bad Request`

#### Scenario: Unauthenticated call rejected
- **WHEN** the endpoint is called without an Authorization header
- **THEN** the response is `401 Unauthorized`

#### Scenario: Cursor pagination is forward-only and stable
- **GIVEN** A has 100 conversations
- **WHEN** A fetches page 1 (size 50), then uses the returned cursor to fetch page 2 (size 50)
- **THEN** the union of the two pages is the full 100 conversations with no overlap and no gaps, ordered as defined

#### Scenario: Hard cap on page size
- **WHEN** A passes `?limit=500`
- **THEN** the response contains at most 100 rows (hard cap)

### Requirement: List-messages endpoint

`GET /api/v1/chat/{conversation_id}/messages` SHALL be authenticated. Unauthenticated calls SHALL be rejected with `401 Unauthorized`. A malformed `conversation_id` path parameter (not a parseable UUID) SHALL be rejected with `400 Bad Request`. An unknown but well-formed `conversation_id` (no row in `conversations`) SHALL be rejected with `404 Not Found`. The caller MUST be an active participant of the conversation (`conversation_participants.user_id = caller AND conversation_id = :conv_id AND left_at IS NULL`); a non-participant or `left_at != NULL` participant SHALL receive `403 Forbidden`. The response SHALL be a cursor-paginated list of `chat_messages` rows ordered by `(created_at DESC, id DESC)`, default 50/page, hard cap 100/page (a `?limit` value above 100 is silently clamped to 100, NOT 400). Malformed cursor tokens SHALL be rejected with `400 Bad Request`.

The cursor SHALL be a base64-encoded JSON object with fields named `created_at` (ISO-8601 string) and `id` (UUID string).

**Shadow-ban filter on read path**: the query SHALL filter rows where the sender is shadow-banned UNLESS the sender is the viewer themselves. Concretely, the row is included iff `sender_id = :viewer OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = chat_messages.sender_id AND u.is_shadow_banned = TRUE)`. This implements the canonical invisible-actor shadow-ban model per `design.md` § D9 — a shadow-banned user always sees their own sent messages; non-banned viewers never see a shadow-banned sender's messages.

When `redacted_at IS NOT NULL` for a row, the response row SHALL set `content: null`, surface `redacted_at`, and the response field `redaction_reason` SHALL be absent from the JSON body (never serialized for the chat data plane). The original `content` value SHALL NOT be returned in any response shape.

The query SHALL read directly from raw `chat_messages` (this is a Repository own-content path explicitly allowed in the `CLAUDE.md` raw-`FROM` carve-out). The `BlockExclusionJoinRule` SHALL NOT auto-apply here — the canonical product spec at `docs/02-Product.md:234` requires that "Existing conversations remain visible in history" even after a block, so a NOT-IN `user_blocks` join would over-filter. The query site SHALL carry the annotation `// @allow-no-block-exclusion: chat-history-readable-after-block` to suppress the lint rule per `design.md` § D10.

#### Scenario: Active participant sees ordered messages
- **GIVEN** A is an active participant in conversation X with three messages M1 < M2 < M3 by `created_at`
- **WHEN** A calls `GET /api/v1/chat/X/messages`
- **THEN** the response is `200 OK` with `[M3, M2, M1]`

#### Scenario: Non-participant rejected
- **GIVEN** A is NOT a participant in conversation Y
- **WHEN** A calls `GET /api/v1/chat/Y/messages`
- **THEN** the response is `403 Forbidden`

#### Scenario: Left-participant rejected
- **GIVEN** A's row in conversation X has `left_at != NULL`
- **WHEN** A calls `GET /api/v1/chat/X/messages`
- **THEN** the response is `403 Forbidden`

#### Scenario: Unknown conversation id rejected
- **WHEN** A calls `GET /api/v1/chat/<uuid that is not in conversations>/messages`
- **THEN** the response is `404 Not Found`

#### Scenario: Redacted message returns NULL content + redacted_at
- **GIVEN** message M in conversation X has `redacted_at = T, redacted_by = admin1, redaction_reason = "spam"`
- **WHEN** an active participant calls `GET /api/v1/chat/X/messages`
- **THEN** M's response row has `content == null`, `redacted_at == T`, and the response shape does NOT include `redaction_reason`

#### Scenario: Cursor pagination by (created_at, id)
- **GIVEN** conversation X has 75 messages
- **WHEN** A fetches with `?limit=50`, then with the returned cursor and `?limit=50`
- **THEN** the union is the full 75 messages with no overlap and no gaps, ordered DESC by `(created_at, id)`

#### Scenario: Hard cap on page size
- **WHEN** A passes `?limit=500`
- **THEN** the response contains at most 100 rows (silent clamp; NOT a 400)

#### Scenario: Shadow-banned sender's messages hidden from non-banned viewer
- **GIVEN** conversation X has messages M1 (sender A, not banned) and M2 (sender B, where B has `is_shadow_banned = TRUE`)
- **WHEN** A calls `GET /api/v1/chat/X/messages`
- **THEN** M1 is in the response and M2 is NOT (the shadow-ban filter excludes B's messages from non-banned viewers)

#### Scenario: Shadow-banned sender sees their own messages
- **GIVEN** conversation X has messages M1 (sender A) and M2 (sender B); B has `is_shadow_banned = TRUE`
- **WHEN** B calls `GET /api/v1/chat/X/messages`
- **THEN** both M1 and M2 are in the response (B always sees their own messages — own-content carve-out applied per-row via the inline `sender_id = :viewer OR NOT EXISTS shadow-ban` filter)

#### Scenario: Block added after conversation creation does not hide history
- **GIVEN** A and B are active participants in conversation X with message history M1, M2, M3; THEN a `user_blocks` row is inserted (either direction)
- **WHEN** A calls `GET /api/v1/chat/X/messages`
- **THEN** the response is `200 OK` with M1, M2, M3 still readable — the block-aware-chat contract at `docs/02-Product.md:234` requires history visibility for both parties post-block

#### Scenario: Cursor boundary at tied created_at
- **GIVEN** conversation X has two messages M1 and M2 with identical `created_at` (DB-clock-tie scenario per `design.md` § D4); the page size is exactly 1
- **WHEN** A fetches page 1 with `?limit=1`, then page 2 using the returned cursor
- **THEN** M1 and M2 are split across the two pages with NO duplication and NO loss — the `(created_at DESC, id DESC)` composite cursor's `id` tiebreaker disambiguates the tied timestamps

#### Scenario: Malformed cursor returns 400
- **WHEN** A calls `GET /api/v1/chat/X/messages?cursor=not-a-base64-token`
- **THEN** the response is `400 Bad Request`

#### Scenario: Malformed UUID path parameter returns 400
- **WHEN** A calls `GET /api/v1/chat/not-a-uuid/messages`
- **THEN** the response is `400 Bad Request`

#### Scenario: Unauthenticated call rejected
- **WHEN** the endpoint is called without an Authorization header
- **THEN** the response is `401 Unauthorized`

### Requirement: Send-message endpoint

`POST /api/v1/chat/{conversation_id}/messages` SHALL be authenticated. Unauthenticated calls SHALL be rejected with `401 Unauthorized`. A malformed `conversation_id` path parameter SHALL be rejected with `400 Bad Request`. An unknown `conversation_id` (no row in `conversations`) SHALL be rejected with `404 Not Found`. The caller MUST be an active participant; non-participant or `left_at != NULL` SHALL receive `403 Forbidden`. The request body SHALL include a `content` field (string, 1–2000 chars). The body MAY ALSO include `embedded_post_id`, `embedded_post_snapshot`, AND/OR `embedded_post_edit_id` fields, which SHALL be silently ignored by this change (deferred to `chat-embedded-posts`); presence of those fields is non-fatal — they do not change the response — and is logged per the silent-ignore clause below. Bidirectional block check SHALL match the canonical query in [`docs/05-Implementation.md:1304-1308`](../../../../../docs/05-Implementation.md): if `user_blocks` contains a row in either direction between caller and the OTHER active participant of the conversation, the response SHALL be `403 Forbidden` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }`. Content length guard SHALL reject content longer than 2000 characters with `400 Bad Request`. Empty content (`""` or whitespace-only) SHALL be rejected with `400 Bad Request`. **Shadow-banned senders SHALL be allowed to send** (per `chat-foundation/design.md` § D9 invisible-actor model — the row persists; non-banned recipients silently never see the row via the `GET /messages` shadow-ban filter). On success, the handler SHALL `INSERT INTO chat_messages` AND `UPDATE conversations SET last_message_at = NOW()` in the same transaction (one transaction; if either operation fails, both roll back), and return `201 Created` with the inserted row.

**Notification emit on success.** When the chat-foundation transaction has executed `INSERT INTO chat_messages` AND the sender does NOT have `viewer.isShadowBanned = TRUE` on the request principal, the handler SHALL invoke `NotificationEmitter.emit(...)` for the OTHER active participant (the recipient) **inside the same transaction** (after the INSERT, before COMMIT). The emit call SHALL pass `recipient_user_id = <the other participant>`, `type = 'chat_message'`, `actor_user_id = <sender>`, `target_type = 'message'`, `target_id = <chat_messages.id of the just-inserted row>`, `body_data = {"conversation_id": <UUID>, "preview": <string|null>}`. The recipient SHALL be resolved from the participant set the handler already loaded for the auth + active-participant gate (no extra `SELECT conversation_participants` is issued for the emit). When the sender has `viewer.isShadowBanned = TRUE`, the handler SHALL SKIP the emit entirely (the `chat_messages` row still persists per the invisible-actor model; this mirrors `chat-realtime-broadcast`'s publish-skip semantics). The `viewer.isShadowBanned` value SHALL come from the request principal populated by the auth-jwt plugin — no additional `SELECT is_shadow_banned FROM users` is issued for the emit decision (matches the no-extra-SELECT contract in [`chat-realtime-broadcast/spec.md`](../../../../specs/chat-realtime-broadcast/spec.md) § "Publish-side shadow-ban skip"). Block-suppression and self-action-suppression are delegated entirely to `NotificationEmitter` per [`in-app-notifications/spec.md`](../../../../specs/in-app-notifications/spec.md) § "NotificationEmitter write-path with block-suppression and self-action suppression"; the chat handler SHALL NOT add a separate block-check or self-check before the emit.

**`body_data.preview` shape.** `preview` SHALL be the first 80 code points (NOT bytes; matches the existing `post_liked` / `post_replied` excerpt truncation policy in [`in-app-notifications/spec.md`](../../../../specs/in-app-notifications/spec.md) § "body_data shape per emitted type") of `chat_messages.content`. When `chat_messages.content IS NULL` (an embedded-only message permitted by the schema CHECK at [`docs/05-Implementation.md:1285`](../../../../../docs/05-Implementation.md)), `preview` SHALL be JSON `null` (NOT empty string, NOT a placeholder). The preview SHALL be captured at emit time from the inserted row's content; subsequent edits or redactions to `chat_messages.content` SHALL NOT update the already-written `notifications.body_data.preview` (mirrors the frozen-at-emit policy of `post_excerpt`).

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

**Shadow-ban-skip coupling annotation.** The chat send-handler's emit call site SHALL carry a paper-trail comment marker:

```kotlin
// @chat-shadow-ban-skip-couples: realtime-broadcast+notification-emit
// Both ChatRealtimeClient.publish and NotificationEmitter.emit MUST skip together
// when viewer.isShadowBanned is true. Removing one without the other breaks the
// symmetric privacy guarantee — see chat-conversations spec § "Send-message endpoint"
// and chat-realtime-broadcast spec § "Publish-side shadow-ban skip".
if (!viewer.isShadowBanned) {
    notificationEmitter.emit(...)  // emit skip (this change)
}
// later, post-commit:
if (!viewer.isShadowBanned) {
    chatRealtimeClient.publish(...)  // publish skip (chat-realtime-broadcast)
}
```

This marker is documentation-only (not a Detekt-enforced rule in this change) — it tells future maintainers that the two skip branches travel together. A future change MAY introduce a Detekt rule to lint the coupling if drift is observed in practice; until then the marker is the canonical paper trail.

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
- **GIVEN** A is an active participant; the schema CHECK permits an INSERT with `content = NULL` AND a non-null `embedded_post_snapshot`; a synthetic test path exercises this insert (e.g., via a `@VisibleForTesting`-annotated repository hook on `ChatRepository` that is package-private and accepts a `chat_messages` row directly, bypassing the public endpoint's content-required guard; the hook MUST NOT be reachable from any production code path — verified by reviewer attestation + grep for `@VisibleForTesting` on the call site, with the option to add a dedicated Detekt rule in a future change if production-call-site regressions occur)
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

### Requirement: Realtime RLS test set runs against real schema

The mandatory RLS test set per [`CLAUDE.md` § Critical invariants](../../../../CLAUDE.md) ("RLS changes: mandatory test case JWT `sub` not in `public.users` → deny on every policy change") SHALL run in CI against a Supabase-mode Postgres container that has `realtime.messages` and the V15-installed policy active. A staging-only verification is INSUFFICIENT — the policy is installed by V15 (with the corrected subscriber-side definition; see Requirement: Schema applies cleanly via Flyway scenario "V15 installs realtime RLS policy with corrected definition") and the test set MUST run against a real schema in CI before the change can ship. The set SHALL cover the deny cases enumerated in [`docs/05-Implementation.md:108-110`](../../../../docs/05-Implementation.md): JWT-sub-not-in-users, non-participant, `left_at`-participant, malformed topic (`conversation:` no UUID, `conversation` no delimiter), invalid-UUID topic (`conversation:not-a-uuid`), SQL-injection topic. The set SHALL ALSO include an allow case for the shadow-banned active participant (the V15 policy does NOT carry V2's subscriber-side `is_shadow_banned` clause).

#### Scenario: JWT sub not in users denies
- **GIVEN** a JWT with a `sub` UUID that does not exist in `public.users`
- **WHEN** a SELECT against `realtime.messages` with topic `conversation:<valid_conv_uuid>` runs in that JWT context
- **THEN** the policy denies (zero rows)

#### Scenario: Non-participant denies
- **GIVEN** a valid user in JWT context who is NOT in `conversation_participants` for the topic conversation
- **WHEN** a SELECT against `realtime.messages` runs
- **THEN** the policy denies

#### Scenario: left_at participant denies
- **GIVEN** a user in JWT context who has a row in `conversation_participants` for the topic conversation but `left_at != NULL`
- **WHEN** a SELECT against `realtime.messages` runs
- **THEN** the policy denies (the V2 policy joins on `cp.left_at IS NULL`)

#### Scenario: Malformed topic without delimiter denies
- **WHEN** a SELECT runs with topic `conversation` (no colon, no UUID)
- **THEN** the policy denies via the regex guard

#### Scenario: Topic with non-UUID denies
- **WHEN** a SELECT runs with topic `conversation:not-a-uuid`
- **THEN** the policy denies via the regex guard

#### Scenario: SQL-injection topic denies
- **WHEN** a SELECT runs with topic `conversation:'; DROP TABLE conversations; --`
- **THEN** the policy denies via the regex guard, and the database remains intact

#### Scenario: Active participant succeeds
- **GIVEN** a user in JWT context who is an active participant (`left_at IS NULL`) of the topic conversation
- **WHEN** a SELECT against `realtime.messages` runs
- **THEN** the policy allows

#### Scenario: Shadow-banned active participant succeeds
- **GIVEN** a user in JWT context who is an active participant of the topic conversation AND has `is_shadow_banned = TRUE`
- **WHEN** a SELECT against `realtime.messages` runs
- **THEN** the policy ALLOWS (the V15-installed policy intentionally does NOT carry V2's subscriber-side `is_shadow_banned` clause; per the invisible-actor model in `design.md` § D9, shadow-banned subscribers retain their own realtime view)

### Requirement: Daily send-rate cap — 50/day Free, unlimited Premium, with WIB stagger

`POST /api/v1/chat/{conversation_id}/messages` SHALL enforce a per-user **daily** rate limit of 50 successful chat-message INSERTs for Free-tier callers and unlimited for Premium-tier callers. The check runs via `RateLimiter.tryAcquire(userId, key, capacity, ttl)` against a Redis-backed counter keyed `{scope:rate_chat_send_day}:{user:<user_id>}`.

The TTL MUST be supplied via `computeTTLToNextReset(userId)` so the per-user offset distributes resets across `00:00–01:00 WIB` (per [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) §1751-1755). Hardcoding `Duration.ofDays(1)` or any other fixed duration at this call site is a `RateLimitTtlRule` Detekt violation.

Tier gating reads `users.subscription_status` (V3 schema column, three-state enum). Both `premium_active` and `premium_billing_retry` (the 7-day grace state) MUST skip the daily limiter entirely. Only `free` (and any future state mapping to free) is subject to the cap.

**Read-site constraint.** Tier MUST be read from the request-scope `viewer` principal populated by the auth-jwt plugin (which loads `subscription_status` alongside the user identity for every authenticated request — added by `like-rate-limit` task 6.1.1; tracked as `auth-jwt` spec debt in `FOLLOW_UPS.md` since the field is not yet documented in any spec). The chat send handler MUST NOT issue a fresh `SELECT subscription_status FROM users WHERE id = :caller` before the limiter; doing so would violate the "limiter runs before any DB read" guarantee. If the auth principal does not carry `subscription_status`, that's a defect in the auth path and MUST be fixed there, not worked around by adding a DB read in this handler.

**Defect-mode behavior.** If `viewer.subscriptionStatus` is unexpectedly null, the handler MUST treat the caller as Free (fail-closed against accidental Premium-tier escalation) and apply the cap. This is a defensive guardrail; the underlying defect MUST still be fixed in the auth-jwt path.

The daily limiter MUST run BEFORE any DB read (specifically, before the chat-foundation conversation-existence + active-participant lookup, before the bidirectional block check, before the `chat_messages` INSERT, and before the `conversations.last_message_at` UPDATE) AND BEFORE the chat-foundation 2000-char content-length guard (so a Free attacker spamming oversized payloads still consumes slots and hits 429 at the cap, rather than burning unlimited "invalid_request" responses). On `RateLimited`, the response is HTTP 429 with body `{ "error": { "code": "rate_limited" } }` and a `Retry-After` header set to the seconds returned by the limiter (≥ 1).

The daily limiter MUST count successful slot acquisitions (i.e., requests that pass auth + UUID validation), not net chat messages. A message that an admin later redacts via the future `PATCH /admin/chat-messages/:id/redact` endpoint (Phase 3.5) MUST NOT release the original sender's slot — admin redaction is independent of rate-limit state and never decrements the daily counter.

#### Scenario: 50 chat sends within a day succeed
- **WHEN** Free-tier caller A successfully POSTs 50 distinct chat-message INSERTs within a single WIB day (each on an active conversation with a non-blocked recipient, with valid 1–2000 char content)
- **THEN** all 50 responses are HTTP 201

#### Scenario: 51st chat send in same day rate-limited
- **WHEN** Free-tier caller A has 50 successful chat sends in the current WIB day AND attempts a 51st
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` AND a `Retry-After` header carrying a positive integer AND no `chat_messages` row is inserted AND `conversations.last_message_at` is unchanged

#### Scenario: Retry-After approximates seconds to next reset
- **WHEN** the 51st request is rejected at WIB time `T`
- **THEN** the `Retry-After` value is approximately the number of seconds from `T` to (next 00:00 WIB) + (`hash(A.id) % 3600` seconds), within ±5 seconds (CI runner clock + Redis `TIME` skew tolerance, matches the like-rate-limit and reply-rate-limit precedents)

#### Scenario: Premium user not gated by daily cap
- **WHEN** caller A has `users.subscription_status = 'premium_active'` AND attempts a 100th chat send in a single WIB day
- **THEN** the response is HTTP 201 AND no daily-limiter check increments a counter for A (the daily limiter MUST be skipped, not consulted-and-overridden)

#### Scenario: Premium billing retry still treated as Premium
- **WHEN** caller A has `users.subscription_status = 'premium_billing_retry'` AND attempts a 75th chat send in a single WIB day
- **THEN** the response is HTTP 201 AND the daily limiter is skipped (75 chosen distinct from the 60 used in override scenarios and the 100 used in `premium_active`, so test fixtures don't accidentally exercise multiple code paths together)

#### Scenario: Daily key uses hash-tag format
- **WHEN** the daily limiter check runs against Redis for caller `A` (uuid `U`)
- **THEN** the key used is exactly `"{scope:rate_chat_send_day}:{user:U}"`

#### Scenario: Admin redaction does not release a daily slot
- **WHEN** within the same WIB day Free-tier caller A executes this sequence: (1) POSTs 5 successful chat sends (bucket grows 0 → 5), (2) an admin redacts one of those messages via the future redaction endpoint (the message row's `redacted_at`/`redacted_by` are set; bucket stays at 5 — admin redaction does NOT release), (3) POSTs 45 more successful chat sends (bucket grows 5 → 50), (4) POSTs a 51st chat send attempt
- **THEN** the 51st POST is rejected with HTTP 429 `error.code = "rate_limited"` AND the daily bucket size remains at 50 (the 51st acquisition was rejected, so no slot was added) AND the redacted message's slot was never released — proving admin moderation does not refund the cap

#### Scenario: Null subscriptionStatus on viewer principal treated as Free (defensive)
- **WHEN** the auth-jwt plugin populates `viewer` with `subscriptionStatus = null` (auth-path defect) AND Free-equivalent caller A attempts a 51st chat send in a day
- **THEN** the response is HTTP 429 (the handler MUST fall through to the Free-tier path, NOT skip the limiter). The implementation MUST also slf4j-WARN-log the defect (so the auth-path bug surfaces in monitoring), but the integration-test assertion is response status only — logging behavior is implementation-tested via service-level unit tests (mirrors the malformed-flag scenario hedge from `reply-rate-limit`).

#### Scenario: Daily limiter runs before participant lookup
- **WHEN** Free-tier caller A is at slot 51 AND attempts `POST /chat/{conversation_id}/messages` on a conversation A is NOT a participant in
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 403 `not_a_participant`) AND no `conversation_participants` SELECT was executed

#### Scenario: Daily limiter runs before block check
- **WHEN** Free-tier caller A is at slot 51 AND attempts `POST /chat/{conversation_id}/messages` on a conversation where the recipient B has blocked A
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 403 `blocked`) AND no `user_blocks` SELECT was executed

#### Scenario: Daily limiter runs before content-length guard
- **WHEN** Free-tier caller A is at slot 51 AND POSTs a chat message with 2001-character content
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 400 `invalid_request`) AND no JSON body parsing or content-length validation occurred

#### Scenario: Daily limiter runs before unknown-conversation 404
- **WHEN** Free-tier caller A is at slot 51 AND POSTs `/chat/{nonexistent_uuid}/messages`
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 404 `conversation_not_found`)

#### Scenario: WIB day rollover restores the cap
- **WHEN** Free-tier caller A is at slot 50 at WIB `23:59:00` AND a chat send is attempted at WIB `00:00:01 + (hash(A.id) % 3600)` seconds the next day
- **THEN** the response is HTTP 201 (the user-specific reset window has passed AND old entries are pruned)

### Requirement: `premium_chat_send_cap_override` Firebase Remote Config flag

The chat send service SHALL read the Firebase Remote Config flag `premium_chat_send_cap_override` on every `POST /api/v1/chat/{conversation_id}/messages` request from a Free-tier caller (Premium calls skip the daily limiter entirely, so the flag is irrelevant for them).

When the flag is unset, malformed, ≤ 0, or unavailable due to Remote Config error: the daily cap is `50` (the canonical default). When the flag is set to a positive integer N: the daily cap is `N`. When the flag is set to a value above 10,000 (clearly absurd / typo): the cap falls back to the default `50` (anti-typo guard, mirrors the like and reply override fallbacks — 10,000 is the threshold that triggers the fallback, not a clamp value applied to the override). The flag MUST mirror the `premium_like_cap_override` and `premium_reply_cap_override` Decision 28 contract from `like-rate-limit` and `reply-rate-limit` byte-for-byte (default-fallback shape, request-time read, mid-day flip-binds-immediately behavior).

#### Scenario: Flag unset uses 50 default
- **WHEN** `premium_chat_send_cap_override` is unset in Remote Config AND Free caller A attempts a 51st chat send in a day
- **THEN** the response is HTTP 429 (daily cap = 50)

#### Scenario: Flag = 60 raises the cap
- **WHEN** `premium_chat_send_cap_override = 60` AND Free caller A has 60 successful chat sends today AND attempts a 61st chat send
- **THEN** the 60th chat send (within the override) returned HTTP 201 AND the 61st returns HTTP 429 with `error.code = "rate_limited"`

#### Scenario: Flag = 10 lowers the cap mid-day
- **WHEN** Free caller A has 15 successful chat sends today (under the original 50 cap) AND `premium_chat_send_cap_override = 10` is set AND A attempts a 16th chat send
- **THEN** the response is HTTP 429 (the override applies at request time; users above the new cap are immediately rate-limited)

#### Scenario: Flag = 0 falls back to default
- **WHEN** `premium_chat_send_cap_override = 0` (invalid) AND Free caller A attempts a 51st chat send in a day
- **THEN** the response is HTTP 429 (the cap remains 50, not 0)

#### Scenario: Flag malformed (non-integer string) falls back to default
- **WHEN** `premium_chat_send_cap_override = "fifty"` (or any non-integer-parseable value returned by the Remote Config client) AND Free caller A attempts a 51st chat send in a day
- **THEN** the response is HTTP 429 (the cap remains 50). The implementation MUST also slf4j-WARN-log the malformed value so ops can detect the misconfiguration, but the integration-test assertion is the response status only (logging behavior is implementation-tested via service-level unit tests, not via a `ListAppender` in the HTTP-level test class)

#### Scenario: Flag oversized integer (above any sane cap) falls back to default
- **WHEN** `premium_chat_send_cap_override = Long.MAX_VALUE` or any positive value above 10,000 (clearly absurd) AND Free caller A attempts a 51st chat send
- **THEN** the response is HTTP 429 (the cap falls back to default 50). The upper-bound fallback prevents accidental cap removal via a typo (e.g., `5000000000` instead of `50`). Implementations MAY pick any specific threshold ≥ 10,000 (no abuse signal supports a Free user sending >10,000 chat messages/day). The implementation MUST also slf4j-WARN-log the oversized value, but the integration-test assertion is response status only (mirrors the malformed-flag scenario hedge).

#### Scenario: Remote Config network failure falls back to default
- **WHEN** the Remote Config SDK throws an `IOException` (or any error) when the chat send handler attempts to read `premium_chat_send_cap_override` AND Free caller A attempts a 51st chat send in a day
- **THEN** the response is HTTP 429 (the cap defaults to 50) AND the request does NOT 5xx — Remote Config errors MUST NOT propagate into a user-facing 5xx since the safe default is conservative (the canonical 50/day cap)

#### Scenario: Flag does NOT affect Premium
- **WHEN** `premium_chat_send_cap_override = 10` AND Premium caller A attempts a 16th chat send
- **THEN** the response is HTTP 201 (Premium skips the daily limiter entirely)

### Requirement: Limiter ordering and pre-DB execution on chat send

The chat send service SHALL run, in this exact order, on every `POST /api/v1/chat/{conversation_id}/messages`:

1. Auth (existing `auth-jwt` plugin).
2. Path UUID validation on `conversation_id` (existing — 400 `invalid_uuid` on malformed path).
3. **Daily rate limiter** (`{scope:rate_chat_send_day}:{user:<uuid>}`) — Free only; Premium skips. On `RateLimited`: 429 + `Retry-After` + STOP.
4. Conversation existence + active-participant check (existing chat-foundation — 404 `conversation_not_found` on missing row in `conversations`; 403 `not_a_participant` on non-participant or `left_at != NULL`).
5. Bidirectional block check (existing chat-foundation — 403 `blocked` with `"Tidak dapat mengirim pesan ke user ini"` on `user_blocks` row in either direction).
6. JSON body parsing + chat-foundation 2000-char content-length guard + empty-content guard (existing — 400 `invalid_request` on empty / whitespace-only / >2000 / missing / null `content`).
7. Chat-foundation `INSERT INTO chat_messages` and `UPDATE conversations SET last_message_at = NOW()` in the same DB transaction.
8. Return HTTP 201 with the chat-foundation response shape.

Steps 1–3 MUST run before any DB query. Steps 1–3 MUST NOT execute a DB statement. The chat send handler MUST NOT call `RateLimiter.releaseMostRecent` on any path — every successful slot acquisition is permanent (there is no idempotent re-action path on `chat_messages` analogous to the `INSERT ... ON CONFLICT DO NOTHING` no-op on `post_likes`).

The send-message endpoint takes NO advisory lock (only `POST /api/v1/conversations` takes the user-pair advisory lock from chat-foundation § "Slot-race serialization via two advisory locks"). The daily limiter therefore has no lock-ordering interaction; it is a pure pre-DB Redis call.

_Note: the "limiter runs before participant lookup", "limiter runs before block check", and "limiter runs before content-length guard" assertions live in the Daily send-rate cap requirement above — its dedicated short-circuit scenarios cover all three orderings. This requirement focuses on auth/UUID short-circuits BEFORE the limiter and slot-consumption rules AFTER the limiter._

#### Scenario: Auth failure short-circuits before limiter
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 AND no limiter check runs (no Redis round-trip for auth-rejected requests)

#### Scenario: Invalid UUID short-circuits before limiter
- **WHEN** the request path is `POST /api/v1/chat/not-a-uuid/messages`
- **THEN** the response is HTTP 400 with `error.code = "invalid_uuid"` AND no limiter check runs

#### Scenario: 404 conversation_not_found consumes a slot
- **WHEN** Free caller A has 5 successful chat sends today AND POSTs `/chat/{nonexistent_uuid}/messages` with valid content
- **THEN** the response is HTTP 404 with `error.code = "conversation_not_found"` AND the daily bucket size is 6 (the slot was consumed before the 404 was decided) AND the limiter MUST NOT call `releaseMostRecent`

#### Scenario: 403 not_a_participant post-limiter consumes a slot
- **WHEN** Free caller A has 5 successful chat sends today AND POSTs `/chat/{conversation_id}/messages` on a conversation A is NOT an active participant in
- **THEN** the response is HTTP 403 AND the daily bucket size is 6 (the slot was consumed because the limiter ran before the participant check) AND the limiter MUST NOT call `releaseMostRecent`

#### Scenario: 403 blocked post-limiter consumes a slot
- **WHEN** Free caller A has 5 successful chat sends today AND POSTs `/chat/{conversation_id}/messages` on a conversation where the recipient B has blocked A
- **THEN** the response is HTTP 403 with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }` AND the daily bucket size is 6 (the slot was consumed because the limiter ran before the block check) AND the limiter MUST NOT call `releaseMostRecent`

#### Scenario: 400 invalid_request post-limiter consumes a slot
- **WHEN** Free caller A has 5 successful chat sends today AND POSTs `/chat/{conversation_id}/messages` with empty content `{ "content": "" }` AND the conversation_id is a valid UUID belonging to a conversation A is an active participant in
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"` AND the daily bucket size is 6 (the slot was consumed because the limiter ran before the content guard) AND the limiter MUST NOT call `releaseMostRecent`

#### Scenario: Successful chat send consumes a slot
- **WHEN** Free caller A has 5 successful chat sends today AND POSTs a fresh chat send on an active conversation with valid content
- **THEN** the response is HTTP 201 AND the daily bucket size is 6 AND `conversations.last_message_at` is updated

#### Scenario: Transaction rollback (chat_messages INSERT failure) does NOT release the slot
- **WHEN** Free caller A is at slot 5 AND POSTs a valid chat message AND the encompassing DB transaction rolls back (e.g., a constraint surfacing causes both the `chat_messages` INSERT and the `conversations.last_message_at` UPDATE to roll back together)
- **THEN** zero `chat_messages` rows persist AND `conversations.last_message_at` reverts to its pre-request value (chat-foundation atomicity contract preserved) AND the daily bucket size is 6 (the slot remains consumed; `releaseMostRecent` MUST NOT be called on the rollback path) AND the limiter response was `Allowed`, so a regression that adds a release on rollback would be caught by this scenario

#### Scenario: Shadow-banned sender's send consumes a slot identically to non-banned sender
- **WHEN** Free caller A has `is_shadow_banned = TRUE` AND has 5 successful chat sends today AND POSTs a fresh chat send on an active conversation with valid content
- **THEN** the response is HTTP 201 (chat-foundation invisible-actor model preserved — shadow-banned senders are allowed to send) AND the daily bucket size is 6 (shadow-ban state does NOT affect rate-limit accounting; the cap applies symmetrically to banned and non-banned senders)

### Requirement: GET /api/v1/chat/{conversation_id}/messages is NOT rate-limited at the per-endpoint layer

The chat-foundation list-messages endpoint (see `chat-conversations` § "List-messages endpoint") MUST NOT call `RateLimiter.tryAcquire`. Per-endpoint read-side rate limiting on GET `/messages` is explicitly deferred (matches the `like-rate-limit` and `reply-rate-limit` precedents which also did not rate-limit the corresponding GET endpoints); read-side throttling lives at the timeline session/hourly layer per [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) (50/session soft + 150/hour hard).

#### Scenario: GET messages unaffected by daily chat send cap
- **WHEN** Free caller A is at slot 51 AND GETs `/api/v1/chat/{conversation_id}/messages` on an active conversation
- **THEN** the response is HTTP 200 with the chat-foundation cursor-paginated response shape AND no rate-limiter check occurred

### Requirement: GET /api/v1/conversations is NOT rate-limited at the per-endpoint layer

The chat-foundation list-conversations endpoint (see `chat-conversations` § "List-conversations endpoint") MUST NOT call `RateLimiter.tryAcquire`. Per-endpoint read-side rate limiting is explicitly deferred (matches the `like-rate-limit` and `reply-rate-limit` precedents); read-side throttling lives at the timeline session/hourly layer.

#### Scenario: GET conversations unaffected by daily chat send cap
- **WHEN** Free caller A is at slot 51 AND GETs `/api/v1/conversations`
- **THEN** the response is HTTP 200 with the chat-foundation cursor-paginated response shape AND no rate-limiter check occurred

### Requirement: POST /api/v1/conversations is NOT rate-limited at the per-endpoint layer

The chat-foundation create-or-return endpoint (see `chat-conversations` § "Create-or-return endpoint") MUST NOT call `RateLimiter.tryAcquire`. Conversation creation is rare (one row pair per user-pair lifetime), already serialized by the user-pair advisory lock, and is not a per-user-velocity abuse vector. A hypothetical future per-user "create N conversations/day" cap, if needed, would be a separate change.

#### Scenario: POST conversations unaffected by daily chat send cap
- **WHEN** Free caller A is at slot 51 AND POSTs `/api/v1/conversations { recipient_user_id: B }` where no prior conversation exists
- **THEN** the response is HTTP 201 with the chat-foundation create-or-return response shape AND no rate-limiter check occurred

### Requirement: Integration test coverage — chat send rate limit

`ChatSendRateLimitTest` (tagged `database`, backed by the `InMemoryRateLimiter` extracted in `like-rate-limit` task 7.1 — Lua-level correctness is exercised separately by `:infra:redis`'s `RedisRateLimiterIntegrationTest`) SHALL exist alongside the existing chat-foundation route test class and cover, at minimum:

1. 50 chat sends in a day succeed for Free user.
2. 51st chat send in the same day rate-limited; 429 + `Retry-After` + no `chat_messages` row inserted AND `conversations.last_message_at` unchanged (verified via row-count snapshot + column-value snapshot before/after the request).
3. `Retry-After` value within ±5s of expected (`now` frozen via `AtomicReference<Instant>` clock injected into `InMemoryRateLimiter`).
4. Premium user (status `premium_active`) skips the daily limiter — 100 chat sends in a day all succeed (chosen distinct from the 60 used in scenario 6 to avoid test-data ambiguity).
5. Premium billing retry status (`premium_billing_retry`) also skips the daily limiter (75 chat sends all succeed).
6. `premium_chat_send_cap_override` raises the cap to 60 for a Free user (61st rejected after a successful 60th).
7. `premium_chat_send_cap_override` lowers the cap mid-day; user previously at 15 is rejected at 16.
8. `premium_chat_send_cap_override = 0` falls back to default 50.
9. `premium_chat_send_cap_override = "fifty"` (malformed non-integer) falls back to default 50. The slf4j WARN log is implementation-detail; the integration test asserts response status only.
10. Remote Config network failure (SDK throws) falls back to default 50; no 5xx propagated.
11. 404 `conversation_not_found` consumes a slot (does NOT release): a request to a non-existent conversation UUID at slot 5 leaves the daily bucket at size 6.
12. 403 `not_a_participant` consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6.
13. 403 `blocked` consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6.
14. 400 `invalid_request` on **empty content** (`{ "content": "" }`) consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6. (Split from a single empty/whitespace/oversized scenario to ensure each rejection branch is independently exercised — an implementation that only handles one branch correctly should not pass.)
15. 400 `invalid_request` on **whitespace-only content** (`{ "content": "   " }`) consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6.
16. 400 `invalid_request` on **2001-character content** consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6.
17. Daily limit hit short-circuits before the chat-foundation participant check: at slot 51, POSTing to a conversation the caller is NOT a participant in returns HTTP 429 (NOT HTTP 403) — behavioral proof that the limiter ran before participant lookup.
18. Daily limit hit short-circuits before the chat-foundation block check: at slot 51, POSTing to a conversation where the recipient blocks the caller returns HTTP 429 (NOT HTTP 403) — behavioral proof that the limiter ran before block lookup.
19. Daily limit hit short-circuits before the chat-foundation content-length guard: at slot 51, POSTing 2001-char content returns HTTP 429 (NOT HTTP 400) — behavioral proof that the limiter ran before content validation.
20. Daily limit hit short-circuits before unknown-conversation 404: at slot 51, POSTing to a non-existent conversation UUID returns HTTP 429 (NOT HTTP 404).
21. Hash-tag key shape verified: daily key = `{scope:rate_chat_send_day}:{user:<uuid>}` (via a `SpyRateLimiter` test double that captures `tryAcquire` keys).
22. WIB rollover (single user): at the per-user reset moment the cap restores (frozen `AtomicReference<Instant>` clock advanced past `computeTTLToNextReset(userId)` + 1s).
23. WIB rollover (per-user offset is genuinely per-user): two distinct synthetic users `U1` and `U2` whose UUIDs hash to different `% 3600` offsets exhaust their caps at the same wall-clock moment; the `Retry-After` values returned to each (captured via `SpyRateLimiter`) differ by ≥ 1 second — proving the offset is applied per-user, not a global constant. (Test fixture MUST pick UUID values whose `hashCode() % 3600` is known to differ; assert the difference is at least 1 second to absorb any same-millisecond hash collisions if they ever occur — collision probability is `1/3600 ≈ 0.0003`, so the fixture deterministically picks non-colliding UUIDs.)
24. Admin redaction does NOT release a daily slot: POST 5 successful chat sends → simulate admin redaction on 1 (bucket stays at 5) → POST 45 more (bucket reaches 50) → 51st POST attempt rejected with HTTP 429 → bucket stays at 50.
25. GET `/messages` unaffected by the daily cap (caller at 51/50 still gets HTTP 200 from the chat-foundation list-messages endpoint).
26. GET `/conversations` unaffected by the daily cap (caller at 51/50 still gets HTTP 200 from the chat-foundation list-conversations endpoint).
27. POST `/conversations` unaffected by the daily cap (caller at 51/50 still gets HTTP 201 from the chat-foundation create-or-return endpoint).
28. Tier (`subscription_status`) is read from the auth-time `viewer` principal: a `SpyRateLimiter` confirms `tryAcquire` was invoked AND the response is HTTP 201 — combined with scenario 17 (limiter-before-participant-lookup), this proves no DB read sits between auth and limiter.
29. The chat send handler MUST NOT call `RateLimiter.releaseMostRecent` on any code path — assert via `SpyRateLimiter` that no `releaseMostRecent` invocation occurs across the full scenario set above.
30. Null `subscriptionStatus` on the viewer principal is treated as Free (defensive guardrail) — limiter applied, 51st request rejected with HTTP 429.
31. Transaction rollback does NOT release the slot: simulate a constraint surfacing failure that rolls back both the `chat_messages` INSERT and the `conversations.last_message_at` UPDATE; verify zero `chat_messages` rows persisted, `last_message_at` reverted to pre-request value, AND the daily bucket size still grew by 1 (no `releaseMostRecent` was called).
32. `premium_chat_send_cap_override = Long.MAX_VALUE` (or any value > 10,000) falls back to default 50.
33. Shadow-banned sender's send consumes a slot identically: a Free shadow-banned sender at slot 5 successfully sends one message, the daily bucket size becomes 6, and the response is HTTP 201 (the chat-foundation invisible-actor contract is preserved while the cap applies symmetrically).
34. Embedded-field silent-ignore + slot consumption: at slot 5 a Free caller A sends `POST /api/v1/chat/{conversation_id}/messages { content: "halo", embedded_post_id: <uuid> }`; the response is 201, the daily bucket size becomes 6 (exactly one slot consumed — not zero, not two), the inserted row has `embedded_post_id IS NULL`, AND a structured WARN log line records the ignored field with `event = "chat_send_embedded_field_ignored"` (matching the chat-foundation contract per `openspec/specs/chat-conversations/spec.md` § Send-message endpoint). The success-path assertion (slot 5 → slot 6 + WARN) proves the body IS parsed after the limiter passes. **The rejection-path assertion proves limiter-runs-before-body-parse**: at slot 51 the same request MUST return HTTP **429 specifically (NOT 422, NOT 400, NOT 201)** — a 422 / 400 differential would indicate the implementation parsed the body, validated/inspected the embedded fields, and rejected before reaching the limiter; the test MUST also assert via `SpyRateLimiter` that `tryAcquireInvocations == 1` AND zero `chat_send_embedded_field_ignored` WARN log lines were emitted on the 429 path (proving the body was never inspected for embedded fields on the rejected path).

#### Scenario: Test class discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*ChatSendRateLimitTest*'`
- **THEN** the class is discovered AND every numbered scenario above corresponds to at least one `@Test` method

### Requirement: Privileged chat-message emit path — caller-allowlist non-goal

The chat-handler emit step (`NotificationEmitter.emit(...)` for `type = 'chat_message'`) inherits the shipped chat-foundation auth + active-participant + bidirectional-block + shadow-ban-skip composition. The ONLY caller permitted to invoke this composition in this change is the chat send route handler at `POST /api/v1/chat/{conversation_id}/messages` (concretely: `ChatService.send(...)` or whichever class owns the chat send-handler tx). Any future code path that invokes `NotificationEmitter.emit(recipient, type = 'chat_message', ...)` MUST replicate the full composition pre-emit:

1. Bidirectional block check (`SELECT 1 FROM user_blocks WHERE ...`) returning 403 to the caller, OR delegating to the emitter's internal block-suppression
2. Active-participant verification (caller is a participant of the conversation referenced by `body_data.conversation_id`)
3. Sender-shadow-ban skip (read `viewer.isShadowBanned` from request principal — DO NOT issue an additional `SELECT is_shadow_banned`)
4. Tx-internal emit ordering (INSERT chat_messages → emit → UPDATE last_message_at → COMMIT)
5. Post-commit publish skip-coupling with `ChatRealtimeClient.publish(...)` on the SAME shadow-ban condition

OR a separate, distinctly-named notification type SHALL be introduced for the new caller (e.g., a future "broadcast announcement" admin feature would use `type = 'admin_announcement'`, NOT reuse `chat_message`).

This is a paper-trail non-goal — NOT enforced by code in this change. Reviewers of future PRs that emit `chat_message` outside the chat send route are expected to flag the missing composition. Codified here so the expectation is explicit and the chat-message notification surface stays minimal.

#### Scenario: Only the chat send route emits chat_message in this change

- **WHEN** searching the `:backend:ktor` source for callers of `notificationEmitter.emit(...)` with `type = "chat_message"` (or its DI-bound `ChatService` invocation site)
- **THEN** the only caller is the chat send route handler (the file landing the implementation of `POST /api/v1/chat/{conversation_id}/messages`)

#### Scenario: Future caller without composition fails review

- **GIVEN** a hypothetical future PR that adds a new route invoking `notificationEmitter.emit(recipient, type = "chat_message", ...)` directly (e.g., an admin "send system message" feature) without first running the chat-foundation auth + active-participant + bidirectional-block check + sender-shadow-ban skip + post-commit publish skip-coupling
- **WHEN** that PR is reviewed
- **THEN** the reviewer rejects (or asks for changes on) the PR citing this requirement; the future author either (a) adds the full composition before invoking emit, OR (b) introduces a separately-named notification type distinct from `chat_message` (e.g., `admin_announcement`, which would also need its own enum entry, body_data shape, PushCopy template, and security review)

