# Design: profile-send-message

## Context

Every layer of create-or-return is already shipped. The backend `POST /api/v1/conversations` (`chat-conversations`) is idempotent: `201` new, `200` existing, `403` block in either direction, `400` self, `404` unknown recipient, and no per-endpoint rate limit. On mobile, `ConversationsApiClient.createOrReturnConversation` → `ChatRepository.createOrReturn` → `CreateConversationOutcome`, bound as `single<ChatFlow>` in `di/MobileModule.kt`. `ChatThreadRoute(conversationId, partnerUsername, partnerDisplayName)` is shipped and already pushed from three hosts (conversation list, notification deep-link, share-to-chat picker).

`mobile-chat-screen` D5 left a placeholder `ChatThreadViewModel.startConversation(...)`, but it can never be called: that VM is constructed for an existing `ChatThreadRoute` and loads that conversation's history in `init`. The other-user `ProfileScreen` (`mobile-profile`) already has the resolved target id (`ProfileViewModel.resolvedUserId`), the display identity (`UserProfile.username` / `displayName`), a nullable-one-shot `message` → snackbar pipeline, and a hoisted-lambda navigation convention (`onOpenFollowList`). The only missing piece is the caller.

## Goals / Non-Goals

**Goals:**
- "Kirim pesan" on the other-user profile → create-or-return → `ChatThreadRoute`, with every `CreateConversationOutcome` mapped to user-visible feedback.
- No double navigation on a double-tap.
- Zero route, DI-module, backend, or wire change. PII disciplines untouched.

**Non-Goals:**
- No chat entry from anywhere else (feed cards, follow-list rows, post-detail). The post-embed share path is the separate, shipped `ConversationPickerRoute`.
- No gating on follow state or private profile. The backend permits DMs to any existing, unblocked user, and `private-profile` explicitly leaves chat un-hidden.
- No optimistic "open an empty thread before the id resolves". The thread needs a real conversation id to load history and subscribe.

## Decisions

- **D1: The caller is `ProfileViewModel`, via the `ChatFlow` seam; the thread-VM placeholder is deleted.** `ProfileViewModel` gains a `chatFlow: ChatFlow?` constructor param (defaulted `null`, the last param) and `onSendMessage()`. Alternative: reuse `ChatThreadViewModel.startConversation` from the profile. Rejected because it would require building a thread VM without a conversation, which violates its route-keyed construction and triggers a history load. The placeholder has no call sites or tests, so leaving it would keep a second, dead create-or-return entry point (Pattern-Registry "one concept, one place"). A dedicated narrower seam (for example a `ConversationStarter` fun-interface) was also rejected: `ChatFlow` already exposes exactly this method, and a one-implementation interface is speculative.
- **D2: Fail-safe `ChatFlow` resolution at the screen (`getKoin().getOrNull<ChatFlow>()`).** This is the established idiom (`TimelineAds`, `SettingsScreen` logout/preview bindings): a new dependency inside an already-tested composable must not throw `NoDefinitionFound` in the ~14 existing Robolectric/iOS tests that render `ProfileScreen` (directly or via the shell) with a minimal Koin module. Production always binds it. When it is `null`, the screen passes a `null` `onSendMessage` and the button is not rendered (the nullable-callback idiom from `post-detail-tap-to-profile` D2). Alternative: `koinInject<ChatFlow>()` plus a `FakeChatFlow` binding in ~14 unrelated test modules. Rejected as mechanical churn in files unrelated to this change, and the codebase has already chosen the fail-safe idiom twice for this exact situation.
- **D3: Navigation is a nullable one-shot state field, emitted to the host.** `ProfileUiState.openChatConversationId: String?` is set on `Ready` and cleared via `onChatOpened()`. It mirrors `navigateBack` / `onNavigatedBack()` (docs/11 §2.2: one-shot events are state, not streams). The screen's `LaunchedEffect` reads the display identity off the loaded `ProfilePhase.Content` profile and calls the hoisted `onOpenChat(conversationId, username, displayName)`. `AppEntryProvider`'s `ProfileRoute` entry wires that to `backStack.add(ChatThreadRoute(...))`. The route carries display identity only, and the partner UUID never enters the serialized back stack. Alternative: carry `username` / `displayName` in the one-shot. Rejected because the loaded profile already holds them, so duplicating them only risks drift.
- **D4: Outcome → message mapping reuses existing copy.** `Blocked` → a new `ProfileMessage.CHAT_BLOCKED` mapped to the existing `chat_send_blocked` string, the docs/02 + `chat-conversations` verbatim 403 copy, so no new string is needed. `RecipientNotFound` → the existing `TARGET_UNAVAILABLE` (neutral, the follow-`POST` 404 precedent). `SelfConversation` / `Error` / `NetworkError` / `SessionExpired` → the existing `ACTION_FAILED`, the same bucket the share-to-chat picker uses (`ChatShareResult.Failed`). `SelfConversation` is unreachable from the UI (the button is hidden on self), but the `when` stays exhaustive. The one new string is the button label `profile_send_message` = "Kirim pesan", a surface-scoped key per the `profile_*` convention. `chat_send`, the thread's send-button content description, shares the copy but not the role, so it is not reused.
- **D5: In-flight guard in the VM, reflected in the UI.** `isOpeningChat` is set synchronously before the launch, and `onSendMessage()` early-returns while it is true (the `isFollowInFlight` precedent). The button renders `enabled = !isOpeningChat`. The endpoint is idempotent, but two resolved `Ready`s would push two thread routes.
- **D6: Button placement and style.** An `OutlinedButton` labelled "Kirim pesan" sits in the existing other-user action `Row`, between the follow toggle and the kebab. The follow toggle stays the primary CTA (filled "Ikuti" / outlined "Mengikuti"), and messaging is secondary. No other-user profile frame exists on the mockup board, so the frame-3 profile layout (one full-width tonal action under the identity block) governs the look. The shipped profile's compact centered action row is kept rather than re-laid-out, because that re-layout is the separate `mobile-mockup-visual-conformance` concern. M3 buttons meet the 48dp minimum interactive size.

