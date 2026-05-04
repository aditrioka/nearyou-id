## 1. Wiring & Boot

- [ ] 1.1 Inject `NotificationEmitter` into `ChatService` (or whichever class owns the chat send-handler tx — verify by reading the current `:backend:ktor` chat module before editing). Add the constructor parameter; update Koin DI binding for `ChatService` in the production module to pass the existing `NotificationEmitter` binding.
- [ ] 1.2 In `:infra:fcm` `PushCopy.bodyFor(...)`, add the `chat_message` template (per the MODIFIED `fcm-push-dispatch` spec in this change): `"<actor_username> mengirim pesan"` when `actorUsername != null`, fallback `"Seseorang mengirim pesan"` when null. The body SHALL NOT inline `body_data.preview` — preview rendering is a mobile NSE / Android side concern via the data payload. `PushCopy` must not depend on Moko Resources (per the existing `fcm-push-dispatch` D4 — backend strings are server-side i18n).
- [ ] 1.3 If `ChatService` does not currently own a transaction boundary that can hold both `INSERT chat_messages` and the emit, refactor the chat send code path so the tx encompasses INSERT → emit → UPDATE last_message_at → COMMIT (per design § D5). Do NOT introduce a new transactional surface elsewhere — extend the existing one.

## 2. Emit-Path Implementation

- [ ] 2.1 In the chat send-handler tx, after `INSERT INTO chat_messages (...) RETURNING id`, compute the recipient as `participants \ {sender}` from the participant set already loaded for the auth + active-participant gate. Do NOT issue a fresh `SELECT conversation_participants` for the emit (per design § D2; verified by the JDBC-spy scenario in `chat-conversations` § "Notification emit reuses already-loaded participants").
- [ ] 2.2 Compute `body_data.preview`: first 80 code points of `chat_messages.content`, OR JSON `null` when `content IS NULL`. Use code-point truncation (NOT bytes, NOT chars) — `String.codePoints().limit(80)` or equivalent (matches the existing `post_excerpt` precedent).
- [ ] 2.3 Compute `body_data.conversation_id` as the conversation's UUID string in canonical lowercase RFC 4122 form (Java `UUID.toString()`).
- [ ] 2.4 If `viewer.isShadowBanned == true` on the request principal, SKIP the emit entirely (do NOT call `NotificationEmitter.emit(...)`). Read the flag exclusively from `UserPrincipal` — do NOT issue any `SELECT is_shadow_banned FROM users` for the emit decision (verified by the JDBC-spy scenario "Shadow-ban skip reads from principal").
- [ ] 2.5 Otherwise, invoke `notificationEmitter.emit(recipientUserId = recipient, type = "chat_message", actorUserId = sender, targetType = "message", targetId = <inserted chat_messages.id>, bodyData = mapOf("conversation_id" to <UUID string>, "preview" to <string|null>))` — passing the call inside the SAME tx as the chat-foundation INSERT.
- [ ] 2.6 Verify (by code review of the diff) that the chat handler does NOT add a separate block-check or self-check before the emit — block-suppression is delegated to the emitter (per design § D6); self-action is structurally impossible (per design § D7).
- [ ] 2.7 Verify (by code review of the diff) that the broadcast publish step remains POST-commit and unchanged (no behavioral change to chat-realtime-broadcast in this change).

## 3. Tests — `PushCopy` (`:infra:fcm`)

- [ ] 3.1 Add unit tests for `PushCopy.bodyFor` covering `chat_message` per the MODIFIED `fcm-push-dispatch` spec scenarios:
  - `bodyFor(chat_message_notification, actorUsername = "bobby")` → `"bobby mengirim pesan"`
  - `bodyFor(chat_message_notification, actorUsername = null)` → `"Seseorang mengirim pesan"`
  - `bodyFor(chat_message_notification_with_preview, actorUsername = "bobby")` → exactly `"bobby mengirim pesan"` (preview NOT inlined into body)
  - `titleFor("chat_message")` → `"NearYou"` (regression — already covered by the existing scenario in `fcm-push-dispatch`)

## 4. Tests — Integration (`:backend:ktor`)

