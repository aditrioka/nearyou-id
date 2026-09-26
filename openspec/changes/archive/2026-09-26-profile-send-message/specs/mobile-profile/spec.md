# mobile-profile — delta for profile-send-message

## MODIFIED Requirements

### Requirement: Self vs other-user rendering is driven by isSelf

`ProfileScreen` SHALL show the follow/unfollow toggle, the **"Kirim pesan"** action, and the kebab (Blokir / Laporkan) **only when `isSelf = false`**. When `isSelf = true` (the self read) it SHALL render NO follow toggle, NO "Kirim pesan" action, and NO block/report kebab (a user cannot follow, message, block, or report themselves; the backend rejects self-follow/self-DM/self-block/self-report anyway). The self profile is reached via the Profil bottom-nav section (its `userId` resolved from the session, per `mobile-home-tab-host`); an other-user profile is reached via `ProfileRoute(userId)`.

#### Scenario: Self read shows no actions

- **GIVEN** a loaded profile with `isSelf = true`
- **WHEN** `ProfileScreen` is rendered
- **THEN** no follow/unfollow control, no "Kirim pesan" action, and no Blokir/Laporkan kebab are present in the tree

#### Scenario: Other-user read shows the actions

- **GIVEN** a loaded profile with `isSelf = false`
- **WHEN** `ProfileScreen` is rendered
- **THEN** a follow/unfollow control AND a "Kirim pesan" action AND a kebab exposing Blokir + Laporkan are present

## ADDED Requirements

### Requirement: "Kirim pesan" opens or resumes a 1:1 conversation from the other-user profile

