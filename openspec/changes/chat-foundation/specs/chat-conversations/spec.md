## ADDED Requirements

### Requirement: Conversation schema

The system SHALL persist 1:1 conversations in three tables: `conversations`, `conversation_participants`, `chat_messages`. The schema SHALL match the canonical specification in [`docs/05-Implementation.md` § Direct Messaging Implementation](../../../../docs/05-Implementation.md) verbatim except for the deferred `redacted_by` admin-FK constraint.

`conversations` columns: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `last_message_at TIMESTAMPTZ NULL`.

`conversation_participants` columns: `conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE`, `user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `slot SMALLINT NOT NULL CHECK (slot IN (1, 2))`, `joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `left_at TIMESTAMPTZ NULL`, `last_read_at TIMESTAMPTZ NULL`. PRIMARY KEY `(conversation_id, user_id)`. UNIQUE INDEX `conv_slot_unique ON (conversation_id, slot) WHERE left_at IS NULL`. INDEX `(user_id) WHERE left_at IS NULL`. INDEX `(conversation_id)`.

`chat_messages` columns: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE`, `sender_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `content VARCHAR(2000) NULL`, `embedded_post_id UUID NULL REFERENCES posts(id) ON DELETE SET NULL`, `embedded_post_snapshot JSONB NULL`, `embedded_post_edit_id UUID NULL REFERENCES post_edits(id) ON DELETE SET NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `redacted_at TIMESTAMPTZ NULL`, `redacted_by UUID NULL` (FK constraint to `admin_users(id) ON DELETE SET NULL` is DEFERRED to Phase 3.5; the column is annotated `-- @allow-admin-fk-deferred: phase-3.5` matching the V9 pattern), `redaction_reason TEXT NULL`. CHECKs: empty-message guard `(content IS NOT NULL OR embedded_post_id IS NOT NULL OR embedded_post_snapshot IS NOT NULL)`; redaction atomicity `((redacted_at IS NULL AND redacted_by IS NULL AND redaction_reason IS NULL) OR (redacted_at IS NOT NULL AND redacted_by IS NOT NULL))`. INDEXes: `(conversation_id, created_at DESC)`, `(sender_id, created_at DESC)`, `(redacted_by, redacted_at DESC) WHERE redacted_at IS NOT NULL`.

#### Scenario: Schema applies cleanly via Flyway
- **WHEN** the V15 migration runs against a database that has V1–V14 applied
- **THEN** the three tables, six indexes, and four CHECK constraints are created without error, and `flyway_schema_history` shows V15 as `success = true`

#### Scenario: V2-drafted realtime RLS policy activates
- **WHEN** V15 has applied and `conversation_participants` exists
- **THEN** the V2 RLS policy `participants_can_subscribe ON realtime.messages` is now active (the V2 `IF EXISTS conversation_participants` gate evaluates true), and a `SELECT` against `realtime.messages` from a non-participant JWT context is denied

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
- `200 OK` with the existing conversation row + both participant rows when an existing conversation between the canonical pair is returned (idempotent).
- `403 Forbidden` with the user-facing string `"Tidak dapat mengirim pesan ke user ini"` when EITHER direction in `user_blocks` between caller and recipient exists.
- `400 Bad Request` when `recipient_user_id == caller_user_id` (self-DM rejected).
- `404 Not Found` when the recipient user does not exist or is not visible (per `visible_users`).

The endpoint SHALL take the user-pair advisory lock for the duration of the create-or-return transaction.

#### Scenario: First call between two users creates a conversation
- **GIVEN** users A and B with no prior conversation between them and no `user_blocks` row in either direction
- **WHEN** A calls `POST /api/v1/conversations { recipient_user_id: B }`
- **THEN** the response is `201 Created` with a conversation row + two participant rows (A `slot = 1`, B `slot = 2`)

#### Scenario: Second call between the same pair returns the existing conversation
- **GIVEN** an existing conversation X between A and B with no participant having `left_at != NULL`
- **WHEN** A or B calls `POST /api/v1/conversations` for the canonical pair
- **THEN** the response is `200 OK` with the conversation row of X (same `id`)

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

#### Scenario: Unauthenticated call rejected
- **WHEN** the endpoint is called without an Authorization header
- **THEN** the response is `401 Unauthorized`

### Requirement: List-conversations endpoint

`GET /api/v1/conversations` SHALL be authenticated. It SHALL return a cursor-paginated list of conversations the caller is an active participant in (`conversation_participants.user_id = caller AND left_at IS NULL`), ordered by `last_message_at DESC NULLS LAST, created_at DESC`. Each row SHALL include the conversation id, `created_at`, `last_message_at`, and the OTHER participant's profile (subset of `visible_users` columns: `id`, `username`, `display_name`, `is_premium`). The other-participant lookup SHALL use the bidirectional block-exclusion join per `BlockExclusionJoinRule`. Default page size 50; query parameter `?cursor` accepts an opaque cursor token.

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
- **THEN** X is in the list, but B's profile fields render via `visible_users` placeholder semantics (shadow-banned partner masked)

#### Scenario: Cursor pagination is forward-only and stable
- **GIVEN** A has 100 conversations
- **WHEN** A fetches page 1 (size 50), then uses the returned cursor to fetch page 2 (size 50)
- **THEN** the union of the two pages is the full 100 conversations with no overlap and no gaps, ordered as defined

#### Scenario: Hard cap on page size
- **WHEN** A passes `?limit=500`
- **THEN** the response contains at most 100 rows (hard cap)

### Requirement: List-messages endpoint

`GET /api/v1/chat/{conversation_id}/messages` SHALL be authenticated. The caller MUST be an active participant of the conversation (`conversation_participants.user_id = caller AND conversation_id = :conv_id AND left_at IS NULL`); a non-participant or `left_at != NULL` participant SHALL receive `403 Forbidden`. The response SHALL be a cursor-paginated list of `chat_messages` rows ordered by `(created_at DESC, id DESC)`, default 50/page, hard cap 100/page. The cursor SHALL be a base64-encoded `{created_at, id}` pair.

When `redacted_at IS NOT NULL` for a row, the response row SHALL set `content: null`, surface `redacted_at`, and OMIT `redaction_reason`. The original `content` value SHALL NOT be returned in any response shape.

The query SHALL read directly from raw `chat_messages` (this is a Repository own-content path explicitly allowed in the `CLAUDE.md` raw-`FROM` carve-out).

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
- **THEN** the response contains at most 100 rows

### Requirement: Send-message endpoint

`POST /api/v1/chat/{conversation_id}/messages` SHALL be authenticated. The caller MUST be an active participant; non-participant or `left_at != NULL` SHALL receive `403 Forbidden`. The request body SHALL be `{ content: <string, 1–2000 chars> }`. Bidirectional block check SHALL match the canonical query in [`docs/05-Implementation.md:1304-1308`](../../../../docs/05-Implementation.md): if `user_blocks` contains a row in either direction between caller and the OTHER active participant of the conversation, the response SHALL be `403 Forbidden` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }`. Content length guard SHALL reject content longer than 2000 characters with `400 Bad Request`. Empty content (`""` or whitespace-only) SHALL be rejected with `400 Bad Request`. On success, the handler SHALL `INSERT INTO chat_messages` AND `UPDATE conversations SET last_message_at = NOW()` in the same transaction, and return `201 Created` with the inserted row.

Any `embedded_post_id` field present in the request body SHALL be silently ignored by this change (deferred to `chat-embedded-posts`); a structured WARN log line SHALL note the ignored field with the message-id.

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
- **THEN** the response is `201 Created`; the inserted row has `embedded_post_id IS NULL`; a structured WARN log line records the ignored field with the message-id

### Requirement: Realtime RLS test set runs against real schema

The mandatory RLS test set per [`CLAUDE.md` § Critical invariants](../../../../CLAUDE.md) ("RLS changes: mandatory test case JWT `sub` not in `public.users` → deny on every policy change") SHALL run against the V2-drafted policy on `realtime.messages` activated by V15. The set SHALL cover the deny cases enumerated in [`docs/05-Implementation.md:108-110`](../../../../docs/05-Implementation.md): JWT-sub-not-in-users, non-participant, `left_at`-participant, malformed topic (`conversation:` no UUID, `conversation` no delimiter), invalid-UUID topic (`conversation:not-a-uuid`), SQL-injection topic.

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
