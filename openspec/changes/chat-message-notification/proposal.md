## Why

`chat-realtime-broadcast` (PR [#63](https://github.com/aditrioka/nearyou-id/pull/63)) closed the foreground-delivery loop for 1:1 chat — when both participants have the app open, the recipient receives the message via Supabase Realtime broadcast. **But an offline recipient is never told a new message arrived.** No FCM push fires; no row lands in their `notifications` panel. The `chat_message` notification type has been baked into the V10 enum since [`in-app-notifications`](../../specs/in-app-notifications/spec.md) shipped — but no code emits it. [`in-app-notifications/spec.md:11`](../../specs/in-app-notifications/spec.md) acknowledges this directly: "all 13 values per the canonical catalog even though V10 only writes 4."

This change wires the chat send handler to emit the `chat_message` notification on every successful send, fanning the recipient out to the existing composite `NotificationDispatcher` (in-app row + FCM push). It closes the chat-MVP backend loop alongside `chat-foundation` (PR [#61](https://github.com/aditrioka/nearyou-id/pull/61)), `chat-rate-limit` (PR [#62](https://github.com/aditrioka/nearyou-id/pull/62)), and `chat-realtime-broadcast` (PR [#63](https://github.com/aditrioka/nearyou-id/pull/63)) — covering Phase 2 §5 + §7-8 in [`docs/08-Roadmap-Risk.md:152`](../../../docs/08-Roadmap-Risk.md).

## What Changes

- The chat send route handler at `POST /api/v1/chat/{conversation_id}/messages` SHALL invoke `NotificationEmitter.emit(...)` for the recipient inside the existing chat-foundation transaction (the same tx that runs `INSERT chat_messages` + `UPDATE conversations.last_message_at`).
- The emitted notification SHALL carry `type = 'chat_message'`, `actor_user_id = sender`, `target_type = 'message'`, `target_id = <chat_messages.id>`, `body_data = {"conversation_id": <UUID>, "preview": <string|null>}` per the canonical catalog at [`docs/05-Implementation.md:860`](../../../docs/05-Implementation.md).
- `preview` SHALL be the first 80 code points of `chat_messages.content` taken at emit time (matching the truncation policy of `post_liked` / `post_replied` excerpts per [`in-app-notifications/spec.md:257`](../../specs/in-app-notifications/spec.md)). When `content` is `NULL` (an embedded-only message — schema-permitted per [`docs/05-Implementation.md:1273`](../../../docs/05-Implementation.md) `CHECK (content IS NOT NULL OR embedded_post_id IS NOT NULL OR embedded_post_snapshot IS NOT NULL)`), `preview` SHALL be JSON `null`.
- The recipient SHALL be the OTHER participant in the conversation (1:1 chat: `participants \ {sender}`). Resolution SHALL reuse the data already loaded by chat-foundation's auth + active-participant gate — no extra `SELECT participants` is issued for emit.
- **Sender-shadow-ban skip**: when `viewer.isShadowBanned = TRUE` on the request principal (the same flag chat-realtime-broadcast reads), `NotificationEmitter.emit(...)` SHALL NOT be invoked. The `chat_messages` row still persists (chat-foundation's invisible-actor contract is preserved verbatim). This matches `chat-realtime-broadcast`'s publish-skip semantics — no extra `SELECT is_shadow_banned` SQL is issued.
- **Bidirectional-block suppression** is delegated to `NotificationEmitter` (already specified in [`in-app-notifications/spec.md:71-76`](../../specs/in-app-notifications/spec.md)) — no new logic in the chat handler. If a `user_blocks` row exists in either direction, the emit short-circuits with `suppressed_reason = "blocked"` and zero notifications rows are written.
- **Tx ordering**: emit happens AFTER the `chat_messages` INSERT (so `target_id` references a real row) but inside the SAME transaction. The chat-realtime-broadcast publish remains POST-commit and best-effort (no behavioral change there); ordering inside the tx is INSERT → emit → UPDATE last_message_at → COMMIT → publish.
- **Rollback semantics**: emit failure rolls back the entire chat send (matches [`in-app-notifications/spec.md:86-88`](../../specs/in-app-notifications/spec.md) "Emit failure rolls back primary write"). The 4xx surface for "recipient hard-deleted between auth and emit" is acceptable per the design's risk discussion (rare race; recipient must have been gone for a non-trivial window between auth and the emit).
- **Self-action suppression** is a structural no-op: chat-foundation rejects single-participant conversations (per [`chat-conversations/spec.md`](../../specs/chat-conversations/spec.md)), so `actor_user_id ≠ recipient_user_id` always; the `NotificationEmitter` self-action short-circuit never fires for chat sends.

## Capabilities

### New Capabilities

(None — this change reuses the existing `NotificationEmitter` and `NotificationDispatcher` infrastructure shipped under `in-app-notifications` + `fcm-push-dispatch`.)

### Modified Capabilities

- `chat-conversations`: the chat send handler at `POST /api/v1/chat/{conversation_id}/messages` gains a notification-emit step inside the existing transaction. Adds a sender-shadow-ban skip semantics requirement aligned with `chat-realtime-broadcast`. No schema change; no API contract change visible to clients (the response shape is identical).
- `in-app-notifications`: adds `chat_message` as the 5th wired emit type with its body_data preview shape and recipient-resolution rule. The "V10 only writes 4" wording in the existing schema requirement is updated to reflect that V10 now writes 5 after this change ships — a wording fix, not a schema or contract change. The block-suppression and self-action-suppression contracts remain unchanged (this change consumes them as-is).
- `fcm-push-dispatch`: extends `PushCopy.bodyFor(...)` with a `chat_message` template (`"<actor_username> mengirim pesan"` / fallback `"Seseorang mengirim pesan"`). Without this addition the FCM push body for a new chat message would render the generic fallback `"Notifikasi baru dari NearYou"` — a degraded UX. The dispatcher contract, the `ActorUsernameLookup.lookup(...)` masking-via-`visible_users` contract, the per-platform payload builders, and the `chat_message` `titleFor(...)` constant `"NearYou"` are all unchanged.

## Impact

- **Code**: `:backend:ktor` chat module — `ChatService.kt` / `ChatRoutes.kt` (or whichever currently owns the send-handler tx) gets `NotificationEmitter` injected and called inside the tx. New tests in `:backend:ktor` (e.g., `ChatMessageNotificationTest`) covering the emit shape, sender-shadow-ban skip, block-suppression delegation, tx-rollback-on-emit-failure, embedded-only-preview-null, and the FCM dispatcher fan-out via `FakeFcmDispatcher` (consistent with `fcm-push-dispatch` test precedent).
- **APIs**: no contract change to `POST /api/v1/chat/{conversation_id}/messages` (status codes, response shape unchanged). New side-effect: `notifications` row + FCM push fire for the recipient on a successful send by a non-shadow-banned, non-blocked sender.
- **Schema**: zero migrations. The `notifications` enum already includes `chat_message` (V10 / [`docs/05-Implementation.md:826-832`](../../../docs/05-Implementation.md)).
- **Dependencies**: no new library pin. Reuses `:infra:fcm` + `:core:data` `NotificationEmitter` + `:core:data` `NotificationDispatcher` already on the classpath.
- **Mobile**: no mobile change in this PR. Mobile already handles `chat_message` notifications (the type was in the catalog from V10 ship). Mobile-side coalescing/grouping in the notifications panel UI is owned by the mobile track.
- **Out-of-scope clarifications** (non-goals — call out in spec § Non-goals):
  - Notification deduplication / debouncing across rapid consecutive messages in the same conversation. UX risk (recipient's panel can be flooded by a chatty sender) is real but coalescing belongs in a future change with explicit product + UX input. MVP: one notifications row per message; mobile UI is responsible for any panel-side grouping.
  - Custom mute / per-conversation notification preferences (post-MVP).
  - Coalesced "X new messages" FCM payloads (FCM payload is per message, mirroring per-row notifications).
  - Read-receipt / typing-indicator FCM payloads.
  - Retroactive backfill of notifications for messages sent before this change ships.
  - Receiver-side shadow-ban semantics: the receiver's `is_shadow_banned` state does NOT skip the emit (parallel to `chat-realtime-broadcast`'s receiver-side non-skip — the recipient still sees their own notifications panel; shadow-ban affects others' visibility of the recipient, not the recipient's own feed).
  - Banned-sender publish-skip: senders with `is_banned = TRUE` are 403'd by chat-foundation's auth path before the chat send handler runs; the emit is reached only by senders who passed auth (same shape as `chat-realtime-broadcast`).
