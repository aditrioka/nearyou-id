# Tasks: profile-send-message

## 1. ViewModel + state

- [x] 1.1 `ProfileUiState`: add `isOpeningChat: Boolean` + `openChatConversationId: String?` (one-shot) fields; `profileUiState(...)` gains both as defaulted params; add `ProfileMessage.CHAT_BLOCKED`
- [x] 1.2 `ProfileViewModel`: `chatFlow: ChatFlow? = null` constructor param; `onSendMessage()` — no-op when `chatFlow` null / not loaded / `isSelf` / already in flight; sets `isOpeningChat` synchronously, calls `createOrReturn(resolvedUserId)`, maps the outcome exhaustively (design D4); `onChatOpened()` clears the one-shot

## 2. Screen + host wiring

- [x] 2.1 `:shared:resources`: add `profile_send_message` = "Kirim pesan"; bump `SharedStringsCatalogTest` (reference + count)
- [x] 2.2 `ProfileScreen`: fail-safe `getKoin().getOrNull<ChatFlow>()` (design D2); hoisted `onOpenChat: (conversationId, partnerUsername, partnerDisplayName) -> Unit = { _, _, _ -> }`; `LaunchedEffect` consumes `openChatConversationId` with the loaded profile's identity then `onChatOpened()`; `CHAT_BLOCKED` → `chat_send_blocked`
- [x] 2.3 `ProfileContent`: `OutlinedButton` "Kirim pesan" (tag `PROFILE_SEND_MESSAGE_TAG`) between the follow toggle and the kebab, rendered iff `!isSelf` and `onSendMessage != null`, `enabled = !isOpeningChat` (design D6)
- [x] 2.4 `AppEntryProvider` `ProfileRoute` entry: wire `onOpenChat` → `backStack.add(ChatThreadRoute(conversationId, partnerUsername, partnerDisplayName))`
- [x] 2.5 `ChatThreadViewModel`: delete the dead `startConversation` / `startedConversationId` / `startOutcome` placeholder (design D1); update the stale "future profile caller" KDoc in `ChatFlow` / `NavKeys` (`PostCard`'s "kirim pesan" note is the separate card-action deferral #238 — unchanged)

## 3. Tests

- [x] 3.1 `FakeChatFlow`: record `createOrReturn` recipient ids + an optional suspend gate (for the in-flight test)
- [x] 3.2 `ChatRepository.createOrReturn` mapping test (MockEngine): `201` and `200` both collapse to `Ready(id)`; `403`/`400`/`404` → `Blocked`/`SelfConversation`/`RecipientNotFound`; `401` → `SessionExpired`; `5xx` → `NetworkError` (no repository-level test existed)
- [x] 3.3 `ProfileViewModelTest`: `Ready` → one-shot set with the resolved id passed + `onChatOpened` clears; `Blocked` → `CHAT_BLOCKED`; `RecipientNotFound` → `TARGET_UNAVAILABLE`; `SelfConversation`/`Error`/`NetworkError`/`SessionExpired` → `ACTION_FAILED` + `isOpeningChat` false; in-flight double-tap → one call; self read → no call; null `chatFlow` → no-op
- [x] 3.4 `ProfileScreenTest` (Robolectric): other-user shows "Kirim pesan", self does not; tap with `Ready("c1")` → `onOpenChat("c1", username, displayName)`; `Blocked` → the send-blocked snackbar and no `onOpenChat`; a gated (unresolved) create-or-return → the action renders disabled; no `ChatFlow` bound → no action; the no-UUID-in-tree guard still holds
- [x] 3.5 `ProfileFlowIosTest`: other-user "Kirim pesan" present + tap emits `onOpenChat` on the simulator (K/N-legal name)

## 4. Verification & lifecycle

- [x] 4.1 Gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` + `:mobile:app:iosSimulatorArm64Test`
- [x] 4.2 Manual verify (verify-loop §B/§C, local emulator + iOS simulator): other-user profile shows "Kirim pesan"; tap → thread opens with the partner identity in the top bar; back returns to the profile; screenshots into the PR body (docs/11 §5 DoD)
- [x] 4.3 PR title/body current; `Closes #271`; archive via `/opsx:archive`

## 5. Review round (4-lens sub-agent review; qodo paused for this account)

- [x] 5.1 Snapshot the open-chat one-shot as `ProfileChatTarget` (id + display identity at resolve time) — removes the phase dependency + the same-id re-key trap (design D3)
- [x] 5.2 Drop a `Ready` that lands after a successful block (design D7) + `ProfileViewModelTest` block-then-Ready case
- [x] 5.3 Rename the host lambda `onOpenChat` → `onOpenChatThread` (the shell's existing name for the thread push; docs/11 §4)
- [x] 5.4 Action row → `FlowRow` so a large font scale wraps instead of squeezing the kebab (design D6); verified on-device at font scale 2
- [x] 5.5 Tests: screen proves the one-shot clears (second tap re-emits the same id); `ChatThreadViewModelSourceGuardTest` backs the mobile-chat "no create-or-return entry" scenario; not-yet-loaded no-op; repository transport-failure branch; self/other render tests bind `ChatFlow` and assert the action
- [x] 5.6 Drop the unused `chatFlow = null` default (both callers pass it explicitly)
