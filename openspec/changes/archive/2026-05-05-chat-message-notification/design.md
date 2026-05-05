## Context

The chat MVP backend has shipped in three slices:
- **`chat-foundation`** (PR [#61](https://github.com/aditrioka/nearyou-id/pull/61)) — schema + REST data plane (`POST /api/v1/chat/{id}/messages`, `GET /messages`, `GET /conversations`, `POST /conversations`).
- **`chat-rate-limit`** (PR [#62](https://github.com/aditrioka/nearyou-id/pull/62)) — 50/day Free cap on send.
- **`chat-realtime-broadcast`** (PR [#63](https://github.com/aditrioka/nearyou-id/pull/63)) — Supabase Broadcast publish leg + `ChatRealtimeClient` interface.

The recipient receives a real-time message **only when their app is open and connected to Supabase Realtime.** When they're offline, on a phone with the app backgrounded, or on a different device entirely, broadcast fires into the void. The recipient finds out about new messages on their next manual `GET /messages` poll — there is no FCM push, no `notifications` panel entry, no badge increment.

The `notifications` table has carried `type = 'chat_message'` in its CHECK enum since V10 — and the [`docs/05-Implementation.md:860`](../../../docs/05-Implementation.md) catalog formally specifies its body_data shape `{conversation_id, preview}`. But [`in-app-notifications/spec.md:11`](../../specs/in-app-notifications/spec.md) flags this directly: "all 13 values per the canonical catalog even though V10 only writes 4." The unwired enum value is exactly the gap this change closes.

The `NotificationEmitter` (write path) and the composite `NotificationDispatcher` (in-app + FCM fan-out) are already shipped — the chat handler is the missing call site.

## Goals / Non-Goals

**Goals:**

- Wire `NotificationEmitter.emit(...)` into the chat send handler at `POST /api/v1/chat/{conversation_id}/messages` so a `chat_message` notification fires for the recipient on every successful send.
- Reuse the existing composite `NotificationDispatcher` so the FCM push leg activates "for free" — no new dispatcher code, no new Firebase wiring.
- Mirror the sender-shadow-ban semantics from `chat-realtime-broadcast` — same `viewer.isShadowBanned` source, same skip behavior, same no-extra-SELECT contract.
- Match the body_data catalog at [`docs/05-Implementation.md:860`](../../../docs/05-Implementation.md) verbatim — `{conversation_id, preview}`, where `preview` is the first 80 code points of `content` or `null` for embedded-only messages.
- Land emit INSIDE the existing chat-foundation transaction (NotificationEmitter contract: block-check + INSERT in the caller's tx).

**Non-Goals:**

- **Notification deduplication / debouncing across rapid consecutive messages.** Sending five messages in quick succession produces five `notifications` rows + five FCM pushes. The UX risk (recipient's panel flooded by a chatty sender) is real, but coalescing belongs in a future change with explicit product + UX input. Mobile UI is responsible for any panel-side grouping; backend ships the per-message rows.
- **Custom mute / per-conversation notification preferences.** Post-MVP — no schema for `notification_preferences` exists yet, and adding one for chat alone would over-fit.
- **Coalesced "X new messages" FCM payloads.** Each FCM push corresponds to exactly one notification row, mirroring the per-message granularity.
- **Read-receipt / typing-indicator FCM payloads.** Out of scope — this change is strictly about new-message notifications.
- **Retroactive backfill** of notifications for messages sent before this change ships. The pre-change messages are already visible via `GET /messages`; no recipient-action-required surface needs them in the panel.
- **Receiver-side shadow-ban skip.** The receiver's `is_shadow_banned` state does NOT skip the emit (parallel to `chat-realtime-broadcast`'s receiver-side non-skip per [`auth-realtime/spec.md:37`](../../specs/auth-realtime/spec.md) — shadow-ban affects others' visibility of the receiver, not the receiver's own notifications panel).
- **Banned-sender skip logic.** Senders with `is_banned = TRUE` are 403'd by chat-foundation's auth path before the chat send handler runs; the emit is reached only by senders who passed auth. No additional ban-skip logic needed.
- **Schema changes.** Zero migrations. The enum already accepts `chat_message`; the body_data catalog already specifies its shape.

## Decisions

### D1: Emit runs inside the existing chat-foundation transaction (not post-commit)

**Decision.** Call `NotificationEmitter.emit(...)` AFTER the `INSERT chat_messages` and BEFORE the transaction commit. The emit's block-check + `INSERT notifications` participate in the same tx as the chat-foundation `INSERT chat_messages` + `UPDATE conversations.last_message_at`. If the emit fails (e.g., recipient hard-deleted between auth and emit, FK violation on `notifications.user_id`), the entire transaction rolls back — chat send returns 5xx and no row persists.

**Why.** Three reasons:

1. **`NotificationEmitter` contract.** [`in-app-notifications/spec.md:64`](../../specs/in-app-notifications/spec.md) states: *"The block-check and INSERT MUST execute in the same DB transaction as the caller's primary write."* The other four wired emit sites (Like / Reply / Follow / Report) all run emit inside the caller's tx. A post-commit emit for chat would diverge from the established contract for no concrete reason.
2. **Strict ordering with `target_id`.** The notification's `target_id = chat_messages.id` is a logical (NOT FK-enforced) reference to a row in `chat_messages` — per the canonical schema at [`docs/05-Implementation.md:822-839`](../../../docs/05-Implementation.md), `target_id` is `UUID` with no `REFERENCES` constraint (only `user_id` and `actor_user_id` carry FK enforcement). If we emit before INSERT, `target_id` references a row that doesn't yet exist; the un-enforced FK means PostgreSQL accepts the dangling reference silently, which is wrong-but-quiet. If we emit after INSERT but post-commit, we add a window where a crash leaves a chat row with no notification — same silent-bug shape. Running emit inside the tx after the INSERT pins `target_id` to a real row that's about to commit (or roll back) atomically.
3. **Composite vs. broadcast semantics.** `chat-realtime-broadcast` is best-effort POST-commit because broadcast loss is recoverable via REST resync (the persisted row is the source of truth). Notifications are NOT recoverable that way — the recipient never re-fetches `notifications` to check for missed entries; the panel is "what's there is what was emitted." So the durability bar for notifications is higher, and the in-tx contract enforces durability.

**Alternatives considered.**

- **Post-commit emit (parallel to chat-realtime-broadcast).** Rejected — diverges from `NotificationEmitter` contract and creates a crash window where notifications are silently dropped.
- **Outbox table + worker-driven emit.** Rejected — overkill for the MVP; adds schema, adds a worker, adds latency. The other four emit sites work fine in-tx; chat is no different.

### D2: Recipient resolution reuses chat-foundation's already-loaded participant set (no extra SELECT)

**Decision.** The chat send handler's auth + active-participant gate already loads the conversation's participant set (per chat-foundation's bidirectional block check at [`docs/05-Implementation.md:1304-1308`](../../../docs/05-Implementation.md)). Recipient resolution = `participants \ {sender}` — a 1-element set in 1:1 chat. The handler passes that recipient_id directly into `NotificationEmitter.emit(...)`. No new `SELECT participants` is issued.

**Why.** The participant set is already in scope at the emit call site (the handler used it for the active-participant check + the bidirectional block query). Re-fetching would duplicate work. This mirrors the spec invariant in `chat-realtime-broadcast` § "Publish-side shadow-ban skip" which forbids an extra `SELECT is_shadow_banned` for the same reason — the auth-time / handler-already-loaded value is authoritative.

**Alternatives considered.**

- **Re-query `conversation_participants` inside `NotificationEmitter`.** Rejected — duplicates a query the handler already ran. Also moves chat-specific logic into a generic emitter, weakening the abstraction.
- **Pass the entire conversation row to the emitter.** Rejected — over-fits the emitter to chat. Recipient_id is the right granularity.

### D3: Sender-shadow-ban skip mirrors chat-realtime-broadcast (no extra SELECT)

**Decision.** When `viewer.isShadowBanned = TRUE` on the request principal, the chat send handler SHALL NOT call `NotificationEmitter.emit(...)`. The `chat_messages` row still persists per chat-foundation's invisible-actor contract. The principal's auth-time `viewer.isShadowBanned` is the authoritative read — no extra `SELECT is_shadow_banned FROM users` is issued for the emit decision.

**Why.** Three points of consistency:

1. **Behavioral parity with broadcast skip.** `chat-realtime-broadcast`'s § "Publish-side shadow-ban skip" already establishes: sender-shadow-banned → `chat_messages` row persists, no broadcast fires. Adding "no notification fires" to that list is the natural completion. The recipient should be invisible to the recipient regardless of channel.
2. **Single source of truth for shadow-ban state.** `viewer.isShadowBanned` was added to `UserPrincipal` by `chat-realtime-broadcast` precisely so handlers don't issue per-call shadow-ban SELECTs. Reusing it here keeps the contract clean.
3. **Acceptable race window.** D2 in `chat-realtime-broadcast/design.md` documents the mid-flight admin-flip race (`is_shadow_banned` flipped after auth but before publish) as accepted: the in-flight message slips through, but every subsequent send from a fresh request sees the new state. This change inherits the same race window for emit; the consumer-side defense (mobile filter on shadow-banned senders' notifications panel entries) is the same as the broadcast defense.

**Alternatives considered.**

- **Issue a fresh `SELECT is_shadow_banned`.** Rejected — diverges from broadcast skip semantics and adds a per-send SQL round trip for a state that's already in-scope on the principal.
- **Skip at the emitter level (move shadow-ban logic into NotificationEmitter).** Rejected — the emitter is generic; shadow-ban skip is a chat-handler-level decision. Moving it into the emitter would couple the emitter to chat semantics.

**Asymmetry vs. like / reply / follow — deliberate, called out explicitly.** The other four wired emit sites (LikeService / ReplyService / FollowService / ReportService) do NOT skip the emit when the actor is shadow-banned; per [`fcm-push-dispatch/spec.md:431`](../../specs/fcm-push-dispatch/spec.md), the existing pattern is "emit fires; FCM push body masks the actor's username via `visible_users` lookup so the push reads `Seseorang menyukai post-mu`." Chat is different here for three reasons:

1. **Broadcast precedent.** `chat-realtime-broadcast` already establishes the publish-skip on shadow-banned senders. The notification skip is the natural complement — both real-time channels (broadcast + FCM push) get suppressed together for chat. Like/reply/follow have no real-time broadcast equivalent (no broadcast = nothing to skip), so the "mask username only" pattern works there but doesn't compose with chat's existing broadcast contract.
2. **Privacy surface.** Chat is a 1:1 direct surface — the recipient cannot reach the sender by any other means (no mutual public post visibility). A shadow-banned chat sender's message has zero non-creepy delivery path. By contrast, a shadow-banned commenter on a public post is at least visible via the post's reporter / author paths; masking the username preserves the recipient's awareness that "someone" engaged.
3. **UX coherence.** Even if the FCM push body could be masked to "Seseorang mengirim pesan", the recipient cannot reply to or open the conversation in any meaningful way (the sender is invisible by design). Suppressing the push entirely is the honest signal: there's no reachable counterparty.

This asymmetry is documented here so reviewers don't flag it as inconsistency. Future capability authors with a similar real-time + private-1:1 surface (e.g., a future direct-mention or DM-style feature) SHOULD consider following the chat pattern; capability authors with a public-engagement surface (likes, replies, follows) SHOULD continue to follow the like/reply/follow mask-only pattern.

### D4: `preview` is the first 80 code points of `content`, `null` when content is null

**Decision.** `body_data.preview` SHALL be the first 80 code points (NOT bytes, NOT chars — code points, matching the existing `post_excerpt` truncation policy at [`in-app-notifications/spec.md:257`](../../specs/in-app-notifications/spec.md)) of `chat_messages.content`. When `chat_messages.content IS NULL` (an embedded-only message permitted by the schema CHECK at [`docs/05-Implementation.md:1285`](../../../docs/05-Implementation.md)), `preview` SHALL be JSON `null` (NOT empty string, NOT a placeholder like `"[embedded post]"`).

**Why.**

- **Code-point truncation matches existing precedent.** `post_liked` excerpt truncation uses code points to avoid splitting a multi-byte UTF-8 sequence (Indonesian uses ASCII mostly, but emoji and accented characters can land at the 80-byte boundary). Code-point truncation is the existing convention.
- **`null` preview for embedded-only messages.** Embedded-only messages exist (the schema permits them for future `chat-embedded-posts`). A placeholder string like `"[embedded post]"` would be a UX promise this change doesn't deliver — the actual rendering of embedded-only chat-message previews is owned by the future `chat-embedded-posts` change. Until then, `null` is the honest signal: "no text to preview." Mobile UI renders `null` as the localized "Sent a post" string (mobile-side concern, owned by Moko Resources per `CLAUDE.md` § Critical invariants).
- **Truncated preview frozen at emit time.** Mirrors the `post_excerpt` precedent: subsequent edits to `chat_messages.content` (e.g., admin redaction) do NOT update the already-written `body_data.preview`. The notifications row is a snapshot.

**Alternatives considered.**

- **Untruncated preview.** Rejected — chat messages can be up to 2000 chars; un-truncated previews would bloat `notifications.body_data` and the FCM push payload.
- **Placeholder string for embedded-only previews.** Rejected — UX strings belong in mobile via Moko Resources; backend should never inline localized strings.
- **Include `embedded_post_id` in body_data when present.** Rejected — `chat-embedded-posts` is the owning capability for embed payloads; this change ships forward-compatible silence (just `{conversation_id, preview}`).

### D5: Tx ordering — INSERT chat_messages → emit → UPDATE last_message_at → COMMIT → publish

**Decision.** The chat send handler's tx order is:

1. `INSERT INTO chat_messages (...) RETURNING id` (chat-foundation)
2. `NotificationEmitter.emit(recipient_id, type='chat_message', actor=sender, target_type='message', target_id=<inserted id>, body_data=...)` (this change — emit inside the tx)
3. `UPDATE conversations SET last_message_at = NOW() WHERE id = ?` (chat-foundation)
4. **COMMIT**
5. `ChatRealtimeClient.publish(conversationId, message)` if not shadow-banned (chat-realtime-broadcast — POST-commit)

**Why.** Step 2 must come AFTER step 1 because `target_id` references the inserted row. Step 3 (last_message_at UPDATE) can be before OR after step 2 — they're independent — but keeping it last in the tx aligns with chat-foundation's existing flow ("INSERT chat_messages AND UPDATE conversations.last_message_at" reads naturally with INSERT first). Putting emit between the two preserves both the existing chat-foundation atomicity contract AND the FK-realness of `target_id`.

The publish step (5) is unchanged by this change — still post-commit, still best-effort, still WARN-on-failure.

**Alternatives considered.**

- **Emit AFTER UPDATE last_message_at.** Acceptable but no clear benefit. Picked the order above for "emit happens as soon as target_id is real."
- **Emit AFTER COMMIT.** Rejected per D1 (durability bar for notifications is higher than for broadcast).

### D6: Block-suppression delegated to NotificationEmitter (no chat-side block check)

**Decision.** The chat send handler does NOT add a separate block check before calling `NotificationEmitter.emit(...)`. The emitter's existing bidirectional `user_blocks` query handles it.

**Why.** [`in-app-notifications/spec.md:71-76`](../../specs/in-app-notifications/spec.md) already specifies block-suppression for emit. Duplicating the check at the call site would (a) issue a redundant SQL query, (b) drift from the canonical block-check shape if it ever changes, (c) couple the chat handler to block semantics that belong in the generic emitter.

**Note on chat-foundation's existing block check.** The chat send handler ALREADY runs a block check at the outer level — the bidirectional `user_blocks` query that returns 403 + `{"error": "Tidak dapat mengirim pesan ke user ini"}`. If that check passes (no block in either direction), the request proceeds and the emit's block check is a structural no-op (the same query, same bindings, returns no rows). This is two SELECTs against `user_blocks` for the same pair, but they happen at different points in the request flow with different semantic purposes (403 contract vs. notification suppression) and the index lookup is sub-millisecond. Optimizing them down to one would prematurely couple the handler to the emitter's internals; defer that to a future cross-cutting "block-check single-source" change if perf data ever justifies it.

**Alternatives considered.**

- **Skip the emit's block check because chat-foundation already proved no-block.** Rejected — the emitter's contract is "block-check + INSERT in the caller's tx." Bypassing it here would weaken the abstraction; future emit call sites would have to re-check whether their handler also pre-checked blocks.
- **Move the chat-foundation block check INTO the emitter.** Rejected — chat's block check has a 403 contract with a specific error string ("Tidak dapat mengirim pesan ke user ini") that's chat-specific. The emitter can't carry chat-localized strings.

### D7: Self-action suppression is a structural no-op (1:1 chat: sender ≠ recipient)

**Decision.** No special handling for self-action. The `NotificationEmitter`'s self-action short-circuit (per [`in-app-notifications/spec.md:60`](../../specs/in-app-notifications/spec.md)) exists for cases like `LikeService` where a user can like their own post. In 1:1 chat, the conversation's participant set is exactly two distinct users (chat-foundation enforces this via the slot-race serialization at [`chat-conversations/spec.md:49`](../../specs/chat-conversations/spec.md)). The recipient = `participants \ {sender}` is always a different user. The emitter's self-action check fires zero times for chat sends — a structural no-op, not a bug.

**Why.** Documenting it explicitly so future maintainers don't try to "optimize" by removing the self-action check at the emitter level. The check is canonical for the emitter; chat just happens to never trigger it.

### D8: Test surface — new `ChatMessageNotificationTest` alongside the chat tests

**Decision.** Add a new integration test class `ChatMessageNotificationTest` (tagged `database`) in `:backend:ktor` alongside `ChatSendRouteTest`, `ChatSendRateLimitTest`, and `ChatRealtimeBroadcastTest`. The new class covers exactly the emit-path concerns:

1. Successful send invokes emit exactly once with the expected `(recipient, type='chat_message', actor=sender, target_type='message', target_id=<msg id>, body_data={conversation_id, preview})` shape.
2. Sender shadow-banned → emit NOT invoked; `chat_messages` row persists.
3. Block in either direction → emitter short-circuits with `suppressed_reason="blocked"`; zero notifications rows; the chat-foundation 403 path means we never reach the emit so this is verified at the emitter contract level (existing `NotificationWritePathTest` already covers it; we add a chat-flavored regression).
4. Emit failure (e.g., recipient hard-deleted) rolls back the entire chat send (no `chat_messages` row, no `last_message_at` update, no broadcast publish).
5. Embedded-only message (`content = NULL`, `embedded_post_snapshot = ...`) → preview is `null` in body_data.
6. 80-code-point preview truncation: 100-char content → preview is exactly 80 code points.
7. UTF-8 / emoji boundary at code point 80: a content string containing a 4-byte emoji at position 80 → preview truncates at the code-point boundary (NOT mid-emoji).
8. Composite dispatcher fan-out: a fake `FcmDispatcher` records exactly one push for the recipient on a successful emit.
9. Recipient receives the row via `GET /api/v1/notifications` (`NotificationDto` shape matches the V10 spec).

The existing `ChatSendRouteTest` adds two regression scenarios (without becoming a notification test):
- A successful send results in a `chat_message` notification visible to the recipient.
- A shadow-banned sender's send does NOT produce a notification.

This avoids cross-class duplication while keeping the regression surface visible from the chat-foundation entry point.

**Why.** Mirrors the precedent set by `chat-realtime-broadcast`: a dedicated test class for the new concern, plus light regressions in `ChatSendRouteTest` to anchor the integration. Avoids ballooning `ChatSendRouteTest` into a multi-concern god-test.

### D9: No new module, no new DI binding shape

**Decision.** The `NotificationEmitter` interface is already in `:core:data`. The production binding is in `:backend:ktor`. The chat module imports it via the same Koin DI mechanism the four existing emit sites use. Zero new modules, zero new bindings. The only DI change is adding `notificationEmitter` to `ChatService`'s constructor (or wherever the send handler tx lives).

**Why.** The infrastructure is already in place. Adding a module would be premature abstraction.

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| **Notifications panel flooding from a chatty sender (UX risk).** Five rapid-fire messages produce five notifications panel entries. | Documented as explicit non-goal. Mobile UI is responsible for panel-side grouping (tap `notifications` of `type='chat_message'` with the same `body_data.conversation_id` → collapsed under a single conversation header). Backend ships the per-message rows; product can revisit coalescing once dogfood data exists. |
| **FCM push spam.** Same shape as above — five messages = five FCM pushes. iOS may throttle; Android shows five notifications. | iOS handles this naturally via NSE-side grouping (per [`docs/04-Architecture.md:509`](../../../docs/04-Architecture.md) iOS alert + mutable-content path). Android shows individual notifications, which OS-level summarization handles. Backend doesn't need to coalesce at the FCM layer. |
| **Emit failure rolls back chat send (5xx).** If recipient is hard-deleted between auth and emit, the chat send fails with 5xx. | Acceptable per [`in-app-notifications/spec.md:86-88`](../../specs/in-app-notifications/spec.md) — the same shape as Like/Reply/Follow. **The race is structurally near-impossible**, not just rare: `conversation_participants.user_id` uses `ON DELETE RESTRICT` per [`docs/05-Implementation.md:1230`](../../../docs/05-Implementation.md), which blocks user hard-delete while any `conversation_participants` row references the recipient. The canonical tombstone worker soft-leaves participants (`left_at = NOW()`) before considering hard-delete; the chat send-handler's auth gate filters `WHERE left_at IS NULL`, so a soft-left recipient gets 403 long before reaching emit. Reaching the FK violation requires a non-canonical worker shape that manually deletes participant rows pre-user-hard-delete — outside the documented operational pattern. The resulting 5xx is essentially never observed in canonical operation; mobile's retry-after-resync UX handles the theoretical edge case if it ever fires. Tradeoff: the alternative (200-with-suppress) requires brittle PostgreSQL error-message parsing to distinguish `notifications.user_id` FK from other constraints, and would pollute the generic emit-site contract that the four other emit sites (Like/Reply/Follow/Report) follow uniformly. |
| **Body_data preview leak via SENDER-shadow-banned reads (in-tx state).** A sender shadow-banned at emit time writes a `chat_messages` row but no notification fires (sender-shadow-ban skip per § D3). | No leak — the skip is deliberate and matches `chat-realtime-broadcast`. Defense-in-depth: even if the skip ever races, the receiver-side `is_shadow_banned` filter at [`chat-conversations/spec.md`](../../specs/chat-conversations/spec.md) read-path hides the underlying row, so the recipient never sees the message it would describe. |
| **Body_data preview persists in panel after sender is shadow-banned LATER (post-emit state).** Alice sends a normal message; emit fires with preview text. Days later, admin shadow-bans Alice. The notifications row for Bob still carries the original preview text (frozen-at-emit per § D4). Bob sees the preview in his panel even though the underlying chat-messages row is now filtered from `GET /messages` by the receiver-side shadow-ban filter. | **Acceptable — this is the intended snapshot semantic.** The notifications panel is a feed of historical events Bob was authorized to receive at emit time. After-the-fact shadow-ban suppresses NEW events from the actor (no new notifications, no new chat-messages visibility), but does NOT retroactively erase Bob's existing historical receipts. This matches the like/reply/follow precedent: a notifications row written for Bob about Alice's like is NOT removed when Alice is later shadow-banned. The frozen preview is a property of a snapshot model, not a leak. |
| **Sender hard-deleted mid-flight between emit and FCM dispatch.** Alice's emit fires (in-tx, Alice not shadow-banned, notification row written with `actor_user_id = Alice`). Before FCM dispatch fires, admin hard-deletes Alice. The `notifications.actor_user_id` FK uses `ON DELETE SET NULL`, so the dispatcher reads the row with `actor_user_id = NULL`, calls `ActorUsernameLookup.lookup(null)` (or short-circuits per [`fcm-push-dispatch/spec.md:98`](../../specs/fcm-push-dispatch/spec.md)), and renders the `"Seseorang mengirim pesan"` fallback. | **Correct behavior — already covered by existing `fcm-push-dispatch` null-fallback path.** *Reconciliation with Open Q1(b2) below.* Q1(b2) frames the path as **reachable** (it is — `actor_user_id` SET NULL fires, dispatcher null-shortcircuits, fallback renders). This risk row frames the path as **structurally near-impossible during an *active* chat send** (also true — under the canonical schema's `ON DELETE RESTRICT` semantics on `chat_messages.sender_id` per [`docs/05-Implementation.md:1276`](../../../docs/05-Implementation.md) AND `conversation_participants.user_id`, the sender cannot be hard-deleted while any of their `chat_messages` rows still exist). Both framings are accurate at different time-windows: the path is reachable AT ALL only after the sender's `chat_messages` rows are themselves hard-deleted (via cascade through `conversations` deletion or admin tombstone purge that runs *after* the active chat send completes). The composition works correctly via the FK SET NULL on `notifications.actor_user_id` whenever the rare race materializes; no additional code or spec needed; the fallback path catches it. |
| **Test flakiness from FCM dispatcher fan-out.** The composite dispatcher invokes FcmDispatcher in a coroutine scope. | Use `FakeFcmDispatcher` (consistent with `fcm-push-dispatch` test precedent) that captures invocations synchronously. No real Firebase round-trip in tests. |
| **Forward-compat — future enum-count drift in spec language.** As future emit sites land (subscription, admin, deletion, privacy-flip, etc.), the count "5 of 13" in the in-app-notifications schema requirement will drift. | Each future change that wires a new emit type SHALL bump the count in the same MODIFIED block (or use OpenSpec RENAMED to update the requirement title that currently encodes the historical "four" wording). Reviewers should treat count drift as a routine update, not an error. |
| **Sender becomes shadow-banned BETWEEN emit and FCM dispatch (race window).** Emit fires in-tx (sender not shadow-banned at emit time, notification row written), tx commits, then admin flips `users.is_shadow_banned = TRUE` for sender, then FCM dispatcher reads the row, calls `ActorUsernameLookup.lookup(sender)` which now returns `null` (because `visible_users` filters out shadow-banned rows per [`fcm-push-dispatch/spec.md:431`](../../specs/fcm-push-dispatch/spec.md)). | **The fallback path IS reachable here**, contrary to the in-tx state which is unreachable. Result: FCM push body renders as `"Seseorang mengirim pesan"` (the null-fallback path) instead of `"<sender_username> mengirim pesan"`. This is the correct privacy outcome — the sender was shadow-banned by the time the dispatcher ran, so masking the username at the push surface is exactly what the system wants. The fallback path is retained in `PushCopy` for precisely this race; this risk-row documents the post-emit-pre-dispatch race as the intentional reachable case, distinct from the "structurally unreachable in-tx" framing applied earlier. |

## Migration Plan

This is a pure code addition — no schema migration, no data migration, no feature flag. Deploy path:

1. **Code change merges to `main`.** The chat handler now emits on every successful send.
2. **Cloud Run rollout (staging first, then production).** During the rollout, some pods have the new handler and some have the old. Both states are correct: old pods emit zero notifications (today's behavior); new pods emit one per successful send. There is no dual-write or schema-incompatible state.
3. **No backfill.** Pre-change messages do not get notifications retroactively. The chat history is still visible via `GET /messages`; the recipient's `notifications` panel just doesn't have entries for sends that happened before this ships.
4. **Rollback strategy:** revert the merge commit. The schema is unchanged. The only effect is "new notifications stop firing." No data is corrupted.

## Open Questions

1. **Should the FCM push payload for `chat_message` use the existing `PushCopy` template or a new chat-specific one?** ✅ **Resolved during reconciliation** — this change MODIFIES `fcm-push-dispatch` to add a `chat_message` template to `PushCopy.bodyFor(...)`. Without this, the FCM push body for a new chat message would render the generic fallback `"Notifikasi baru dari NearYou"` per the existing fallback rule at [`fcm-push-dispatch/spec.md:314`](../../specs/fcm-push-dispatch/spec.md), which is a UX gap a reviewer would immediately flag. The new template is `"<actor_username> mengirim pesan"` (or fallback `"Seseorang mengirim pesan"` when `actorUsername == null`). The null-fallback path's reachability splits into two cases: (a) **in-tx state — structurally unreachable.** Sender-shadow-ban skip at emit time means a shadow-banned sender never triggers emit, so `ActorUsernameLookup.lookup(sender)` is invoked only for non-shadow-banned senders whose `visible_users` row exists and returns a non-null username. (b) **post-commit / post-emit state — reachable via two distinct races.** (b1) Sender becomes shadow-banned between emit (in-tx) and FCM dispatch (post-commit) — `visible_users` filters them out by the time the dispatcher reads, returning null. (b2) Sender hard-deleted between emit and FCM dispatch — the `notifications.actor_user_id` FK SET NULL fires, the dispatcher reads `actor_user_id = NULL`, and short-circuits to null-fallback per [`fcm-push-dispatch/spec.md:98`](../../specs/fcm-push-dispatch/spec.md). Both (b1) and (b2) are correct privacy outcomes — the fallback ships the appropriate "Seseorang mengirim pesan" without leaking the actor handle. The chat-message preview from `body_data.preview` is NOT inlined into the FCM push body in this change — that's an iOS/Android NSE-side rendering concern (the push body says "X mengirim pesan", and the mobile UI surfaces the preview via the data-payload `body_data.preview` key). Title remains the constant `"NearYou"` per the existing `titleFor(...)` rule.

2. **Should `notifications.read_at` auto-update when the recipient enters the conversation?** Currently a manual `PATCH /notifications/:id/read` endpoint exists. For chat, an "open the conversation" UX naturally implies "read the notification(s) for this conversation." Default plan: out of scope for this change — that's a mobile-driven UX concern that needs a backend endpoint shape (e.g., `PATCH /notifications/by-conversation/:id/read-all`) which deserves its own design pass. File a follow-up if the mobile track needs it before launch.

3. **Should chat redaction (`chat_message_redacted`) be wired in the same change?** [`docs/05-Implementation.md:866`](../../../docs/05-Implementation.md) lists `chat_message_redacted` as a separate notification type, fired when admin redacts a message in the recipient's conversation. The redaction admin endpoint is Phase 3.5 (`docs/08-Roadmap-Risk.md:305`). Default plan: out of scope — `chat_message_redacted` belongs to the redaction admin change. Don't wire it here.