- [ ] 4.1 Create `ChatMessageNotificationTest` in `:backend:ktor` (tagged `database`, alongside `ChatSendRouteTest` / `ChatSendRateLimitTest` / `ChatRealtimeBroadcastTest`).
- [ ] 4.2 Test: successful send invokes `NotificationEmitter.emit` exactly once with `(recipient = B, type = "chat_message", actor = A, target_type = "message", target_id = <inserted id>, body_data = {conversation_id, preview})`. Use a `FakeNotificationEmitter` test-double that records every invocation. Verifies `chat-conversations` § "Notification emit invoked exactly once per successful send" + § "Active participant sends a valid message".
- [ ] 4.3 Test: emit's recipient is the OTHER participant, not the sender (`recipient_user_id == B; actor_user_id == A`). Verifies `chat-conversations` § "Notification emit recipient is the OTHER participant".
- [ ] 4.4 Test: JDBC statement spy captures all SQL during a successful send; the captured statements contain exactly ONE `SELECT ... FROM conversation_participants` (the chat-foundation auth gate) — no second SELECT for the emit. Verifies `chat-conversations` § "Notification emit reuses already-loaded participants (no extra SELECT)".
- [ ] 4.5 Test: shadow-banned sender (`viewer.isShadowBanned = TRUE`) — the `chat_messages` row persists, but `NotificationEmitter.emit` is NOT invoked AND `ChatRealtimeClient.publish` is NOT invoked AND zero `notifications` rows exist for the recipient. Verifies `chat-conversations` § "Shadow-banned sender — emit AND publish both skipped, row persists".
- [ ] 4.6 Test: JDBC statement spy on a shadow-banned send — zero `SELECT is_shadow_banned FROM users` statements issued by the chat send handler for the emit decision. Verifies `chat-conversations` § "Shadow-ban skip reads from principal, not a fresh DB SELECT".
- [ ] 4.7 Test: block in either direction → 403 from chat-foundation, no row inserted, emit NOT invoked, publish NOT invoked. Two scenarios (Alice→Bob block; Bob→Alice block). Verifies `chat-conversations` § "Block in either direction rejects send before emit" + § "Block in the reverse direction also rejects send before emit".
- [ ] 4.8 Test: 80-code-point preview truncation — 100-char ASCII content → preview is exactly the first 80 chars. Verifies `chat-conversations` § "80-code-point preview truncation".
- [ ] 4.9 Test: multi-byte (emoji) content — 90-char content with a 4-byte emoji at code-point positions 78–80 → preview is exactly 80 code points; emoji is NOT split mid-bytes. Verifies `chat-conversations` § "Multi-byte (emoji) content truncates at code-point boundary".
- [ ] 4.10 Test: embedded-only message (`content = NULL`, `embedded_post_snapshot = ...`) — the captured `body_data.preview` is JSON `null` (not `""`, not a placeholder). Use a synthetic test-only repository hook that bypasses the public endpoint's content-required guard (since the public endpoint forbids empty content per the existing 400 contract). Verifies `chat-conversations` § "Embedded-only message produces null preview".
- [ ] 4.11 Test: emit failure (recipient hard-deleted between auth and emit, FK violation on `notifications.user_id`) → entire tx rolls back: zero `chat_messages` rows persist; `last_message_at` unchanged; zero `notifications` rows; publish NOT invoked; HTTP 5xx. Verifies `chat-conversations` § "Emit failure rolls back the entire chat send".
- [ ] 4.12 Test: composite dispatcher fan-out — production composite `NotificationDispatcher` bound, with `FakeFcmDispatcher` swapped in for the FCM leg. After a successful chat send, `FakeFcmDispatcher.dispatchInvocations` records exactly one entry for `recipient = B`. Verifies `chat-conversations` § "Composite dispatcher fan-out — one push per emit".
- [ ] 4.13 Test: recipient sees the new notification via `GET /api/v1/notifications` — response includes a `NotificationDto` with the expected shape (`type = "chat_message"`, `actor_user_id`, `target_type = "message"`, `target_id`, `body_data.conversation_id`, `body_data.preview`, `read_at = null`). Verifies `chat-conversations` § "Recipient sees the notification via GET /api/v1/notifications".
- [ ] 4.14 Test: preview frozen at emit — after a successful send, simulate redaction (direct UPDATE on `chat_messages` setting `redacted_at`, clearing `content`); the recipient's subsequent `GET /api/v1/notifications` still shows the original preview. Verifies `chat-conversations` § "Preview frozen at emit (later content edit does not mutate notification)".
- [ ] 4.15 Test: publish failure (test-double `FakeChatRealtimeClient.publish` returns `PublishResult.Failure`) does NOT roll back the persisted INSERT or the `notifications` row. HTTP 201; chat row persists; notification row persists; WARN log emitted with `event = "chat_realtime_publish_failed"`. Verifies `chat-conversations` § "Publish failure does not roll back persisted INSERT or notification".
- [ ] 4.16 Test: 2001-char content → 400; whitespace-only → 400; non-participant → 403; unknown conversation → 404; malformed UUID → 400; unauthenticated → 401. In each case, emit NOT invoked, publish NOT invoked. Verifies the matching scenarios under `chat-conversations` § "Send-message endpoint" — these are regressions on the pre-emit short-circuit paths.

