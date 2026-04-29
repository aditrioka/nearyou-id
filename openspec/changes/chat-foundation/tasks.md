## 1. Schema (V15 Flyway migration)

- [x] 1.1 Create `backend/ktor/src/main/resources/db/migration/V15__chat_foundation.sql` with `CREATE TABLE conversations (...)` per canonical [`docs/05-Implementation.md`](../../../docs/05-Implementation.md): `id UUID PK DEFAULT gen_random_uuid(), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT, last_message_at TIMESTAMPTZ NULL`.
- [x] 1.2 Add `CREATE TABLE conversation_participants (...)` with `conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT, slot SMALLINT NOT NULL CHECK (slot IN (1, 2)), joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), left_at TIMESTAMPTZ NULL, last_read_at TIMESTAMPTZ NULL, PRIMARY KEY (conversation_id, user_id)`. Include the inline comment `-- last_read_at: read-receipt / unread-count storage; written by future chat-read-receipts change`.
- [x] 1.3 Add `CREATE UNIQUE INDEX conv_slot_unique ON conversation_participants (conversation_id, slot) WHERE left_at IS NULL`.
- [x] 1.4 Add `CREATE INDEX conversation_participants_user_active_idx ON conversation_participants (user_id) WHERE left_at IS NULL` and `CREATE INDEX conversation_participants_conversation_idx ON conversation_participants (conversation_id)`.
- [x] 1.5 Add `CREATE TABLE chat_messages (...)` per canonical: columns + the empty-message CHECK + the 3-column redaction atomicity CHECK. The `embedded_post_id` column ships with its FK to `posts(id) ON DELETE SET NULL`. The `redacted_by UUID NULL` and `embedded_post_edit_id UUID NULL` columns ship WITHOUT FK constraints; document each via `COMMENT ON COLUMN` matching the V9 pattern at [V9__reports_moderation.sql:72-73, 110-111](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql): `COMMENT ON COLUMN chat_messages.redacted_by IS 'FK to admin_users(id) ON DELETE SET NULL — deferred to the Phase 3.5 admin-users migration.';` and `COMMENT ON COLUMN chat_messages.embedded_post_edit_id IS 'FK to post_edits(id) ON DELETE SET NULL — deferred to the post-edit-history change which ships post_edits.';`. The `post_edits` table does NOT exist at V14 — verified via `grep -rn "CREATE TABLE post_edits"` returning no matches; canonical Phase 4 schema per `docs/08-Roadmap-Risk.md:102`.
- [x] 1.6 Add `chat_messages` indexes: `(conversation_id, created_at DESC)`, `(sender_id, created_at DESC)`, `(redacted_by, redacted_at DESC) WHERE redacted_at IS NOT NULL`.
- [x] 1.7 In V15, gated on `realtime` schema existing (matching the V2 gate at lines 64-66; plain Postgres has no `realtime` schema), CREATE the `participants_can_subscribe` policy on `realtime.messages` directly. The policy body matches V2 lines 73-86 STRUCTURALLY but the subscriber-side `AND NOT EXISTS (SELECT 1 FROM public.users WHERE id = cp.user_id AND is_shadow_banned = TRUE)` clause from V2 lines 81-84 is REMOVED — shadow-banned subscribers are allowed (per `design.md` § D9). Use the canonical `DROP POLICY IF EXISTS ... ; CREATE POLICY ...` idempotent pattern from V2.
- [x] 1.8 Run `./gradlew :backend:ktor:flywayMigrate` against the local Postgres (Docker Compose) and verify V15 applies cleanly.
- [x] 1.9 Verify policy installation in a Supabase-mode test database: `SELECT polname FROM pg_policy WHERE polrelid = 'realtime.messages'::regclass` returns `participants_can_subscribe`. Verify policy body via `pg_get_policydef()` does NOT contain `is_shadow_banned`.

## 2. Repository layer

