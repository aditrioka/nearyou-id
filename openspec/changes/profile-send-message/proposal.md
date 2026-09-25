# Proposal: profile-send-message

## Why

An other-user profile has no way to start a chat. The only chat entries are resuming an existing conversation from the Pesan list, a notification deep-link, or share-to-chat from a post, so users cannot message someone they found through a feed or a follow list. The create-or-return path is fully shipped (`POST /api/v1/conversations` per `chat-conversations`; `ConversationsApiClient.createOrReturnConversation` → `ChatRepository.createOrReturn` → `CreateConversationOutcome` per `mobile-chat`), but nothing calls it: `mobile-chat-screen` design D5 deferred the profile caller until the profile screen shipped (#245, since merged). This change adds that caller. It closes GitHub issue [#271](https://github.com/aditrioka/nearyou-id/issues/271).

## What Changes

- The **other-user** `ProfileScreen` gains a **"Kirim pesan"** action next to the follow toggle. It is absent on the self read, like the follow toggle and kebab.
- Tapping it has `ProfileViewModel` call the shipped create-or-return path (`ChatFlow.createOrReturn(resolvedUserId)`). On `Ready(conversationId)` the screen asks the host to open the thread. `AppEntryProvider` pushes `ChatThreadRoute(conversationId, partnerUsername, partnerDisplayName)` onto the root back stack; the display identity comes from the already-loaded profile, so the route stays UUID-free.
- Non-success outcomes surface as the profile's existing snackbar one-shot:
  - `Blocked` (403) → the docs-verbatim `chat_send_blocked` copy "Tidak dapat mengirim pesan ke user ini".
  - `RecipientNotFound` (404) → the existing neutral, direction-less "Pengguna tidak tersedia." copy.
  - `SelfConversation` / `Error` / `NetworkError` / `SessionExpired` → the generic "Gagal. Coba lagi." copy. This matches the share-to-chat picker's `Failed` bucket; a terminal 401 is re-routed by the `Auth` plugin regardless.
- **In-flight guard**: while a create-or-return is outstanding, further taps are ignored and the button is disabled, so a double-tap cannot push two thread routes. The endpoint is idempotent but navigation is not.
- **Removal (dead code)**: the `ChatThreadViewModel.startConversation` / `startedConversationId` / `startOutcome` placeholder is deleted. It was a stand-in for a future caller, but the thread VM is keyed on an existing conversation id and can never be the create-or-return caller. It has no call sites and no tests.

## Capabilities

### New Capabilities

None. This wires a navigation/action affordance on an existing surface to an already-shipped read/write path.

### Modified Capabilities

- `mobile-profile`: "Self vs other-user rendering is driven by isSelf" is MODIFIED so the other-user action set includes "Kirim pesan", still hidden on the self read. A requirement is ADDED for the "Kirim pesan" create-or-return behavior: outcome mapping, in-flight guard, host-emitted navigation, and PII discipline.
- `mobile-chat`: "Create-or-return opens or resumes a conversation" is MODIFIED to name the shipped caller (the `mobile-profile` "Kirim pesan" action via the `ChatFlow` seam) instead of "the future profile caller", and to drop the thread-VM placeholder.

## Impact

- **Code** (`mobile/app/src/commonMain/.../`):
  - `screens/profile/ProfileViewModel.kt`: a nullable `ChatFlow` dependency, `onSendMessage()` / `onChatOpened()`, and the new VM state.
  - `screens/profile/ProfileUiState.kt`: `isOpeningChat` + the `openChatConversationId` one-shot, plus a new `ProfileMessage.CHAT_BLOCKED`.
  - `screens/profile/ProfileScreen.kt`: the button, the hoisted `onOpenChat`, and fail-safe `ChatFlow` resolution.
  - `screens/routing/AppEntryProvider.kt`: wire `onOpenChat` → `ChatThreadRoute`.
  - `screens/chat/ChatThreadViewModel.kt`: delete the placeholder.
- **Resources**: one new `:shared:resources` string (`profile_send_message` = "Kirim pesan"); `SharedStringsCatalogTest` count bump.
- **Tests**: `ProfileViewModelTest` (outcome mapping, in-flight guard, self guard, one-shot clear), `ProfileScreenTest` (presence other vs self, tap → `onOpenChat` args, blocked snackbar), `ProfileFlowIosTest` (the action on the simulator).
- **No** backend, admin, route, DI-module, or wire change. The vertical slice is complete by construction: backend `POST /api/v1/conversations`, the mobile ApiClient/Repository, and the `ChatThreadRoute` surface are all shipped, and there is no admin counterpart (docs/12).
- **Issue**: closes #271.
