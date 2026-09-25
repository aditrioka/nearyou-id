# Tasks: profile-send-message

## 1. ViewModel + state

- [ ] 1.1 `ProfileUiState`: add `isOpeningChat: Boolean` + `openChatConversationId: String?` (one-shot) fields; `profileUiState(...)` gains both as defaulted params; add `ProfileMessage.CHAT_BLOCKED`
- [ ] 1.2 `ProfileViewModel`: `chatFlow: ChatFlow? = null` constructor param; `onSendMessage()` — no-op when `chatFlow` null / not loaded / `isSelf` / already in flight; sets `isOpeningChat` synchronously, calls `createOrReturn(resolvedUserId)`, maps the outcome exhaustively (design D4); `onChatOpened()` clears the one-shot

## 2. Screen + host wiring

- [ ] 2.1 `:shared:resources`: add `profile_send_message` = "Kirim pesan"; bump `SharedStringsCatalogTest` (reference + count)
- [ ] 2.2 `ProfileScreen`: fail-safe `getKoin().getOrNull<ChatFlow>()` (design D2); hoisted `onOpenChat: (conversationId, partnerUsername, partnerDisplayName) -> Unit = { _, _, _ -> }`; `LaunchedEffect` consumes `openChatConversationId` with the loaded profile's identity then `onChatOpened()`; `CHAT_BLOCKED` → `chat_send_blocked`
- [ ] 2.3 `ProfileContent`: `OutlinedButton` "Kirim pesan" (tag `PROFILE_SEND_MESSAGE_TAG`) between the follow toggle and the kebab, rendered iff `!isSelf` and `onSendMessage != null`, `enabled = !isOpeningChat` (design D6)
- [ ] 2.4 `AppEntryProvider` `ProfileRoute` entry: wire `onOpenChat` → `backStack.add(ChatThreadRoute(conversationId, partnerUsername, partnerDisplayName))`
- [ ] 2.5 `ChatThreadViewModel`: delete the dead `startConversation` / `startedConversationId` / `startOutcome` placeholder (design D1); update the stale "future profile caller" KDoc in `ChatFlow` / `NavKeys` / `PostCard`

## 3. Tests

- [ ] 3.1 `FakeChatFlow`: record `createOrReturn` recipient ids + an optional suspend gate (for the in-flight test)
- [ ] 3.2 `ChatRepository.createOrReturn` mapping test (MockEngine): `201` and `200` both collapse to `Ready(id)`; `403`/`400`/`404` → `Blocked`/`SelfConversation`/`RecipientNotFound`; `401` → `SessionExpired`; `5xx` → `NetworkError` (no repository-level test existed)
- [ ] 3.3 `ProfileViewModelTest`: `Ready` → one-shot set with the resolved id passed + `onChatOpened` clears; `Blocked` → `CHAT_BLOCKED`; `RecipientNotFound` → `TARGET_UNAVAILABLE`; `SelfConversation`/`Error`/`NetworkError`/`SessionExpired` → `ACTION_FAILED` + `isOpeningChat` false; in-flight double-tap → one call; self read → no call; null `chatFlow` → no-op
- [ ] 3.4 `ProfileScreenTest` (Robolectric): other-user shows "Kirim pesan", self does not; tap with `Ready("c1")` → `onOpenChat("c1", username, displayName)`; `Blocked` → the send-blocked snackbar and no `onOpenChat`; no `ChatFlow` bound → no action; the no-UUID-in-tree guard still holds
- [ ] 3.5 `ProfileFlowIosTest`: other-user "Kirim pesan" present + tap emits `onOpenChat` on the simulator (K/N-legal name)

## 4. Verification & lifecycle

- [ ] 4.1 Gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` + `:mobile:app:iosSimulatorArm64Test`
- [ ] 4.2 Manual verify (verify-loop §B/§C, local emulator + iOS simulator): other-user profile shows "Kirim pesan"; tap → thread opens with the partner identity in the top bar; back returns to the profile; screenshots into the PR body (docs/11 §5 DoD)
- [ ] 4.3 PR title/body current; `Closes #271`; archive via `/opsx:archive`