- [x] 2.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/chat/ChatRepository.kt` with the four primary operations: `findOrCreate1to1(callerId, recipientId)`, `listMyConversations(userId, cursor, limit)`, `listMessages(conversationId, viewerId, cursor, limit)`, `sendMessage(conversationId, senderId, content)`.
- [x] 2.2 Implement `findOrCreate1to1` using the two advisory-lock pattern from `design.md` § D1: take user-pair lock first (`pg_advisory_xact_lock(hashtext(LEAST(:caller, :recipient)::text || ':' || GREATEST(:caller, :recipient)::text))`), then the existence-lookup, then if absent the conversation INSERT, then the conversation-id lock, then the two participant INSERTs. All in one transaction.
- [x] 2.3 Implement `listMyConversations` with **LEFT JOIN visible_users + COALESCE-to-placeholder** so shadow-banned partners surface masked, not filtered (per `design.md` § D9 invisible-actor model). The query site carries `// @allow-no-block-exclusion: chat-history-readable-after-block` per `design.md` § D10 — list view does NOT exclude conversations based on `user_blocks` in either direction (canonical "Existing conversations remain visible in history" at `docs/02-Product.md:234`). Canonical SQL shape: `SELECT c.*, COALESCE(u.username, 'akun_dihapus') AS partner_username, COALESCE(u.display_name, 'Akun Dihapus') AS partner_display_name, COALESCE(u.subscription_status IN ('premium_active', 'premium_billing_retry'), FALSE) AS partner_is_premium FROM conversations c JOIN conversation_participants me ON me.conversation_id = c.id AND me.user_id = :viewer AND me.left_at IS NULL JOIN conversation_participants other ON other.conversation_id = c.id AND other.user_id != :viewer LEFT JOIN visible_users u ON other.user_id = u.id ORDER BY c.last_message_at DESC NULLS LAST, c.created_at DESC LIMIT :limit`. Note: `users` has no `is_premium` column; the wire-format `is_premium` boolean is derived from `subscription_status` (per `V2__auth_foundation.sql`). Cursor adds `WHERE (c.last_message_at IS NOT NULL AND (c.last_message_at, c.id) < (:cursor_lmat, :cursor_id)) OR (c.last_message_at IS NULL AND :cursor_lmat IS NULL AND c.id < :cursor_id)` (NULL-safe — NULLS LAST ordering pages reach the NULL block last).
- [x] 2.4 Implement `listMessages` reading raw `chat_messages` (Repository own-content path; suppress `BlockExclusionJoinRule` via the source annotation `// @allow-no-block-exclusion: chat-history-readable-after-block` per `design.md` § D10). Active-participant pre-check via `conversation_participants WHERE conversation_id = :conv AND user_id = :viewer AND left_at IS NULL`; throw `NotParticipantException` if no row. **Shadow-ban filter inline**: append `AND (cm.sender_id = :viewer OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = cm.sender_id AND u.is_shadow_banned = TRUE))` to the message query (per spec Requirement: List-messages endpoint, shadow-ban filter section). Apply `(created_at, id) < (:cursor_ts, :cursor_id)` for pagination. At serialization, mask `content = NULL` and never serialize `redaction_reason` when `redacted_at IS NOT NULL`.
- [x] 2.5 Implement `sendMessage` with the canonical block check from [`docs/05-Implementation.md:1304-1308`](../../../docs/05-Implementation.md): bidirectional `user_blocks` query against the OTHER active participant. If hit, throw `BlockedException`. Otherwise INSERT chat_messages + UPDATE conversations.last_message_at = NOW() in the same transaction. Carry the `// @allow-no-block-exclusion: chat-history-readable-after-block` annotation on the participant-lookup query (the explicit block-check query is what enforces the 403 contract; the auto-applied lint NOT-IN would over-filter the participant lookup).
- [x] 2.6 Add a self-DM guard: `findOrCreate1to1` rejects if `callerId == recipientId` with `SelfDmException`. The check SHALL run BEFORE the user-pair `pg_advisory_xact_lock` is acquired (per spec scenario "Self-DM check happens before lock acquisition").
- [x] 2.7 Add a recipient-existence pre-check: `findOrCreate1to1` looks up the recipient via RAW `users` (NOT `visible_users`) — only return 404 if the recipient row is genuinely absent. A shadow-banned recipient is a valid creation target (per `design.md` § D9 — non-banned third parties cannot detect shadow-ban state via 201/404 differential).