The other-user `ProfileScreen` SHALL render a "Kirim pesan" action (label via `stringResource`, a standard M3 button touch target) beside the follow toggle. The action row SHALL wrap rather than shrink the trailing Blokir/Laporkan kebab when the row outgrows the width (e.g. at a large font scale). Activating it SHALL make `ProfileViewModel` call the shipped create-or-return path through the `ChatFlow` seam (`ChatFlow.createOrReturn(recipientUserId)` with the profile's resolved `userId`; never `ConversationsApiClient` directly, per the `UI → ViewModel → Repository → ApiClient` direction) and map the `CreateConversationOutcome` exhaustively (no generic fallthrough):

- `Ready(conversationId)` (the endpoint's `201` new OR `200` existing) SHALL set a nullable one-shot `ProfileUiState.openChat`. It carries the conversation id plus the partner's display identity (`username`, `displayName`), snapshotted from the loaded profile when the call resolves, so consuming it never depends on the current phase. The screen SHALL consume it by emitting `onOpenChatThread(conversationId, partnerUsername, partnerDisplayName)` to the host (the same name and shape as the shell's existing thread-push lambda) and then SHALL clear it via `onChatOpened()`. The host (`AppEntryProvider`) SHALL push `ChatThreadRoute(conversationId, partnerUsername, partnerDisplayName)` onto the root back stack. `ProfileScreen` performs no navigation itself. A `Ready` that resolves after a block from the same profile has already succeeded (the block navigate-back is pending) SHALL NOT open the chat; the block wins.
- `Blocked` (`403`) SHALL surface the docs-verbatim `chat_send_blocked` copy ("Tidak dapat mengirim pesan ke user ini") and SHALL NOT navigate.
- `RecipientNotFound` (`404`) SHALL surface the existing neutral, direction-less "user unavailable" copy (`profile_action_user_unavailable`), with no block/shadow-ban/deletion hint, and SHALL NOT navigate.
- `SelfConversation`, `Error`, `NetworkError`, and `SessionExpired` SHALL surface the generic `profile_action_failed` copy and SHALL NOT navigate. A terminal `401` is additionally re-routed by the `Auth` plugin.

Messages SHALL use the existing nullable `ProfileUiState.message` one-shot (no `Channel`/`SharedFlow`). While a create-or-return is in flight (`ProfileUiState.isOpeningChat = true`), the action SHALL be disabled and a further activation SHALL NOT issue a second call. The call is also a no-op on the self read or before the profile has loaded. The `ChatThreadRoute` pushed SHALL carry only the conversation id + display identity (no user UUID, per `mobile-chat` § "Chat navigation uses serializable root-stack routes with no PII"), and the target `userId` SHALL NOT be rendered or logged.

`ProfileScreen` SHALL resolve `ChatFlow` fail-safe (`getKoin().getOrNull<ChatFlow>()`, the established `TimelineAds` / `SettingsScreen` idiom). Production always binds `ChatFlow` (`di/MobileModule.kt`). When it is unbound, the "Kirim pesan" action SHALL be absent rather than the screen failing to resolve.

#### Scenario: Ready opens the thread with the profile's display identity

- **GIVEN** an other-user profile (`username = "raka.jkt"`, `displayName = "Raka Pratama"`) and a create-or-return that yields `Ready("c1")`
- **WHEN** "Kirim pesan" is activated
- **THEN** `ChatFlow.createOrReturn` is called once with the profile's resolved `userId` AND `onOpenChatThread("c1", "raka.jkt", "Raka Pratama")` is emitted to the host AND the `openChat` one-shot is cleared after emission (a second activation that resolves the same `"c1"` emits again)

#### Scenario: Existing and new conversations behave identically

- **WHEN** the create-or-return resolves an existing conversation (`200`) and, separately, a new one (`201`)
- **THEN** both collapse to `Ready(conversationId)` AND both open the thread the same way (no user-visible difference)

#### Scenario: Blocked surfaces the send-blocked copy and does not navigate

- **WHEN** the create-or-return yields `Blocked`
- **THEN** the message one-shot is the `chat_send_blocked` copy AND no `onOpenChatThread` is emitted

#### Scenario: Recipient not found surfaces the neutral unavailable copy

- **WHEN** the create-or-return yields `RecipientNotFound`
- **THEN** the message one-shot is the neutral `profile_action_user_unavailable` copy (no block/shadow-ban/deletion hint) AND no `onOpenChatThread` is emitted

#### Scenario: Other failures surface the generic failure copy

- **WHEN** the create-or-return yields `SelfConversation`, `Error`, `NetworkError`, or `SessionExpired`
- **THEN** each maps to the `profile_action_failed` message AND no `onOpenChatThread` is emitted AND `isOpeningChat` returns to false

#### Scenario: In-flight guard prevents a double push

- **GIVEN** a create-or-return that has not yet resolved
- **WHEN** "Kirim pesan" is activated a second time
- **THEN** `ChatFlow.createOrReturn` has been called exactly once AND the action renders disabled while in flight

#### Scenario: A Ready after a successful block does not open the chat

- **GIVEN** a create-or-return that has not yet resolved
- **WHEN** a block from the same profile succeeds (navigate-back pending) AND the create-or-return then resolves `Ready`
- **THEN** no `openChat` one-shot is set (the block's navigate-back wins) AND `isOpeningChat` returns to false

#### Scenario: A not-yet-loaded profile never calls create-or-return

- **GIVEN** a profile that is not loaded (`NotFound` / `NetworkError`)
- **WHEN** the ViewModel's send-message entry is invoked
- **THEN** `ChatFlow.createOrReturn` is NOT called

#### Scenario: Self read never calls create-or-return

- **GIVEN** a loaded self profile (`isSelf = true`)
- **WHEN** the ViewModel's send-message entry is invoked
- **THEN** `ChatFlow.createOrReturn` is NOT called

#### Scenario: Action is absent when ChatFlow is unbound

- **GIVEN** a Koin graph with no `ChatFlow` binding
- **WHEN** an other-user `ProfileScreen` renders
- **THEN** the screen renders normally (identity, follow toggle, kebab) AND no "Kirim pesan" action is present
