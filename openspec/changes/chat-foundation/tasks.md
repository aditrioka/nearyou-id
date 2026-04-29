## 1. Schema (V15 Flyway migration)

- [ ] 1.1 Create `backend/ktor/src/main/resources/db/migration/V15__chat_foundation.sql` with `CREATE TABLE conversations (...)` per canonical [`docs/05-Implementation.md`](../../../docs/05-Implementation.md): `id UUID PK DEFAULT gen_random_uuid(), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT, last_message_at TIMESTAMPTZ NULL`.
- [ ] 1.2 Add `CREATE TABLE conversation_participants (...)` with `conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT, slot SMALLINT NOT NULL CHECK (slot IN (1, 2)), joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), left_at TIMESTAMPTZ NULL, last_read_at TIMESTAMPTZ NULL, PRIMARY KEY (conversation_id, user_id)`. Include the inline comment `-- last_read_at: read-receipt / unread-count storage; written by future chat-read-receipts change`.
- [ ] 1.3 Add `CREATE UNIQUE INDEX conv_slot_unique ON conversation_participants (conversation_id, slot) WHERE left_at IS NULL`.
- [ ] 1.4 Add `CREATE INDEX conversation_participants_user_active_idx ON conversation_participants (user_id) WHERE left_at IS NULL` and `CREATE INDEX conversation_participants_conversation_idx ON conversation_participants (conversation_id)`.
- [ ] 1.5 Add `CREATE TABLE chat_messages (...)` per canonical: columns + the empty-message CHECK + the 3-column redaction atomicity CHECK. Annotate the `redacted_by UUID NULL` column with `-- @allow-admin-fk-deferred: phase-3.5 -- FK to admin_users(id) ON DELETE SET NULL added when admin schema lands` matching the V9 pattern at [V9__reports_moderation.sql:20](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql).
- [ ] 1.6 Add `chat_messages` indexes: `(conversation_id, created_at DESC)`, `(sender_id, created_at DESC)`, `(redacted_by, redacted_at DESC) WHERE redacted_at IS NOT NULL`.
- [ ] 1.7 Run `./gradlew :backend:ktor:flywayMigrate` against the local Postgres (Docker Compose) and verify V15 applies cleanly.
- [ ] 1.8 Verify the V2-drafted RLS policy on `realtime.messages` activates: spin up a Supabase-mode local instance (or skip if local Postgres is plain — note in PR), inspect `pg_policies` for `participants_can_subscribe`.

## 2. Repository layer