## 3. REST routes

- [x] 3.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/chat/ChatRoutes.kt` registering four routes under the authenticated route block: `POST /api/v1/conversations`, `GET /api/v1/conversations`, `GET /api/v1/chat/{conversation_id}/messages`, `POST /api/v1/chat/{conversation_id}/messages`.
- [x] 3.2 `POST /api/v1/conversations` handler: deserialize `{ recipient_user_id }`, call repository, map the response codes per `chat-conversations` spec (201 vs 200 vs 403 vs 400 vs 404). On `BlockedException`, return `403 { error: "Tidak dapat mengirim pesan ke user ini" }`. On `SelfDmException`, return 400. On `RecipientNotFoundException`, return 404.
- [x] 3.3 `GET /api/v1/conversations` handler: parse `?cursor` + `?limit` (default 50, hard-cap 100), call repository, return `200 OK` with the cursor-paginated list including the next cursor in the response body.
- [x] 3.4 `GET /api/v1/chat/{conversation_id}/messages` handler: parse path param + query params, call repository, return `200 OK`. On `NotParticipantException`, return 403. On unknown conversation id (FK lookup miss), return 404.
- [x] 3.5 `POST /api/v1/chat/{conversation_id}/messages` handler: deserialize `{ content }`, validate length 1–2000 via the existing content-length middleware (register the chat path in the middleware config with `maxChars = 2000`), strip whitespace-only content with 400, call repository, return `201 Created` with the inserted row. On `BlockedException`, 403 + canonical body. On `NotParticipantException`, 403. On unknown conversation id, 404.
- [x] 3.6 Wire ignore-with-WARN for ALL THREE embed body fields (`embedded_post_id`, `embedded_post_snapshot`, `embedded_post_edit_id`): per ignored field, log a structured WARN with the resulting message-id, `event = "chat_send_embedded_field_ignored"`, and the specific field name. Do NOT pass any embed field to the repository; the row inserts with all three NULL.
- [x] 3.7 Register the chat routes in `Application.module()` (or wherever the route registration happens — match the existing pattern from `posts`, `replies`, `reports`).
- [x] 3.8 Register the chat path in `ContentLengthMiddleware` with the 2000-char cap (matches the canonical chat constraint per [`docs/02-Product.md:319`](../../../docs/02-Product.md)).

## 4. Cursor + serialization helpers

- [x] 4.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/chat/ChatCursors.kt` with `encodeConversationsCursor(lastMessageAt, id)`, `decodeConversationsCursor(token)`, `encodeMessagesCursor(createdAt, id)`, `decodeMessagesCursor(token)`. Use base64-encoded JSON shape `{ts: "<ISO-8601>", id: "<uuid>"}`.
- [x] 4.2 Reject malformed cursor tokens with `400 Bad Request`.
- [x] 4.3 Create `ChatMessageDto` with explicit serialization that NULL-masks `content` and OMITs `redaction_reason` when `redacted_at != null` per `chat-conversations` spec.
- [x] 4.4 Create `ConversationListItemDto` with the partner-profile subset (`id, username, display_name, is_premium`) sourced from `visible_users`.

## 5. Tests — schema constraint enforcement

- [x] 5.1 Test: empty-message CHECK rejects `(content NULL, embedded_post_id NULL, embedded_post_snapshot NULL)` INSERT.
- [x] 5.2 Test: empty-message CHECK accepts snapshot-only `(content NULL, embedded_post_id NULL, embedded_post_snapshot {...})` INSERT.
- [x] 5.3 Test: redaction atomicity CHECK rejects `(redacted_at = T, redacted_by NULL, redaction_reason NULL)`.
- [x] 5.4 Test: redaction atomicity CHECK accepts `(redacted_at = T, redacted_by = X, redaction_reason NULL)`.
- [x] 5.5 Test: slot CHECK rejects `slot = 0`, `slot = 3`, `slot = NULL`.
- [x] 5.6 Test: `conv_slot_unique` partial unique blocks a third active participant with `slot = 1` when one already exists with `slot = 1, left_at NULL`.
- [x] 5.7 Test: a `slot = 1, left_at IS NOT NULL` row plus a second `slot = 1, left_at IS NULL` row both succeed (partial unique only constrains active rows).

