## 1. Pre-flight

- [x] 1.1 Confirm no substrate change: this reuses the existing Ktor client, NavKeys, and `SinglePostApiClient` — no `gradle/libs.versions.toml` edit, so the pre-implementation library re-check is N/A (note it in the first feat commit body).
- [x] 1.2 Re-confirm the destination NavKeys + the shell's hoisted callback signatures are unchanged on the branch base (`PostDetailRoute`, `ProfileRoute(userId)`, `ChatThreadRoute(conversationId, …)`, `AppShellScreen.onOpenPost/onOpenProfile`, `PostDetailTarget`).

## 2. Full-projection single-post fetch (data layer — design D2)

- [x] 2.1 Extend `post/SinglePostApiClient.kt` with a full-projection read of `GET /api/v1/posts/{postId}`: a new `@Serializable` DTO decoding the deployed `SinglePostResponse` wire in its **MIXED case** — bare camelCase `id`/`authorUsername`/`authorDisplayName`/`content`/`createdAt`, and `@SerialName("city_name")`/`@SerialName("liked_by_viewer")`/`@SerialName("reply_count")` for `cityName`/`likedByViewer`/`replyCount` (verified against `backend/.../post/SinglePostRoutes.kt`; NO author UUID / coordinate). Distinct from the existing minimal `SinglePostDto` (leave that untouched). DO NOT use all-camelCase keys for the three snake fields — that silently mis-parses on the real wire.
- [x] 2.2 Add a `fetchFullPost(postId)` method returning a sealed result (`Success(PostDetailTarget)` / `Unavailable`): map the full-projection DTO → `PostDetailTarget(distanceM = null, …)`; `200` → `Success`, `404`/non-200/IO → `Unavailable`; rethrow `CancellationException`; never log (no-PII). Mirror the existing `SinglePostApiResult` discipline.
- [x] 2.3 Wire/confirm Koin: `SinglePostApiClient` AND the shipped `ProfileApiClient` (reused as-is for the chat partner-identity read) are available to the `NotificationsViewModel` seam (bindings in `di/MobileModule.kt`); keep test seams (interfaces/fakes) so commonTest can drive `Success`/`Unavailable` and the partner read without a backend.

## 3. NotificationsViewModel — per-type resolution + nav events (design D3, D4, D6)

- [x] 3.1 Add a pure resolver mapping `(type, target_type, target_id, actor_user_id, body_data)` → a typed nav intent: `Post(target_id)`, `Profile(actor_user_id)`, `ChatMessage(conversationId, actorUserId)`, or `None` (`chat_message_redacted` [actor NULL] + reply-target + informational + unknown/missing-field). Unit-testable, PII-free.
- [x] 3.2 On row tap: keep the unchanged optimistic mark-read; independently resolve the nav intent. For `Post`, run `fetchFullPost(target_id)` with a per-tap "resolving" indicator (supersede/cancel an in-flight resolution when another row is tapped) → `Success` sets the consumed-once `pendingNavTarget` to `OpenPost(PostDetailTarget)`; `Unavailable` sets a transient non-blocking "Postingan tidak tersedia" affordance and sets NO nav target (the row is still marked read).
- [x] 3.3 For `ChatMessage`, fetch the partner via `ProfileApiClient.getProfile(actorUserId)` (the sender = the 1:1 partner) → set `pendingNavTarget` to `OpenChatThread(conversationId, username, displayName)`; on profile-fetch failure set `OpenChatThread(conversationId, "", "")` (the conversation is valid; the top bar degrades to its blank-name placeholder). For `Profile`, set `OpenProfile(actor_user_id)` with no fetch.
- [x] 3.3a Expose `pendingNavTarget` as a **nullable, consumed-once field on the `StateFlow` UiState** cleared by a VM `onNavConsumed()` callback — the established `EditPostUiState` one-shot-signal pattern; **NO `Channel`/`SharedFlow`** (forbidden by docs/11 § 2.2) — so navigation does not re-fire on recomposition.
- [x] 3.4 Keep `NotificationsUiState` Compose-free and PII-free (no `actor_user_id`/`target_id`/`conversation_id` in state or diagnostics); the transient unavailable affordance carries no PII.

## 4. NotificationsScreen — hoisted callbacks + collect events + unavailable affordance (spec: hoisted-callbacks requirement)

- [x] 4.1 Add `onOpenPost: (PostDetailTarget) -> Unit`, `onOpenProfile: (userId: String) -> Unit`, `onOpenChatThread: (conversationId: String, partnerUsername: String, partnerDisplayName: String) -> Unit` params to `NotificationsScreen` (default no-op for test ergonomics); keep the screen navigation-free.
- [x] 4.2 Observe the VM's nullable `pendingNavTarget`; on a non-null value invoke the matching hoisted callback (`onOpenPost`/`onOpenProfile`/`onOpenChatThread`) exactly once, then call `onNavConsumed()` to clear it (so it does not re-fire on recomposition) — the `PostDetailScreen` consumed-marker precedent.
- [x] 4.3 Render the per-tap resolving indicator on the tapped row and the transient non-blocking "Postingan tidak tersedia" affordance; all copy via `stringResource(Res.string.*)` (no hardcoded UI strings) — add the new string(s) to `:shared:resources`.