- [ ] 2.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/chat/ChatRepository.kt` with the four primary operations: `findOrCreate1to1(callerId, recipientId)`, `listMyConversations(userId, cursor, limit)`, `listMessages(conversationId, viewerId, cursor, limit)`, `sendMessage(conversationId, senderId, content)`.
- [ ] 2.2 Implement `findOrCreate1to1` using the two advisory-lock pattern from `design.md` § D1: take user-pair lock first (`pg_advisory_xact_lock(hashtext(LEAST(:caller, :recipient)::text || ':' || GREATEST(:caller, :recipient)::text))`), then the existence-lookup, then if absent the conversation INSERT, then the conversation-id lock, then the two participant INSERTs. All in one transaction.
- [ ] 2.3 Implement `listMyConversations` with the canonical SQL: `SELECT FROM conversations c JOIN conversation_participants me ON ... JOIN conversation_participants other ON c.id = other.conversation_id AND other.user_id != :viewer_id JOIN visible_users u ON other.user_id = u.id LEFT JOIN user_blocks b1 ON b1.blocker_id = :viewer AND b1.blocked_id = u.id LEFT JOIN user_blocks b2 ON b2.blocker_id = u.id AND b2.blocked_id = :viewer WHERE me.user_id = :viewer AND me.left_at IS NULL AND b1.blocker_id IS NULL AND b2.blocker_id IS NULL ORDER BY c.last_message_at DESC NULLS LAST, c.created_at DESC LIMIT :limit`. Cursor adds `WHERE (c.last_message_at, c.id) < (:cursor_lmat, :cursor_id)` (with NULL-safe variant).
- [ ] 2.4 Implement `listMessages` reading raw `chat_messages` (Repository own-content path; the `BlockExclusionJoinRule` allowlist permits this — verify the lint rule does NOT fire). Active-participant pre-check via `conversation_participants WHERE conversation_id = :conv AND user_id = :viewer AND left_at IS NULL`; throw `NotParticipantException` if no row. Apply `(created_at, id) < (:cursor_ts, :cursor_id)` for pagination. At serialization, mask `content = NULL` and omit `redaction_reason` when `redacted_at IS NOT NULL`.
- [ ] 2.5 Implement `sendMessage` with the canonical block check from [`docs/05-Implementation.md:1304-1308`](../../../docs/05-Implementation.md): bidirectional `user_blocks` query against the OTHER active participant. If hit, throw `BlockedException`. Otherwise INSERT chat_messages + UPDATE conversations.last_message_at = NOW() in the same transaction.
- [ ] 2.6 Add a self-DM guard: `findOrCreate1to1` rejects if `callerId == recipientId` with `SelfDmException`.
- [ ] 2.7 Add a recipient-existence pre-check: `findOrCreate1to1` looks up the recipient via `visible_users`; missing or shadow-banned-fully-hidden recipient throws `RecipientNotFoundException`.

## 3. REST routes

- [ ] 3.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/chat/ChatRoutes.kt` registering four routes under the authenticated route block: `POST /api/v1/conversations`, `GET /api/v1/conversations`, `GET /api/v1/chat/{conversation_id}/messages`, `POST /api/v1/chat/{conversation_id}/messages`.
- [ ] 3.2 `POST /api/v1/conversations` handler: deserialize `{ recipient_user_id }`, call repository, map the response codes per `chat-conversations` spec (201 vs 200 vs 403 vs 400 vs 404). On `BlockedException`, return `403 { error: "Tidak dapat mengirim pesan ke user ini" }`. On `SelfDmException`, return 400. On `RecipientNotFoundException`, return 404.
- [ ] 3.3 `GET /api/v1/conversations` handler: parse `?cursor` + `?limit` (default 50, hard-cap 100), call repository, return `200 OK` with the cursor-paginated list including the next cursor in the response body.
- [ ] 3.4 `GET /api/v1/chat/{conversation_id}/messages` handler: parse path param + query params, call repository, return `200 OK`. On `NotParticipantException`, return 403. On unknown conversation id (FK lookup miss), return 404.
- [ ] 3.5 `POST /api/v1/chat/{conversation_id}/messages` handler: deserialize `{ content }`, validate length 1–2000 via the existing content-length middleware (register the chat path in the middleware config with `maxChars = 2000`), strip whitespace-only content with 400, call repository, return `201 Created` with the inserted row. On `BlockedException`, 403 + canonical body. On `NotParticipantException`, 403. On unknown conversation id, 404.
- [ ] 3.6 Wire `embedded_post_id` ignore-with-WARN: if the request body has `embedded_post_id` populated, log a structured WARN with the resulting message-id and an `event = "chat_send_embedded_post_ignored"` field. Do NOT pass the field to the repository; the row inserts with `embedded_post_id IS NULL`.
- [ ] 3.7 Register the chat routes in `Application.module()` (or wherever the route registration happens — match the existing pattern from `posts`, `replies`, `reports`).
- [ ] 3.8 Register the chat path in `ContentLengthMiddleware` with the 2000-char cap (matches the canonical chat constraint per [`docs/02-Product.md:319`](../../../docs/02-Product.md)).

## 4. Cursor + serialization helpers