## 6. Tests — slot-race serialization

- [x] 6.1 Concurrency test: 10 concurrent `findOrCreate1to1(A, B)` calls AND 10 concurrent `findOrCreate1to1(B, A)` calls (20 total) → exactly one row in `conversations`, exactly two rows in `conversation_participants`, all 20 callers receive the same `conversation_id`. Use a real Postgres test database (Docker Compose), not an in-memory mock.
- [x] 6.2 Lock-key derivation test: assert the user-pair lock takes `hashtext(LEAST(...)::text || ':' || GREATEST(...)::text)` — instrument the repository to return the lock-key string, assert canonical-ordered + colon-separated.
- [x] 6.3 Per-conversation lock-presence test: assert that the participant INSERTs in `findOrCreate1to1` happen AFTER `pg_advisory_xact_lock(hashtext(:conversation_id::text))` was called in the same transaction. Use `pg_locks` view introspection.

## 7. Tests — REST endpoint behavior

- [x] 7.1 `POST /api/v1/conversations`: first call returns status code **specifically 201** (not 200) + new conversation; second call between same pair returns status code **specifically 200** (not 201) + same conversation_id. Assert both status codes explicitly to verify the D8 idempotency contract.
- [x] 7.2 `POST /api/v1/conversations`: block in either direction returns 403 with canonical body `{ error: "Tidak dapat mengirim pesan ke user ini" }`.
- [x] 7.3 `POST /api/v1/conversations`: self-DM returns 400.
- [x] 7.4 `POST /api/v1/conversations`: nonexistent recipient (no row in raw `users`) returns 404.
- [x] 7.4.a `POST /api/v1/conversations`: shadow-banned recipient (row in `users` with `is_shadow_banned = TRUE`) returns 201/200 (NOT 404 — verifies the no-shadow-ban-oracle contract per spec Scenario "Recipient is shadow-banned does NOT 404").
- [x] 7.4.b `POST /api/v1/conversations`: self-DM check happens BEFORE user-pair lock acquisition. Use `pg_locks` introspection on a separate connection to assert that the user-pair lock-key is NOT held by the request transaction when the 400 is returned.
- [x] 7.4.c `POST /api/v1/conversations`: unauthenticated call returns 401.
- [x] 7.5 `GET /api/v1/conversations`: returns active-participant rows ordered by `last_message_at DESC NULLS LAST, created_at DESC`.
- [x] 7.6 `GET /api/v1/conversations`: empty conversation (no messages) appears at the bottom (NULLS LAST).
- [x] 7.7 `GET /api/v1/conversations`: rows where caller has `left_at != NULL` are absent.
- [x] 7.8 `GET /api/v1/conversations`: cursor pagination is forward-only, stable, no overlap, no gaps over 100-conversation seed.
- [x] 7.9 `GET /api/v1/conversations`: hard cap at 100 rows when `?limit=500` is passed (silent clamp; status remains 200, NOT 400).
- [x] 7.9.a `GET /api/v1/conversations`: malformed cursor returns 400.
- [x] 7.9.b `GET /api/v1/conversations`: unauthenticated call returns 401.
- [x] 7.9.c `GET /api/v1/conversations`: bidirectional block does NOT exclude conversation — A blocks B (or B blocks A) → conversation between A and B IS still in A's list (and B's list, by symmetry). This verifies the canonical "Existing conversations remain visible in history" applied to the list view per `docs/02-Product.md:234`.
- [x] 7.9.d `GET /api/v1/conversations`: shadow-banned partner B → conversation surfaces with `partner_username = 'akun_dihapus'`, `partner_display_name = 'Akun Dihapus'`, `partner_is_premium = FALSE` (LEFT JOIN visible_users + COALESCE-to-placeholder).
- [x] 7.10 `GET /api/v1/chat/{id}/messages`: active participant gets ordered messages.
- [x] 7.11 `GET /api/v1/chat/{id}/messages`: non-participant returns 403.
- [x] 7.12 `GET /api/v1/chat/{id}/messages`: `left_at != NULL` participant returns 403.
- [x] 7.13 `GET /api/v1/chat/{id}/messages`: redacted message has `content == null`, `redacted_at` surfaced, `redaction_reason` omitted from response shape.
- [x] 7.14 `GET /api/v1/chat/{id}/messages`: cursor pagination by `(created_at DESC, id DESC)` over 75-message seed.
- [x] 7.14.a `GET /api/v1/chat/{id}/messages`: cursor boundary at tied `created_at` — seed two messages M1 and M2 with identical `created_at`; fetch with `?limit=1`, then page 2 — assert M1 and M2 are split exactly across the two pages with no duplication or loss (the `(created_at, id)` composite tiebreaker disambiguates).
- [x] 7.14.b `GET /api/v1/chat/{id}/messages`: malformed cursor returns 400.
- [x] 7.14.c `GET /api/v1/chat/{id}/messages`: malformed UUID path param returns 400.
- [x] 7.14.d `GET /api/v1/chat/{id}/messages`: unknown well-formed UUID returns 404.
- [x] 7.14.e `GET /api/v1/chat/{id}/messages`: unauthenticated call returns 401.
- [x] 7.14.f `GET /api/v1/chat/{id}/messages`: shadow-banned sender's messages hidden from non-banned viewer.
- [x] 7.14.g `GET /api/v1/chat/{id}/messages`: shadow-banned sender sees their own messages (own-content carve-out per-row).
- [x] 7.14.h `GET /api/v1/chat/{id}/messages`: block added AFTER conversation creation does NOT hide history — pre-existing M1, M2, M3; insert `user_blocks` (either direction); assert all three messages still in the response (200).
- [x] 7.15 `POST /api/v1/chat/{id}/messages`: active participant sends; row inserted with sender_id, content, conversation_id; `last_message_at` updated atomically.
- [x] 7.16 `POST /api/v1/chat/{id}/messages`: block in either direction returns 403 + canonical body.
- [x] 7.17 `POST /api/v1/chat/{id}/messages`: 2001-char content returns 400; whitespace-only content returns 400.
- [x] 7.18 `POST /api/v1/chat/{id}/messages`: `embedded_post_id` in body silently ignored; row inserts with `embedded_post_id IS NULL`; structured WARN log emitted with `event = "chat_send_embedded_field_ignored"` + field name.
- [x] 7.18.a `POST /api/v1/chat/{id}/messages`: `embedded_post_snapshot` in body silently ignored; row inserts with `embedded_post_snapshot IS NULL`; WARN log includes field name `embedded_post_snapshot`.
- [x] 7.18.b `POST /api/v1/chat/{id}/messages`: `embedded_post_edit_id` in body silently ignored; row inserts with `embedded_post_edit_id IS NULL`; WARN log includes field name `embedded_post_edit_id`.
- [x] 7.18.c `POST /api/v1/chat/{id}/messages`: shadow-banned sender's send succeeds (201) and persists; recipient's subsequent `GET /messages` does NOT return the row (filter applies).
- [x] 7.18.d `POST /api/v1/chat/{id}/messages`: `last_message_at` rollback test — induce a post-INSERT failure (e.g., a constraint violation surfaced after the application-layer guard), assert that `conversations.last_message_at` for the conversation is unchanged from its pre-call value (verifying same-transaction atomicity per design.md § D3).
- [x] 7.19 `POST /api/v1/chat/{id}/messages`: unknown conversation id returns 404.
- [x] 7.19.a `POST /api/v1/chat/{id}/messages`: malformed UUID path param returns 400.
- [x] 7.19.b `POST /api/v1/chat/{id}/messages`: unauthenticated call returns 401.

