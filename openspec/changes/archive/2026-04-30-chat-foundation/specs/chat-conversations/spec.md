## ADDED Requirements

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

`POST /api/v1/chat/{conversation_id}/messages` SHALL be authenticated. Unauthenticated calls SHALL be rejected with `401 Unauthorized`. A malformed `conversation_id` path parameter SHALL be rejected with `400 Bad Request`. An unknown `conversation_id` (no row in `conversations`) SHALL be rejected with `404 Not Found`. The caller MUST be an active participant; non-participant or `left_at != NULL` SHALL receive `403 Forbidden`. The request body SHALL be `{ content: <string, 1–2000 chars> }`. Bidirectional block check SHALL match the canonical query in [`docs/05-Implementation.md:1304-1308`](../../../../docs/05-Implementation.md): if `user_blocks` contains a row in either direction between caller and the OTHER active participant of the conversation, the response SHALL be `403 Forbidden` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }`. Content length guard SHALL reject content longer than 2000 characters with `400 Bad Request`. Empty content (`""` or whitespace-only) SHALL be rejected with `400 Bad Request`. **Shadow-banned senders SHALL be allowed to send** (per `design.md` § D9 invisible-actor model — the row persists; non-banned recipients silently never see the row via the `GET /messages` shadow-ban filter). On success, the handler SHALL `INSERT INTO chat_messages` AND `UPDATE conversations SET last_message_at = NOW()` in the same transaction (one transaction; if either operation fails, both roll back), and return `201 Created` with the inserted row.

Any of `embedded_post_id`, `embedded_post_snapshot`, OR `embedded_post_edit_id` fields present in the request body SHALL be silently ignored by this change (deferred to `chat-embedded-posts`); a structured WARN log line per ignored field SHALL note the field name and the resulting message-id with `event = "chat_send_embedded_field_ignored"`.

The send query SHALL carry the annotation `// @allow-no-block-exclusion: chat-history-readable-after-block` per `design.md` § D10 (the canonical bidirectional block check is performed via an EXPLICIT query inside the handler, satisfying the 403 contract; the auto-applied NOT-IN-`user_blocks` join is suppressed because it would over-filter the participant lookup).

#### Scenario: Active participant sends a valid message
- **GIVEN** A and B are active participants in X with no `user_blocks` row in either direction
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo B" }`
- **THEN** the response is `201 Created` with the inserted row; a query of `chat_messages` shows the new row with `sender_id = A, conversation_id = X, content = "halo B"`; `conversations.last_message_at` for X is updated to the time of the INSERT

#### Scenario: Non-participant rejected
- **GIVEN** A is NOT a participant in conversation Y
- **WHEN** A calls `POST /api/v1/chat/Y/messages { content: "x" }`
- **THEN** the response is `403 Forbidden` and no row is inserted

#### Scenario: Block in either direction rejects send
- **GIVEN** A and B are active participants in X; a `user_blocks` row exists with `blocker_id = A, blocked_id = B`
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "x" }`
- **THEN** the response is `403 Forbidden` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }` and no row is inserted

#### Scenario: Block in the reverse direction also rejects send
- **GIVEN** A and B are active participants in X; a `user_blocks` row exists with `blocker_id = B, blocked_id = A`
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "x" }`
- **THEN** the response is `403 Forbidden` and no row is inserted

#### Scenario: 2001-char content rejected
- **WHEN** A calls `POST /api/v1/chat/X/messages` with `content` of length 2001
- **THEN** the response is `400 Bad Request` and no row is inserted

#### Scenario: Whitespace-only content rejected
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "   " }`
- **THEN** the response is `400 Bad Request` and no row is inserted

#### Scenario: Send updates last_message_at atomically
- **GIVEN** A is an active participant in X; X.last_message_at = T1
- **WHEN** A successfully sends a message at clock time T2 > T1
- **THEN** within the same DB transaction as the chat_messages INSERT, X.last_message_at is updated to T2; if the INSERT rolls back, last_message_at also rolls back

#### Scenario: embedded_post_id silently ignored
- **WHEN** A sends `POST /api/v1/chat/X/messages { content: "halo", embedded_post_id: <uuid> }`
- **THEN** the response is `201 Created`; the inserted row has `embedded_post_id IS NULL`; a structured WARN log line records the ignored field with the message-id, `event = "chat_send_embedded_field_ignored"`, and field name `embedded_post_id`

#### Scenario: embedded_post_snapshot silently ignored
- **WHEN** A sends `POST /api/v1/chat/X/messages { content: "halo", embedded_post_snapshot: { ... } }`
- **THEN** the response is `201 Created`; the inserted row has `embedded_post_snapshot IS NULL`; a structured WARN log line records the ignored field with field name `embedded_post_snapshot`

#### Scenario: embedded_post_edit_id silently ignored
- **WHEN** A sends `POST /api/v1/chat/X/messages { content: "halo", embedded_post_edit_id: <uuid> }`
- **THEN** the response is `201 Created`; the inserted row has `embedded_post_edit_id IS NULL`; a structured WARN log line records the ignored field with field name `embedded_post_edit_id`

#### Scenario: Shadow-banned sender's message persists
- **GIVEN** A is shadow-banned (`is_shadow_banned = TRUE`) and is an active participant in conversation X with B; no `user_blocks` row in either direction
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo" }`
- **THEN** the response is `201 Created` with the inserted row; the row exists in `chat_messages` with `sender_id = A`; B's subsequent `GET /api/v1/chat/X/messages` does NOT return this row (per the shadow-ban read-path filter)

#### Scenario: last_message_at rollback on INSERT failure
- **GIVEN** an active participant A in conversation X with X.last_message_at = T1; a fault is induced post-INSERT (e.g., the trigger or constraint surfacing causes the transaction to roll back)
- **WHEN** A calls the endpoint and the transaction rolls back
- **THEN** `conversations.last_message_at` for X remains T1 (the UPDATE was inside the same transaction and rolled back together with the failed INSERT — verifying the same-transaction guarantee from `design.md` § D3)

#### Scenario: Unknown conversation id returns 404
- **WHEN** A calls `POST /api/v1/chat/<uuid that is not in conversations>/messages { content: "x" }`
- **THEN** the response is `404 Not Found`

#### Scenario: Malformed UUID path parameter returns 400
- **WHEN** A calls `POST /api/v1/chat/not-a-uuid/messages { content: "x" }`
- **THEN** the response is `400 Bad Request`

#### Scenario: Unauthenticated call rejected
- **WHEN** the endpoint is called without an Authorization header
- **THEN** the response is `401 Unauthorized`

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