- [ ] 4.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/chat/ChatCursors.kt` with `encodeConversationsCursor(lastMessageAt, id)`, `decodeConversationsCursor(token)`, `encodeMessagesCursor(createdAt, id)`, `decodeMessagesCursor(token)`. Use base64-encoded JSON shape `{ts: "<ISO-8601>", id: "<uuid>"}`.
- [ ] 4.2 Reject malformed cursor tokens with `400 Bad Request`.
- [ ] 4.3 Create `ChatMessageDto` with explicit serialization that NULL-masks `content` and OMITs `redaction_reason` when `redacted_at != null` per `chat-conversations` spec.
- [ ] 4.4 Create `ConversationListItemDto` with the partner-profile subset (`id, username, display_name, is_premium`) sourced from `visible_users`.

## 5. Tests — schema constraint enforcement

- [ ] 5.1 Test: empty-message CHECK rejects `(content NULL, embedded_post_id NULL, embedded_post_snapshot NULL)` INSERT.
- [ ] 5.2 Test: empty-message CHECK accepts snapshot-only `(content NULL, embedded_post_id NULL, embedded_post_snapshot {...})` INSERT.
- [ ] 5.3 Test: redaction atomicity CHECK rejects `(redacted_at = T, redacted_by NULL, redaction_reason NULL)`.
- [ ] 5.4 Test: redaction atomicity CHECK accepts `(redacted_at = T, redacted_by = X, redaction_reason NULL)`.
- [ ] 5.5 Test: slot CHECK rejects `slot = 0`, `slot = 3`, `slot = NULL`.
- [ ] 5.6 Test: `conv_slot_unique` partial unique blocks a third active participant with `slot = 1` when one already exists with `slot = 1, left_at NULL`.
- [ ] 5.7 Test: a `slot = 1, left_at IS NOT NULL` row plus a second `slot = 1, left_at IS NULL` row both succeed (partial unique only constrains active rows).

## 6. Tests — slot-race serialization

- [ ] 6.1 Concurrency test: 10 concurrent `findOrCreate1to1(A, B)` calls AND 10 concurrent `findOrCreate1to1(B, A)` calls (20 total) → exactly one row in `conversations`, exactly two rows in `conversation_participants`, all 20 callers receive the same `conversation_id`. Use a real Postgres test database (Docker Compose), not an in-memory mock.
- [ ] 6.2 Lock-key derivation test: assert the user-pair lock takes `hashtext(LEAST(...)::text || ':' || GREATEST(...)::text)` — instrument the repository to return the lock-key string, assert canonical-ordered + colon-separated.
- [ ] 6.3 Per-conversation lock-presence test: assert that the participant INSERTs in `findOrCreate1to1` happen AFTER `pg_advisory_xact_lock(hashtext(:conversation_id::text))` was called in the same transaction. Use `pg_locks` view introspection.

## 7. Tests — REST endpoint behavior

- [ ] 7.1 `POST /api/v1/conversations`: first call returns 201 + new conversation; second call between same pair returns 200 + same conversation id.
- [ ] 7.2 `POST /api/v1/conversations`: block in either direction returns 403 with canonical body `{ error: "Tidak dapat mengirim pesan ke user ini" }`.
- [ ] 7.3 `POST /api/v1/conversations`: self-DM returns 400.
- [ ] 7.4 `POST /api/v1/conversations`: nonexistent recipient returns 404.
- [ ] 7.5 `GET /api/v1/conversations`: returns active-participant rows ordered by `last_message_at DESC NULLS LAST, created_at DESC`.
- [ ] 7.6 `GET /api/v1/conversations`: empty conversation (no messages) appears at the bottom (NULLS LAST).
- [ ] 7.7 `GET /api/v1/conversations`: rows where caller has `left_at != NULL` are absent.
- [ ] 7.8 `GET /api/v1/conversations`: cursor pagination is forward-only, stable, no overlap, no gaps over 100-conversation seed.
- [ ] 7.9 `GET /api/v1/conversations`: hard cap at 100 rows when `?limit=500` is passed.
- [ ] 7.10 `GET /api/v1/chat/{id}/messages`: active participant gets ordered messages.
- [ ] 7.11 `GET /api/v1/chat/{id}/messages`: non-participant returns 403.
- [ ] 7.12 `GET /api/v1/chat/{id}/messages`: `left_at != NULL` participant returns 403.
- [ ] 7.13 `GET /api/v1/chat/{id}/messages`: redacted message has `content == null`, `redacted_at` surfaced, `redaction_reason` omitted from response shape.
- [ ] 7.14 `GET /api/v1/chat/{id}/messages`: cursor pagination by `(created_at DESC, id DESC)` over 75-message seed.
- [ ] 7.15 `POST /api/v1/chat/{id}/messages`: active participant sends; row inserted with sender_id, content, conversation_id; `last_message_at` updated atomically.
- [ ] 7.16 `POST /api/v1/chat/{id}/messages`: block in either direction returns 403 + canonical body.
- [ ] 7.17 `POST /api/v1/chat/{id}/messages`: 2001-char content returns 400; whitespace-only content returns 400.
- [ ] 7.18 `POST /api/v1/chat/{id}/messages`: `embedded_post_id` in body silently ignored; row inserts with `embedded_post_id IS NULL`; structured WARN log emitted with the message-id and `event = "chat_send_embedded_post_ignored"`.
- [ ] 7.19 `POST /api/v1/chat/{id}/messages`: unknown conversation id returns 404.

## 8. Tests — RLS realtime policy (mandatory per CLAUDE.md invariant)

- [ ] 8.1 RLS: JWT `sub` not in `public.users` against topic `conversation:<valid_uuid>` denies (zero rows).
- [ ] 8.2 RLS: valid user, NOT in `conversation_participants` for the topic conv, denies.
- [ ] 8.3 RLS: valid user with `left_at IS NOT NULL` participant row denies.
- [ ] 8.4 RLS: malformed topic `conversation` (no delimiter) denies via regex.
- [ ] 8.5 RLS: malformed topic `conversation:` (no UUID after colon) denies via regex.
- [ ] 8.6 RLS: invalid-UUID topic `conversation:not-a-uuid` denies via regex.
- [ ] 8.7 RLS: SQL-injection topic `conversation:'; DROP TABLE conversations; --` denies; database remains intact.
- [ ] 8.8 RLS: active participant (`left_at IS NULL`) with valid topic UUID matching their participation row allows.
- [ ] 8.9 If the local Postgres is plain (not Supabase), document in PR body that the RLS test set runs against staging only; otherwise run locally via Supabase CLI.