## 8. Tests — RLS realtime policy (mandatory per CLAUDE.md invariant)

- [x] 8.1 RLS: JWT `sub` not in `public.users` against topic `conversation:<valid_uuid>` denies (zero rows).
- [x] 8.2 RLS: valid user, NOT in `conversation_participants` for the topic conv, denies.
- [x] 8.3 RLS: valid user with `left_at IS NOT NULL` participant row denies.
- [x] 8.4 RLS: malformed topic `conversation` (no delimiter) denies via regex.
- [x] 8.5 RLS: malformed topic `conversation:` (no UUID after colon) denies via regex.
- [x] 8.6 RLS: invalid-UUID topic `conversation:not-a-uuid` denies via regex.
- [x] 8.7 RLS: SQL-injection topic `conversation:'; DROP TABLE conversations; --` denies; database remains intact.
- [x] 8.8 RLS: active participant (`left_at IS NULL`) with valid topic UUID matching their participation row allows.
- [x] 8.9 RLS test set MUST run in CI against a Supabase-mode Postgres container (not staging-only). If the existing CI Postgres service is plain, extend it with the Supabase realtime extension OR run a separate Supabase-mode service in the test job. A staging-only verification is INSUFFICIENT per the spec Requirement: "Realtime RLS test set runs against real schema." The mandatory invariant per `CLAUDE.md` requires the test set runs on every RLS policy change — this change INSTALLS the policy for the first time (with the V15-corrected definition per `design.md` § D9, dropping V2's subscriber-side `is_shadow_banned` clause).
- [x] 8.10 RLS: shadow-banned active participant ALLOWS (per spec Scenario "Shadow-banned active participant succeeds") — a user with `is_shadow_banned = TRUE` who is an active participant of the topic conversation gets the SELECT allowed. Verifies V15's policy correctly drops V2's subscriber-side `is_shadow_banned` clause.

## 9. Lint-rule update + coverage

- [x] 9.0.1 Update `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/BlockExclusionJoinRule.kt` to recognize the new annotation `// @allow-no-block-exclusion: chat-history-readable-after-block` on the enclosing function (matching the convention pattern from `// @allow-username-write` and `// @allow-privacy-write` recognized by their respective rules). When the annotation is present, the rule SHALL skip the bidirectional NOT-IN-`user_blocks` join enforcement for the function's body.
- [x] 9.0.2 Extend `lint/detekt-rules/src/test/kotlin/.../BlockExclusionJoinLintTest.kt` (or equivalent test class) with a positive case: a function annotated `// @allow-no-block-exclusion: chat-history-readable-after-block` containing a chat-history-style query SHALL NOT trip the rule.
- [x] 9.0.3 Add a negative case: a function lacking the annotation but containing the same chat-history-style query SHALL still trip the rule (proves the annotation is what's gating, not some other carve-out).
- [x] 9.0.4 Add the annotation token (`@allow-no-block-exclusion`) to the rule's recognized allowlist set with an explanatory comment citing `chat-foundation/design.md` § D10.
- [x] 9.1 Run `./gradlew :backend:ktor:detekt :lint:detekt-rules:test` and verify (a) `BlockExclusionJoinRule` does NOT fire on the list-messages and send-message participant-lookup queries (suppressed by the new annotation per 9.0.1–9.0.4), (b) the rule DOES fire on the conversation-list partner-lookup query if the bidirectional NOT-IN is removed (proves the rule still works on non-annotated sites), and (c) all `:lint:detekt-rules:test` cases pass including the two new ones from 9.0.2 + 9.0.3.
- [x] 9.2 Run `./gradlew ktlintCheck` per the pre-push verification convention from [`CLAUDE.md` § Delivery workflow](../../../CLAUDE.md).

## 10. Pre-push verification + staging deploy + smoke

- [x] 10.1 Local verification: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` all green.
- [x] 10.2 Trigger staging deploy: `gh workflow run deploy-staging.yml --ref <branch>` per the staging-deploy-before-archive convention codified in PR [#50](https://github.com/aditrioka/nearyou-id/pull/50). Wait for the deploy to land on staging.
- [x] 10.3 Verify V15 schema applied on staging: run a one-shot `psql` Cloud Run job (per the `dev/scripts/promote-staging-user.sh` shape, using the `^|^` delimiter to avoid the comma + `@` parsing footguns documented in `FOLLOW_UPS.md`) executing `\dt conversations`, `\dt conversation_participants`, `\dt chat_messages` and capturing the row counts as zero.
- [x] 10.4 Round-trip smoke against staging: create two staging users (or reuse two existing QA accounts), call `POST /api/v1/conversations`, then `POST /api/v1/chat/{id}/messages`, then `GET /api/v1/chat/{id}/messages`, then `GET /api/v1/conversations`, and assert the data plane round-trips correctly.
- [x] 10.5 Verify the V2-drafted RLS policy is now active in staging: `SELECT polname FROM pg_policy WHERE polrelid = 'realtime.messages'::regclass` returns `participants_can_subscribe`.
- [x] 10.6 Negative smoke: attempt to subscribe to `realtime:conversation:<uuid>` with a non-participant user's HS256 token; assert Supabase Realtime denies (visible in Supabase dashboard → Realtime logs).

## 11. Documentation + follow-up bookkeeping

- [x] 11.1 Verify no `docs/` amendment is required (the canonical schema in `docs/05-Implementation.md` matches the shipped V15 byte-for-byte; if the reconciliation pass surfaced any divergence, follow-up entries are in `FOLLOW_UPS.md`).
- [x] 11.2 Update PR title at implementation start: `gh pr edit <pr> --title 'feat(chat): chat-foundation (schema + REST data plane for 1:1 conversations)'`.
- [x] 11.3 Update PR body at implementation start to reflect the current state per the same-PR iteration rule in `CLAUDE.md`.
- [ ] 11.4 At archive: confirm `openspec/changes/chat-foundation/` is moved under `archive/` and `openspec/specs/chat-conversations/` (NEW) + `openspec/specs/auth-realtime/` (MODIFIED) are synced.
- [x] 11.5 Open follow-up entry in `FOLLOW_UPS.md` titled `chat-realtime-broadcast-publish` capturing what's deferred (Supabase Realtime broadcast publish from Ktor, Phase 2 #9 realtime layer) so the follow-up has a tracked anchor.
- [x] 11.6 Open follow-up entry in `FOLLOW_UPS.md` titled `chat-rate-limit-50-per-day` capturing the deferred Free 50/day daily-send cap (matches the like-rate-limit + reply-rate-limit shape).
- [x] 11.7 Open follow-up entry in `FOLLOW_UPS.md` titled `chat-message-notification-emit-sites` for the `chat_message` and `chat_message_redacted` notification emit-sites, deferred until chat-realtime-broadcast lands the publish surface. The fcm-push-dispatch composite (PR [#60](https://github.com/aditrioka/nearyou-id/pull/60)) will pick them up automatically once emitted.
- [x] 11.8 Open follow-up entry in `FOLLOW_UPS.md` titled `chat-realtime-broadcast-publish-side-shadow-ban-filter` (medium priority — hard prerequisite for `chat-realtime-broadcast`). When the realtime broadcast layer ships, the publish-side SHALL skip emit when `sender.is_shadow_banned = TRUE` (mirroring the read-path inline filter in `GET /messages` per spec Requirement: List-messages endpoint). Without this, shadow-banned senders' messages would broadcast normally to non-banned recipients via WSS while being filtered from the REST `GET /messages` path — REST/WSS asymmetry. Note: V15 already installs the realtime RLS subscriber-side policy WITHOUT the V2 `is_shadow_banned` clause (per design.md § D9 + spec Requirement: Realtime RLS policy installed with shadow-ban-aware subscriber semantics), so the subscriber-side reconciliation is COMPLETE in this change; only the publish-side filter remains.