## Standards conformance

- **State holder (docs/11 §2.2)**: the androidx `ViewModel` exposes one `stateIn(WhileSubscribed)` `StateFlow<ProfileUiState>`. New one-shots are nullable fields cleared via callbacks, with no `Channel`/`SharedFlow`. The pure `profileUiState(...)` projection gains two defaulted params.
- **Navigation (§2.3)**: the screen stays navigation-free (hoisted lambda), and the host pushes an already-registered, serializable `ChatThreadRoute`. No new `NavKey`.
- **Data layer (§2.6)**: VM → `ChatFlow` (repository seam) → ApiClient. The sealed `CreateConversationOutcome` is mapped exhaustively.
- **DI**: reuses the existing `ChatFlow` binding. The fail-safe `getOrNull` screen resolution is the existing idiom, not a new pattern.
- No Pattern-Registry deviation, so no docs/11 amendment is needed.

## Cross-layer scope (docs/12)

- **Backend**: shipped (`chat-conversations` § Create-or-return endpoint). No change.
- **Admin**: none. There is no admin counterpart for a user starting a DM.
- **Mobile**: this change (the caller). The ApiClient/Repository/`ChatThreadRoute` are already shipped.

The vertical slice is complete, and no layer is deferred.

## Risks / Trade-offs

- [Block raced after the profile loaded] → the `403` maps to the send-blocked copy with no navigation. The profile itself 404s on the next read, which is the existing block semantics.
- [`ChatFlow` silently unbound in production would hide the button] → mitigated: `MobileModule` binds it unconditionally, and `ChatReportKoinResolutionTest` already asserts that the real `mobileModule` resolves `ChatFlow`. The fail-safe exists only for minimal test graphs.
- [Recipient deleted between profile load and tap] → the `404` maps to the neutral "Pengguna tidak tersedia." copy, with no crash and no navigation.

## Migration Plan

Pure client addition plus dead-code removal in one PR. Rollback is a revert.

## Open Questions

None.