## 5. Shell + AppEntryProvider wiring (spec: hoisted-callbacks requirement; design D3)

- [x] 5.1 In `screens/shell/AppShellScreen.kt`, stop invoking `NotificationsScreen()` bare: forward the existing `onOpenPost` / `onOpenProfile`, and pass a new `onOpenChatThread` callback.
- [x] 5.2 In `screens/routing/AppEntryProvider.kt`, wire the notifications `onOpenChatThread(conversationId, partnerUsername, partnerDisplayName)` to a `ChatThreadRoute(conversationId, partnerUsername, partnerDisplayName)` root-stack push (reuse the chat-list row's existing push seam); confirm `onOpenPost`/`onOpenProfile` reuse the shipped `PostDetailRoute`/`ProfileRoute` pushes. Declare NO new `NavKey`.

## 6. Tests (docs/11 §5 DoD)

- [ ] 6.1 `SinglePostApiClient` full-projection MockEngine test: path `/api/v1/posts/{id}`, **mixed-case** parse → `PostDetailTarget(distanceM = null)` (assert `cityName`/`likedByViewer`/`replyCount` populate from the `city_name`/`liked_by_viewer`/`reply_count` keys) PLUS an **all-camelCase regression guard** (those three do NOT bind from camelCase keys), `200`→`Success`, `404`/`500`/IO→`Unavailable`, `CancellationException` rethrown; assert the minimal projection is undisturbed.
- [ ] 6.2 commonTest `NotificationsViewModel` nav-resolution tests over `FakeNotificationsFlow` + fakes for the post fetch and the partner-profile fetch: each type → correct intent; `Post` success → `OpenPost`; `Post` `Unavailable` → unavailable affordance + no nav + row still marked read; `chat_message` → partner fetch then `OpenChatThread(conversationId, username, displayName)`; `chat_message` partner-fetch failure → `OpenChatThread(conversationId, "", "")`; `followed` → `OpenProfile` (no fetch); `chat_message_redacted` (actor NULL) + reply-target + informational + unknown/missing-`conversation_id` → no nav; a second tap supersedes an in-flight resolution (only the second navigates); mark-read still fires on every tap.
- [ ] 6.3 Robolectric `NotificationsScreenTest` (androidUnitTest): per-type tap → callback assertions (post→`onOpenPost`, followed→`onOpenProfile`, chat_message→`onOpenChatThread` with resolved partner fields, informational + reply-target + `chat_message_redacted` → no callback), 404 → unavailable affordance + no callback, the no-UUID-in-tree PII assertion still holds, one-shot nav fires once across recomposition. Add the test to the Release-variant test-exclude list in `mobile/app/build.gradle.kts` (the `*ScreenTest` convention).
- [ ] 6.4 iOS flow test under `mobile/app/src/iosTest/...` mirroring the existing notifications iOS flow test (Kotlin/Native-legal function names) exercising a post-target tap → nav callback on the simulator.
- [ ] 6.5 Remove (or rewrite) the now-obsolete `mobile/app/src/androidUnitTest/.../NotificationsDeepLinkAbsenceScanTest.kt` — its "deep-link tap-through deferred (#193)" / no-`PostDetailRoute` contract is exactly what this change inverts; its negative guard is superseded by the new positive `NotificationsScreenTest` nav assertions. The screen stays navigation-free (hoisted callbacks, no `NavKey` in the screen source), so keep any still-valid "screen holds no back-stack ref" assertion if rewriting; otherwise delete.

## 7. Verification (pre-archive gates)

- [ ] 7.1 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green.
- [ ] 7.2 Run the flavor-qualified mobile unit tests locally: `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` green (mobile unit tests are local-only; CI mobile is the device-run APK build).
- [ ] 7.3 verify-loop bring-up (UI-affecting change): launch the app, tap a `post_liked` / `followed` / `chat_message` notification, confirm it navigates to post detail / profile / chat thread, and a post-unavailable tap shows the non-blocking affordance with no navigation. Capture screenshot evidence into the PR body before archive (docs/11 §5 DoD).
- [ ] 7.4 `openspec validate mobile-notifications-deep-link-targets --strict` green; archive-phase `openspec validate --specs mobile-notifications-list --strict` green.

## 8. Bookkeeping

- [ ] 8.1 On the first feat commit, retitle the PR to `feat(mobile): mobile-notifications-deep-link-targets …` and refresh the body (in-progress shape) per the same-PR convention.
- [ ] 8.2 On archive, close follow-up issue [#193](https://github.com/aditrioka/nearyou-id/issues/193) and file the deferred cases (reply-target deep-linking + actor-less `chat_message_redacted` deep-linking) as a new `follow-up` issue (label `follow-up` + `mobile`) so the "Actor-less and reply-target deep-linking is deferred" negative-guard requirement has a tracked home.