## 9. Tests — block-exclusion lint coverage

- [ ] 9.1 Run `./gradlew :backend:ktor:detekt :lint:detekt-rules:test` and verify `BlockExclusionJoinRule` does NOT fire on the new chat queries (the partner-lookup includes the canonical bidirectional NOT-IN; the message read/write is in the Repository own-content allowlist).
- [ ] 9.2 Run `./gradlew ktlintCheck` per the pre-push verification convention from [`CLAUDE.md` § Delivery workflow](../../../CLAUDE.md).

## 10. Pre-push verification + staging deploy + smoke

- [ ] 10.1 Local verification: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` all green.
- [ ] 10.2 Trigger staging deploy: `gh workflow run deploy-staging.yml --ref <branch>` per the staging-deploy-before-archive convention codified in PR [#50](https://github.com/aditrioka/nearyou-id/pull/50). Wait for the deploy to land on staging.
- [ ] 10.3 Verify V15 schema applied on staging: run a one-shot `psql` Cloud Run job (per the `dev/scripts/promote-staging-user.sh` shape, using the `^|^` delimiter to avoid the comma + `@` parsing footguns documented in `FOLLOW_UPS.md`) executing `\dt conversations`, `\dt conversation_participants`, `\dt chat_messages` and capturing the row counts as zero.
- [ ] 10.4 Round-trip smoke against staging: create two staging users (or reuse two existing QA accounts), call `POST /api/v1/conversations`, then `POST /api/v1/chat/{id}/messages`, then `GET /api/v1/chat/{id}/messages`, then `GET /api/v1/conversations`, and assert the data plane round-trips correctly.
- [ ] 10.5 Verify the V2-drafted RLS policy is now active in staging: `SELECT polname FROM pg_policy WHERE polrelid = 'realtime.messages'::regclass` returns `participants_can_subscribe`.
- [ ] 10.6 Negative smoke: attempt to subscribe to `realtime:conversation:<uuid>` with a non-participant user's HS256 token; assert Supabase Realtime denies (visible in Supabase dashboard → Realtime logs).

## 11. Documentation + follow-up bookkeeping

- [ ] 11.1 Verify no `docs/` amendment is required (the canonical schema in `docs/05-Implementation.md` matches the shipped V15 byte-for-byte; if the reconciliation pass surfaced any divergence, follow-up entries are in `FOLLOW_UPS.md`).
- [ ] 11.2 Update PR title at implementation start: `gh pr edit <pr> --title 'feat(chat): chat-foundation (schema + REST data plane for 1:1 conversations)'`.
- [ ] 11.3 Update PR body at implementation start to reflect the current state per the same-PR iteration rule in `CLAUDE.md`.
- [ ] 11.4 At archive: confirm `openspec/changes/chat-foundation/` is moved under `archive/` and `openspec/specs/chat-conversations/` (NEW) + `openspec/specs/auth-realtime/` (MODIFIED) are synced.
- [ ] 11.5 Open follow-up entry in `FOLLOW_UPS.md` titled `chat-realtime-broadcast-publish` capturing what's deferred (Supabase Realtime broadcast publish from Ktor, Phase 2 #9 realtime layer) so the follow-up has a tracked anchor.
- [ ] 11.6 Open follow-up entry in `FOLLOW_UPS.md` titled `chat-rate-limit-50-per-day` capturing the deferred Free 50/day daily-send cap (matches the like-rate-limit + reply-rate-limit shape).
