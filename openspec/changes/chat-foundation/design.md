## Context

The `chat-foundation` change ships the data plane for 1:1 chat — schema, REST endpoints, block enforcement on send + create — without the Supabase Realtime broadcast layer, embedded-post send/render, admin redaction, daily-send rate cap, or Perspective API screening. The constraint comes from scope discipline: chat is canonical Phase 2 work that the [`docs/`](../../../docs/) corpus has already specced in detail across [`02-Product.md`](../../../docs/02-Product.md), [`04-Architecture.md`](../../../docs/04-Architecture.md), and [`05-Implementation.md`](../../../docs/05-Implementation.md), but landing it as a single change would dwarf any prior change in this repo. The natural cut is "data plane first" — schema + REST — because:

1. The realtime broadcast layer in [`docs/05-Implementation.md` § Chat Flow](../../../docs/05-Implementation.md) is conditional on the schema existing.
2. The V2 migration already drafted the gated `realtime.messages` RLS policy ([V2__auth_foundation.sql:58-78](../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql)) which activates the moment `conversation_participants` exists.
3. The auth-realtime token endpoint already ships ([`auth-realtime` spec](../../specs/auth-realtime/spec.md)) and has been waiting for a real consumer.
4. The fcm-push-dispatch composite ([fcm-push-dispatch](../../specs/fcm-push-dispatch/spec.md), PR [#60](https://github.com/aditrioka/nearyou-id/pull/60)) wires the dispatcher but has no `chat_message` emit-site to push — chat-foundation does NOT add the emit-site (deferred to the realtime change once the broadcast layer lands), but it does land the schema that future emit-sites will write through.

The constraints driving the design are:

- **Slot-race correctness**: two concurrent `POST /api/v1/conversations` calls from User A → User B and User B → User A MUST result in exactly one conversation, not two; and at participant-insert time, no third active row can squeeze in. The canonical pattern in [`docs/05-Implementation.md` § Insert Flow](../../../docs/05-Implementation.md) uses a per-conversation advisory lock + a partial unique index on `(conversation_id, slot)`. This change extends that with a per-user-pair advisory lock at create-or-return time.
- **Block enforcement**: the canonical block check at [`docs/05-Implementation.md:1304-1308`](../../../docs/05-Implementation.md) checks `user_blocks` bidirectionally for the canonical sender↔other-participant pair; existing conversation history remains readable on both sides per [`docs/02-Product.md:234`](../../../docs/02-Product.md), only new sends are rejected.
- **CLAUDE.md invariants**: `BlockExclusionJoinRule` (bidirectional NOT-IN for any business query touching `users`/`posts`/`chat_messages`/`post_replies`); raw `FROM chat_messages` allowed only in the Repository own-content path (which is exactly what these endpoints are); RLS test "JWT sub not in `users` → deny" mandatory on every policy change (the V2-drafted policy activates here, so the test set runs against real schema for the first time).
- **Phase 3.5 admin FK deferral**: the `redacted_by` column on `chat_messages` references `admin_users(id)` which doesn't exist yet. This change ships the column without the FK, annotated `-- @allow-admin-fk-deferred: phase-3.5` matching the existing V9 reports/moderation pattern at [V9__reports_moderation.sql:20](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql).

## Goals / Non-Goals

**Goals:**

1. Ship the canonical schema verbatim from [`docs/05-Implementation.md` § Direct Messaging Implementation](../../../docs/05-Implementation.md) — no schema improvisation. Where canonical and proposal would diverge, this design notes the rationale (D2 below).
2. Ship the four REST endpoints (`POST /api/v1/conversations`, `GET /api/v1/conversations`, `GET /api/v1/chat/{id}/messages`, `POST /api/v1/chat/{id}/messages`) with correct authorization (active-participant), block enforcement, content-length guard, redaction-aware reads, and slot-race correctness.
3. Activate the V2-drafted `realtime.messages` RLS policy automatically (no new SQL — V2's `IF EXISTS conversation_participants` gate flips on once V15 creates the table).
4. Run the mandatory RLS test set (per `CLAUDE.md` invariant) with real schema for the first time: JWT-sub-not-in-users deny, non-participant deny, `left_at`-participant deny, malformed-topic deny, SQL-injection-topic deny.
5. Cover the slot race deterministically: 10 concurrent `POST /api/v1/conversations` for the same canonical pair → exactly one conversation row, exactly two participant rows.

**Non-Goals:**

1. **Supabase Realtime broadcast publish from Ktor.** Deferred to `chat-realtime-broadcast` follow-up. Without broadcast, the user-visible product is "1:1 chat with REST polling" — usable for testing the data plane, not yet a shippable feature.
2. **Embedded post send/render.** The schema columns (`embedded_post_id`, `embedded_post_snapshot`, `embedded_post_edit_id`) ship now per canonical, but the send-path embed and the render-path snapshot logic are deferred to `chat-embedded-posts`. The `embedded_post_id` field in the send body is silently ignored.
3. **Admin chat-message redaction endpoint.** Phase 3.5 admin-panel territory. Schema columns + atomicity CHECK ship now so the endpoint, when added, only needs the route + service code.
4. **Daily send-rate cap (Free 50/day, Premium unlimited).** Deferred to `chat-rate-limit` follow-up using the shared `rate-limit-infrastructure` capability shipped in `like-rate-limit`. Same shape as the like-rate-limit + reply-rate-limit changes.
5. **`chat_message` notification emit-site.** Without realtime broadcast there's nothing to emit; the existing in-app notification flow stays orthogonal. Deferred to the realtime change.
6. **Perspective API screening.** Cross-cutting (posts + replies + chat) — separate change.
7. **Read-receipts / typing indicators / message reactions / unread counters.** Post-MVP per [`docs/02-Product.md`](../../../docs/02-Product.md). The `last_read_at` column ships unused so the future change has the storage in place.
8. **Per-conversation FCM push batching.** Phase 2 #11. Depends on realtime layer.

## Decisions

### D1. Two advisory locks at different points in the lifecycle

**Decision**: take a Postgres advisory lock at TWO distinct points, with two distinct keys, instead of one.

- **Lock A (user-pair scope, create-or-return time)**: `pg_advisory_xact_lock(hashtext(LEAST(:caller_id, :recipient_id)::text || ':' || GREATEST(:caller_id, :recipient_id)::text))`. Held for the duration of the create-or-return transaction. Serializes concurrent `POST /api/v1/conversations` calls for the same canonical user pair so that two concurrent first-creations don't produce two conversation rows.
- **Lock B (conversation-id scope, participant-insert time)**: `pg_advisory_xact_lock(hashtext(:conversation_id::text))` per canonical [`docs/05-Implementation.md:1252`](../../../docs/05-Implementation.md). Held for the participant-insert sub-transaction. Serializes slot assignment within a single conversation. Required for the canonical insert flow; semantics unchanged.

In the create-or-return path, both locks are taken in sequence: Lock A serializes the existence check + conversation INSERT; Lock B is taken inside that same transaction before the two participant INSERTs. Since Lock A is already held, Lock B is uncontended in this path — but it MUST still be taken to satisfy the canonical participant-insert contract (which any future change adding a participant to an existing conversation will rely on).

Reading `last_message_at`-based listing or message inserts/reads do NOT take any advisory lock; they rely on the partial unique index for safety.

**Why**: a single conversation-id-scoped lock is insufficient for create-or-return because the conversation id doesn't exist yet. A user-pair-scoped lock is the natural extension, and the canonical-ordered pair (`LEAST` then `GREATEST`) avoids the asymmetric A→B / B→A race.

**Alternative considered**: rely on a UNIQUE index over `(LEAST(participant_a, participant_b), GREATEST(participant_a, participant_b))` to make duplicate creation a UNIQUE-violation that the application catches and re-fetches. Rejected because (a) the schema doesn't have such columns at the conversation level (slots live on `conversation_participants`), and (b) the canonical pattern is advisory-lock-based — diverging here would be schema improvisation.

**Alternative considered**: a single user-pair advisory lock for create-or-return AND a separate conversation-id lock only when adding a participant to an EXISTING conversation. Equivalent to the chosen design but obscures the canonical `pg_advisory_xact_lock(hashtext(:conversation_id::text))` requirement at participant-insert time. Chose the explicit two-lock design for clarity.

### D2. `created_by` and `sender_id` use ON DELETE RESTRICT — hard-delete is a Phase 3.5 cleanup-worker concern

**Decision**: follow canonical [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) verbatim — `conversations.created_by` and `chat_messages.sender_id` use `ON DELETE RESTRICT`, NOT `SET NULL`.

**Why**: the canonical schema is intentionally restrictive on user references in chat. A hard-delete of a user with conversation history must NOT cascade-erase the messages (the other participant still wants their conversation). Setting `RESTRICT` enforces this at the DB level: any direct `DELETE FROM users WHERE id = X` for a user with chat history fails until the cleanup worker has explicitly handled that user's chat-message rows. The canonical Phase 3.5 cleanup-worker design (deferred — the `deletion_requests` + tombstone pattern) will null-out the user's PII columns and either tombstone (sender_id stays, content rendered as "Akun Dihapus") or null-cascade-then-delete; the choice is the cleanup-worker's, not this change's.

**Why NOT `SET NULL`**: SET NULL would force `sender_id` to be `NULL`-able, which then loses the integrity property that every message has an attributable sender at write time. With RESTRICT + Phase 3.5 cleanup, the column stays `NOT NULL` and the worker handles the deletion sequence explicitly.

**Risk**: until Phase 3.5 ships, hard-delete of a user with chat history fails at the DB. Mitigation: the project doesn't currently hard-delete users (no cleanup worker exists yet — same Phase 3.5 deferral as `admin_users`). When the worker lands, its design must explicitly handle chat. Documented as a project-level prerequisite, not a chat-foundation gap.

**Tradeoff**: a `chat-users-cleanup-worker` follow-up is a hard prerequisite for Phase 3.5 user deletion. This is canonical (deferred per `docs/`), not new debt introduced by this change.

### D3. `last_message_at` is updated by the application, not a DB trigger

**Decision**: in the `POST /api/v1/chat/{id}/messages` handler, the same DB transaction that INSERTs the chat_messages row also `UPDATE conversations SET last_message_at = NOW() WHERE id = :conversation_id`. No DB trigger.

**Why**: a trigger would couple `chat_messages` writes to `conversations` writes implicitly, which is harder to reason about and harder to test in isolation. The two updates fit naturally in one transaction at the application layer, are visible in the SQL log, and work against both Supabase and plain Postgres without needing the trigger to be created in both environments. Matches the canonical insert flow pattern.

**Alternative considered**: a `chat_messages_after_insert` trigger that bumps `conversations.last_message_at`. Rejected because it adds a moving part for no benefit — the application is the only writer to `chat_messages`; there's no concern that an out-of-band INSERT would skip the trigger.

**Alternative considered**: re-derive `last_message_at` at read time via `MAX(created_at)` join from `chat_messages`. Rejected because the conversation list query is hot (every app open), and an O(n) MAX-per-conversation scan in a `JOIN LATERAL` would dominate; a stored column with single-statement update is cheaper and simpler.

### D4. Cursor pagination uses (created_at DESC, id DESC) — not raw timestamp

**Decision**: cursor token is a base64-encoded `{created_at: ISO-8601, id: UUID}` pair. Default page size 50, hard cap 100. Forward-only (no backward seek for MVP).

**Why**: timestamp-only cursors are unsafe under DB-clock-tie scenarios (two messages with identical `created_at` would lose the tail of the equal-timestamp set across page boundaries). The composite `(created_at DESC, id DESC)` cursor is uniquely orderable and matches the existing `chat_messages_conv_idx (conversation_id, created_at DESC)` index — the leftmost column gets the index seek, then the index range scan walks DESC.

**Alternative considered**: opaque integer offset. Rejected because conversation message lists can grow unbounded; offset pagination becomes O(n) worse the deeper a user scrolls.

**Alternative considered**: only `created_at` cursor, accepting the rare DB-clock-tie loss. Rejected — `chat_messages` will see bursty INSERT patterns (Realtime arrival batches) where `NOW()` clock granularity on Postgres is microsecond-level but JVM `Instant.now()` upstream may round; ties happen.

### D5. Block enforcement: 403 on send AND on create-conversation

**Decision**: bidirectional block check at BOTH `POST /api/v1/conversations` (create-or-return) AND `POST /api/v1/chat/{id}/messages` (send). Both return 403 with user-facing `"Tidak dapat mengirim pesan ke user ini"`.

**Why**: [`docs/02-Product.md:234`](../../../docs/02-Product.md) says "Blocked user cannot initiate a DM to the blocker. Existing conversations remain visible in history, but no new messages can be sent." The "cannot initiate" rule applies at create time (create-or-return); the "no new messages" rule applies at send time. Both must enforce, otherwise either (a) the blocker can be DM'd by a blocked user via a pre-existing conversation that wasn't blocked at the time of creation (which the send check catches), or (b) the blocked user can initiate a fresh DM by going through the create-or-return endpoint (which the create check catches).

The status code is 403 for both — `409 Conflict` would be wrong because the underlying state is not a conflict but an authorization denial. Matches the existing block-enforcement pattern in `nearby-timeline-with-blocks` + `following-timeline-with-follow-cascade`.

The `GET` endpoints (list conversations, list messages) do NOT block-filter — existing conversation history is readable per canonical. The list-conversations endpoint's other-participant profile field DOES go through `visible_users` so a shadow-banned partner masks out, but the row itself surfaces.

### D6. Redacted message rendering: NULL the content field at the API boundary, surface `redacted_at`

**Decision**: when serializing a `chat_messages` row to the API response, if `redacted_at IS NOT NULL`, the response carries `content: null` + `redacted_at: <timestamp>` + `redaction_reason` ALWAYS omitted (admin-only). The client renders the user-facing string `"Pesan ini telah dihapus oleh moderator."` based on `redacted_at` being non-null. The DB row still holds the original content for audit; the API never returns it to participants.

**Why**: per [`docs/05-Implementation.md:1299`](../../../docs/05-Implementation.md) — "the original `content` field stays in the DB for audit; it is not returned to the conversation feed endpoint once redacted." The redaction is the moderator's action; participants see the redaction state, not the original content. `redaction_reason` is an admin-only field (the admin panel needs it; the user does not).

**Edge case**: a message with both `content` and `embedded_post_id` (or `embedded_post_snapshot`) populated, then redacted — the embed reference is also masked at the API boundary. Redaction zeroes out ALL renderable content. The atomicity CHECK enforces this at the DB layer (admin must set `redacted_at` + `redacted_by` together; redaction implies all renderable surface is hidden).

### D7. `last_read_at` ships now but is unused

**Decision**: include the `last_read_at TIMESTAMPTZ NULL` column on `conversation_participants` per canonical. Do NOT write or read it from any endpoint in this change. Add a clear comment in the migration: `-- last_read_at: read-receipt / unread-count storage; written by future chat-read-receipts change`.

**Why**: a future change for read receipts / unread counters needs the storage in place; adding a column to a populated table later is a breaking-style migration. Better to ship the canonical column unused than to retrofit later. The CLAUDE.md "no premature abstraction" rule applies to code, not schema — schema decisions cost more to revisit than code decisions.

**Alternative considered**: omit the column; add it in the read-receipts change. Rejected because the canonical schema IS the column.

### D9. Shadow-ban model: invisible-actor on REST data plane; V2 RLS amendment deferred to chat-realtime-broadcast

**Decision**: shadow-banned users SHALL be able to (a) call `POST /api/v1/conversations` to create-or-return, (b) be returned by `GET /api/v1/conversations` to themselves, (c) call `POST /api/v1/chat/{id}/messages` to send, and (d) call `GET /api/v1/chat/{id}/messages` to list their own messages. Other (non-banned) users SHALL NOT see a shadow-banned sender's messages in `GET /messages` results — the query inlines the filter `(sender_id = :viewer OR NOT EXISTS shadow-ban-on-sender)`. Other users SHALL still see the conversation row in `GET /conversations` (so the partner can keep their conversation list stable across the partner's punishment cycle), but the partner profile is masked via LEFT JOIN visible_users + COALESCE-to-placeholder.

**Why (UX)**: the canonical shadow-ban contract per [`docs/08-Roadmap-Risk.md` Acceptable Risks](../../../docs/08-Roadmap-Risk.md) is "sophisticated adversary will detect in 24-48 hours; friction + time buy, not invisible shield." Hard-deny on chat (the alternative — shadow-banned user gets immediate 403/404 errors) breaks this contract: the punished user instantly knows they've been punished, defeating the moderation friction layer. The invisible-actor model — punished user goes about their day, posts/sends/likes, but their content is invisible to others — is the canonical pattern this project applies to `posts` (via `visible_posts`) and `users` (via `visible_users`).

**Why (engineering)**: the existing `visible_posts` / `visible_users` pattern is filter-only on OTHER readers' queries; the punished user themselves reads their own content via the own-content carve-out. Chat is symmetric — the list-messages query returns `(sender_id = :viewer OR NOT EXISTS shadow-ban-on-sender)`, which is the exact own-content carve-out applied per-row. The recipient existence check at create-or-return time uses RAW `users` (not `visible_users`) so a 404-vs-201 differential cannot leak shadow-ban state to a probing third party.

**Scope split with V2 RLS**: V2 has already-shipped a clause in the `realtime.messages` policy ([V2__auth_foundation.sql:81-84](../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql)) `AND NOT EXISTS (SELECT 1 FROM public.users WHERE id = cp.user_id AND is_shadow_banned = TRUE)` that DENIES the shadow-banned user's own realtime subscription — this contradicts the invisible-actor model. The amendment of V2 (remove the `is_shadow_banned` clause OR move it to the publish-side) is OUT OF SCOPE for this change because (a) the chat-realtime-broadcast change owns the realtime publish layer end-to-end, and (b) the REST data plane is the only user-visible chat surface this change ships, so the V2 inconsistency is dormant for ~1 change cycle. A high-priority FOLLOW_UPS entry tracks the V2 amendment as a hard prerequisite for chat-realtime-broadcast.

**Alternative considered (hard-deny)**: every shadow-banned user gets immediate 403/404 on every chat endpoint. Rejected because it breaks the canonical shadow-ban invisibility property within seconds (the punished user goes from "chat works" to "chat broken" the instant the ban applies — they detect the ban immediately, not in 24-48 hours).

**Alternative considered (defer the entire shadow-ban story to chat-realtime-broadcast)**: ship this change with the V2 RLS as-is (effectively hard-deny on the realtime path that doesn't exist yet) and defer the invisible-actor REST behavior to a third change. Rejected because the REST data plane is the user-visible surface for THIS change — the shadow-ban model has to be coherent on the REST path NOW or the change ships with a privacy hole (recipient-existence-via-`visible_users` leaks shadow-ban state via 201/404 differential).

### D10. List-messages and send-message use a per-call-site annotation to suppress `BlockExclusionJoinRule`

**Decision**: introduce per-call-site annotation `// @allow-no-block-exclusion: chat-history-readable-after-block` on the list-messages and send-message participant-lookup query sites. Update `BlockExclusionJoinRule` in `:lint:detekt-rules` to recognize the annotation (matching the existing convention from `// @allow-username-write` and `// @allow-privacy-write` per `CLAUDE.md` § Critical invariants).

**Why**: per [`docs/02-Product.md:234`](../../../docs/02-Product.md), the canonical block-aware-chat contract is asymmetric — send is blocked (403) but list-history is readable (200) for both parties even after a block. The auto-applied bidirectional NOT-IN `user_blocks` join would over-filter the list-messages path, dropping the entire conversation history once a block lands. The send-message handler enforces the 403 contract via an EXPLICIT bidirectional `user_blocks` lookup (canonical query at [`docs/05-Implementation.md:1304-1308`](../../../docs/05-Implementation.md)), so suppressing the auto-applied NOT-IN there is safe — the explicit query is what the spec scenario actually tests against.

**Alternative considered**: silently use `// @allow-...` annotations without lint-rule update. Rejected because the lint rule has to recognize the annotation symbol or it'll fire regardless. The `BlockExclusionJoinRule` is in `:lint:detekt-rules`; updating it to recognize a new annotation tag is a small, well-bounded change.

**Alternative considered**: refactor the list-messages query to NOT touch `chat_messages` directly (e.g., create a `visible_chat_messages` view that excludes shadow-banned-sender messages but does NOT apply block exclusion). Rejected because (a) the shadow-ban filter is per-row with a viewer-aware carve-out (sender = viewer) that views can't express, and (b) introducing a new view for this single-query case is more code than the annotation.

**Alternative considered**: amend the spec to say that list-messages DOES filter by block (canonical product spec is wrong). Rejected — `docs/02-Product.md:234` is canonical and says "Existing conversations remain visible in history, but no new messages can be sent." The lint annotation is the right way to handle the asymmetric contract.

### D8. Idempotent create-or-return returns 200, not 201 on duplicate

**Decision**: `POST /api/v1/conversations` returns:
- `201 Created` when a new conversation is created.
- `200 OK` when an existing conversation is returned (idempotent).
- `403 Forbidden` on bidirectional block.
- `400 Bad Request` on self-DM (recipient == caller).

The body shape is identical for 201 and 200 — the conversation row + both participants. Clients distinguish via status code if they care to; most clients just consume the body.

**Why**: distinguishing creation-vs-return is useful for analytics (chat_initiated event) but not for behavior. The 201/200 split is REST-canonical and matches the user's intent (the caller wants a conversation; whether it was just created or existed already doesn't affect what they do next).

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Slot-race regression: a future code change forgets to take the conversation-id advisory lock at participant-insert time, regressing to a CHECK-violation retry loop | Spec scenario asserts the canonical lock is held via `pg_xact_lock_test`-style probe in tests; design.md D1 documents the requirement explicitly; canonical pattern in `docs/05-Implementation.md:1252` is the source of truth. |
| User-pair advisory lock collision under high load — `hashtext(...)` may collide for distinct pairs | `hashtext` returns int4 (32-bit signed). At ~10M concurrent pair-locks the birthday-collision probability is ~10^-3, not the lower order of magnitude originally estimated. The CONSEQUENCE of a collision is benign: two ostensibly-distinct user-pairs serialize on the same lock for the duration of one transaction (single-digit milliseconds). No correctness loss — the partial unique index on `(conversation_id, slot)` and the canonical-ordered pair lookup both still apply, so the worst case is a wasted lock-wait. Documented as accepted in design.md D1. |
| RLS policy "JWT sub not in `users` → deny" mandatory test fails because V2's regex-guarded `split_part` doesn't handle a malformed topic edge case | The test set is exactly the canonical set in `docs/05-Implementation.md:108-110` — malformed topic / invalid UUID format / SQL-injection topic / non-participant / `left_at` participant / sub-not-in-users. Run the tests against real schema for the first time in this change. Surface failures as blocking; do not ship partial coverage. |
| `redacted_by` admin-FK shipping without the constraint creates a future-Phase-3.5 trap if the FK isn't added when `admin_users` lands | The `-- @allow-admin-fk-deferred: phase-3.5` annotation matches the existing V9 pattern; the lint rule for admin-FK deferral (existing) catches drift. The Phase 3.5 admin-panel change owns the FK addition. |
| Hard-delete of a user with chat history fails at the DB level due to RESTRICT, blocking ops if a user requests deletion before Phase 3.5 cleanup-worker ships | The project does not currently support user hard-delete (Phase 3.5 territory). If a user requests deletion in the interim, they get a "deletion request received, processing in next batch" response and the request lands in `deletion_requests` (already shipped) waiting for the cleanup worker. The cleanup worker, when built, MUST handle chat-message null-out before deleting the user. Documented as a Phase 3.5 prerequisite, not a chat-foundation gap. |
| List-conversations query is O(n) over the caller's active participations | The `conversation_participants_user_active_idx (user_id) WHERE left_at IS NULL` index makes the lookup O(log n); the partner-profile JOIN to `visible_users` adds a second index seek per row; the `last_message_at DESC` order matches the natural insertion order of recent activity. Page size 50 hard-caps the per-request cost. Hourly Redis cache of the conversation list is a Month 6+ optimization (out of scope). |
| Cursor pagination clients pin a `created_at` cursor and miss arrivals between page fetches | Standard cursor-pagination tradeoff. The client's reconciliation strategy (per [`docs/05-Implementation.md` § Failure handling](../../../docs/05-Implementation.md)) is to fetch via WSS realtime for live updates and use REST pagination only for backfill on cold start / scroll-back. Acceptable for MVP. |
| `embedded_post_id` silently ignored in the send body could surprise an early adopter | The handler logs a single WARN per accepted send that has the field populated, with the message-id in the structured log; the response body intentionally does not echo the field. This change explicitly does not document the field as part of the send contract — adding it later is the embed change's contract. |

## Migration Plan

1. **V15 migration** lands the three tables, all indexes, all CHECKs, and the `redacted_by` column without FK constraint (annotated `-- @allow-admin-fk-deferred: phase-3.5`). The V2-drafted RLS policy on `realtime.messages` activates as a side-effect (its `IF EXISTS conversation_participants` gate flips on).
2. **Application code** lands the four route handlers, the conversation + chat-message repositories (in `:backend:ktor` per the existing module shape), and the content-length middleware registration for the chat path.
3. **Test set** runs against staging-style integration test fixtures: slot-race concurrency, RLS five-deny set, block-enforcement on send + create, redaction-aware reads, cursor pagination, empty-message CHECK, redaction atomicity CHECK.
4. **Staging deploy + smoke** before archive (per the staging-deploy-before-archive convention codified in PR [#50](https://github.com/aditrioka/nearyou-id/pull/50)): run a one-shot psql verification that the V15 schema applies cleanly, then a curl-based round-trip on `POST /api/v1/conversations` + `POST /api/v1/chat/{id}/messages` + `GET /api/v1/chat/{id}/messages` against staging.

**Rollback strategy**: Flyway forward-only migrations. If V15 needs to be reverted in production, ship a V16 that DROPs the three tables (cascading the FK from `chat_messages.embedded_post_id` etc.) AND DROPs the V2 RLS policy on `realtime.messages` (re-creating the gate's "before V15" behavior). The route handlers are removed by the rollback PR. Chat data is lost on rollback by design — no chat data should exist in production at this point (this change ships with no consumers yet other than test traffic).

## Open Questions

None at proposal time. The canonical schema in `docs/05-Implementation.md` is detailed and unambiguous; the only design calls were D1 (advisory-lock layering), D3 (trigger vs application update), D4 (cursor shape), D6 (redaction render policy), D7 (`last_read_at` shipped unused), and D8 (status code for idempotent create-or-return). All are resolved above.