## 5. Tests — Light Regression in `ChatSendRouteTest`

- [ ] 5.1 Add to `ChatSendRouteTest` (NOT to the new `ChatMessageNotificationTest`): a regression scenario asserting that a successful send results in a `chat_message` notification visible to the recipient via `GET /api/v1/notifications`. Keep this lightweight — full coverage lives in `ChatMessageNotificationTest`.
- [ ] 5.2 Add to `ChatSendRouteTest`: a regression scenario asserting that a shadow-banned sender's send does NOT produce a `notifications` row. Lightweight; full coverage in `ChatMessageNotificationTest`.

## 6. Spec Hygiene & Doc Sync

- [ ] 6.1 Verify the proposal/design/specs read together coherently — tx ordering described in design § D5 matches the spec's tx-ordering paragraph; the body_data shape in the spec matches the canonical catalog at `docs/05-Implementation.md:860`.
- [ ] 6.2 Run `openspec validate chat-message-notification --strict` and fix any reported issues.
- [ ] 6.3 Update [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) Notifications Schema section if any wording in the catalog or `body_data` table needs to reflect that `chat_message` is now a wired type (only if the doc currently says otherwise; the catalog itself does not need modification — the shape `{conversation_id, preview}` is already canonical).
- [ ] 6.4 If any pre-existing `FOLLOW_UPS.md` entries reference the unwired `chat_message` notification type (search for `chat_message` in `FOLLOW_UPS.md`), tick the relevant boxes or remove the entry once this change ships. Verify by grep before opening the PR.

## 7. CI & Local Pre-Push Verification

- [ ] 7.1 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally and confirm green per [`CLAUDE.md`](../../../CLAUDE.md) § Delivery workflow ("Pre-push verification"). CI runs both `ktlintCheck` AND `detekt` — passing only one is insufficient (precedent: PRs #31 + #32 hit CI lint failures because `ktlintCheck` was skipped locally).
- [ ] 7.2 Verify no Detekt rule violations in the chat module diff — particularly `BlockExclusionJoinRule` (the existing `// @allow-no-block-exclusion: chat-history-readable-after-block` annotation in the chat send query stays untouched; this change does not alter that query).
- [ ] 7.3 Verify the change does not alter any of the V10 / V11 / V12 / V13 / V14 / V15 / V16 / V17 / V18 Flyway migrations — this is a pure code change. Grep `backend/ktor/src/main/resources/db/migration/` for any new `V*.sql` files; there should be none.
- [ ] 7.4 Test: integration — successful chat send from A to B confirms the chat-side block-suppression scenario in `in-app-notifications` § "Chat-side block-suppression" (chat-foundation's 403 fires upstream of the emit when `user_blocks` row exists). This is a small regression check that lives wherever the existing block-rejection chat tests live.

## 8. Staging Smoke (pre-archive, run during `/opsx:apply`)

- [ ] 8.1 Per `staging-deploy-before-archive` skill convention (PR [#50](https://github.com/aditrioka/nearyou-id/pull/50)): trigger a manual staging deploy of the change branch via `gh workflow run deploy-staging.yml --ref <change-branch>` BEFORE `/opsx:archive`. Wait for the deploy to complete green.
- [ ] 8.2 Run a staging smoke against `api-staging.nearyou.id`: a real chat send from A to B → confirm B's `GET /api/v1/notifications` returns a row with `type = "chat_message"`, the right `body_data.conversation_id` AND `body_data.preview`. Use a curl-based or scripted check; the smoke does NOT need to verify the FCM push end-to-end (FCM staging requires real device; out of scope for the smoke). Skip if staging is paused (Free tier idle auto-pause); document the skip in the PR body and rerun before archive.
- [ ] 8.3 Run a staging smoke against the shadow-ban skip path: a chat send from a shadow-banned A to B → confirm zero `notifications` rows for B for that message (query staging Supabase directly via the admin connection; or skip if staging Supabase is paused — document).
