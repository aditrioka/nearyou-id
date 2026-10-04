## 1. Shared by-id post resolution (design D8)

- [x] 1.1 Move `PostTargetResolution` from `notifications/NotificationsFlow.kt` to a new `post/PostTargetResolution.kt` (same shape) + add the shared `SinglePostFullResult.toPostTargetResolution()` mapping there; reword its KDoc to be consumer-neutral (notifications show "tidak tersedia" and don't navigate on `Unavailable`; search falls back)
- [x] 1.2 `NotificationsRepository.resolvePostTarget` calls the shared mapping (its type-only `Unavailable` diagnostic kept); `NotificationsFlow` + notification fakes/tests update imports only
- [x] 1.3 Move `Resolved → PostDetailTarget` out of `NotificationNavTargetResolver` (private) into a public `PostTargetResolution.Resolved.toPostDetailTarget()` next to `PostDetailTarget` in `screens/home/HomeScreen.kt`; the notification resolver calls it
- [x] 1.4 Refresh the now-stale KDoc: `SinglePostApiClient.fetchFullPost` / `SinglePostFullResult` ("no-card consumer" → also the search tap; failure semantics are per consumer), `PostDetailTarget.imageUrl` (search now passes the by-id `imageUrl`)

## 2. Search data seam (#255, design D6/D7)

- [x] 2.1 `SearchFlow` gains `suspend fun resolvePost(postId: String): PostTargetResolution`
- [x] 2.2 `SearchRepository` takes `SinglePostApiClient` and implements `resolvePost` via `fetchFullPost` + the shared mapping (type-only diagnostic on `Unavailable`); `MobileModule` passes the shared `SinglePostApiClient` single
- [x] 2.3 `FakeSearchFlow` gains a programmable `resolvePost` (outcome per id, optional per-id suspend gate, optional throw, recorded ids)

## 3. SearchViewModel (#253 + #255, design D1–D7, D9, D12)

- [x] 3.1 `searchUiState(...)` gains the explicit `viewerKnownFree: Boolean` input: a below-2 query projects to `PremiumGate` when `true`, `Idle` otherwise; eligible queries unchanged
- [x] 3.2 `SearchScreenUiState(query, surface, resolvingPostId, pendingNavTarget)`; `SearchViewModel` holds one private `MutableStateFlow<VmState>` → `.map { toUiState() }.stateIn(WhileSubscribed(5_000), state.value.toUiState())` (the `UsernameCustomizationViewModel` shape); the raw `outcome` stays exposed as the white-box seam
- [x] 3.3 Constructor gains `ProfileFlow` + `SelfUserIdProvider`; on-entry self read → tier `isPremium || purchaseConfirmed.value`; null id / non-`Loaded` → not-known-Free; tier writes are one-way toward Premium (a later read never downgrades `true`)
- [x] 3.4 A first-page `Results` / `RateLimited` marks the tier known Premium; a `403` never marks it Free
- [x] 3.5 The existing `purchaseConfirmed` collector also marks the tier known Premium (the once-only `PremiumGate` re-run untouched)
- [x] 3.6 `onResultTap(hit)`: ignored while `pendingNavTarget != null`; cancel prior → `resolvingPostId = hit.postId` → `flow.resolvePost` (non-cancellation throw → fallback) → `pendingNavTarget` = `Resolved.toPostDetailTarget()` or the v1 fallback target; `resolvingPostId` cleared only by the latest job
- [x] 3.7 Every new search start (edit / submit / clear / retry) cancels an in-flight resolution and clears `resolvingPostId`; `onNavConsumed()` clears the pending target AND cancels any in-flight resolution

## 4. Screen + host (design D2, D6, D10, D11)

- [x] 4.1 `SearchScreen`: inject `ProfileFlow` + `SelfUserIdProvider`; collect the single `uiState`; `LaunchedEffect(pendingNavTarget)` → `onOpenPost(target)` + `onNavConsumed()`; `DisposableEffect` → `onNavConsumed()` on dispose; `onOpenPost: (PostDetailTarget) -> Unit`; card tap → `viewModel::onResultTap`
- [x] 4.2 `SearchResultCard` gains `isResolving` → a 16 dp / 2 dp-stroke `CircularProgressIndicator` tagged `SEARCH_RESULT_RESOLVING_TAG` (the notification-row idiom); no new strings
- [x] 4.3 Move the non-result state composables (`LoadingState`, `CenteredMessage`, `ErrorState`, `PremiumGateState`, `RateLimitedState`) unchanged to `screens/search/SearchStates.kt` (`internal`) — pure move (D11)
- [x] 4.4 `screens/routing/`: `internal fun PostDetailTarget.toRoute(focusReplyComposer: Boolean = false)`; used by the `AppEntryProvider` Home open / reply-shortcut pushes, the search entry (`onOpenPost = { backStack.add(it.toRoute()) }`), and `PushTapNavigationEffect`'s post push; refresh the search entry comment and the chat `onOpenSharedPost` comment ("same nav-arg pattern … search-result tap" no longer true)
- [x] 4.5 Do NOT touch `PostDetailScreen.kt` (concurrent owner); record "no edit" in the PR body

## 5. Tests (one per spec'd scenario)

- [x] 5.1 commonTest `SearchUiStateTest`: existing cases pass `viewerKnownFree = false`; new: `true` → empty/`"a"` = `PremiumGate`, `"jakarta"` + Results = `Results`
- [x] 5.2 commonTest `SearchViewModelTest`. Every `uiState` assertion runs with an active collector, and every Idle-from-read assertion is paired with `FakeProfileFlow.loadCalls` (1, or 0 for a null id) and a Free positive control.
  - **On-entry tier:**
    - Free → `PremiumGate`, with zero searches;
    - Premium → `Idle`;
    - pending / `NetworkError` / null self id → `Idle`.
  - **Server evidence:**
    - a Free viewer's `Results` then clear → `Idle`;
    - a Free viewer's `RateLimited` then clear → `Idle`;
    - a Free viewer's `403` then clear → `PremiumGate`;
    - `Results` before a late Free read, then clear → `Idle`.
  - **Purchase:**
    - confirmed while on-entry gated → `Idle`, with no request;
    - confirmed before entry with a Free read → `Idle`;
    - confirmed while the read is in flight, then a Free read → `Idle`.
  - **Taps:**
    - `Resolved` → the hydrated target (`distanceM = null`);
    - `Unavailable` → the fallback defaults;
    - a throwing read → the fallback, no crash;
    - an in-flight tap exposes `resolvingPostId`.
  - **Supersede:** record every non-null `pendingNavTarget`, release `p1` before `p2`, and expect exactly `[p2]`.
  - **Cancellation:** a query change mid-resolution → no target and the resolving id cleared; a tap while a target is pending → ignored; `onNavConsumed` clears the target and cancels.
  - **Compile fixes for existing tests:** add `resolvePost` to the two anonymous `object : SearchFlow`, and replace `vm.query.value` with `uiState.value.query`. All existing tests stay green.
- [x] 5.3 commonTest `SearchRepositoryTest`:
  - the helper constructs `SearchRepository(SearchApiClient(client), SinglePostApiClient(client))`;
  - new `resolvePost` (MockEngine): a `200` mixed-case full body → `Resolved` (snake `city_name` / `liked_by_viewer` / `reply_count`, bare `imageUrl`);
  - `404` → `Unavailable`.
- [x] 5.4 Robolectric `SearchScreenTest`. Koin binds `ProfileFlow` (Premium by default) + `SelfUserIdProvider`.
  - **New tests:**
    - a Free read → the upsell body + CTA render before typing, the Idle prompt is absent, zero searches;
    - a Premium read → the Idle prompt and no gate body;
    - the tap → `onOpenPost` gets the hydrated target exactly once, even after a forced recomposition;
    - an in-flight tap shows `SEARCH_RESULT_RESOLVING_TAG`;
    - a typed query renders no typeahead, and only `search(...)` is called.
  - **Host push:** under `TestNavHost` with the real `appEntryProvider`, a tap pushes `PostDetailRoute` with the hydrated fields. Bind PostDetail's Koin graph the way `HomeTabHostScreenTest` does, and re-run this after rebasing onto the concurrent PostDetail change.
  - **Updated:** the existing payload test asserts the `Unavailable` fallback defaults.
- [x] 5.5 iosTest `SearchFlowIosTest` (K/N-legal names): bind a Premium `single<ProfileFlow>` override so the existing three stay green; new `onEntry_freeViewer_showsUpsellBeforeTyping`
- [x] 5.6 Notification tests (`NotificationsViewModelNavTest`, `NotificationsScreenNavTest`, `FakeNotificationsFlow`, push-tap tests): imports only; all green unchanged in behavior
- [x] 5.7 The `mobile-post-detail` search-entry scenarios ("builds the route from the full projection", "failed lookup still opens from the hit") are backed by 5.2 (VM target) + 5.4 (host push)

## 6. Docs

- [x] 6.1 `docs/11-Engineering-Standards.md` §2.2 known-debt sentence: drop `SearchViewModel` (now one `uiState`); count 10 → 9
- [x] 6.2 Confirm `docs/03-UX-Design.md` § Search UX needs no edit ("Free users see an upsell on tap" now true); no new strings, so no `strings.xml` change

## 7. Verification & lifecycle

- [x] 7.1 Gate (docs/13): `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (throwaway Postgres on :5434 if the dev DB is dirty) + `:mobile:app:iosSimulatorArm64Test`
- [x] 7.2 Manual verify (verify-loop §B/§C) on the local Android emulator AND the iOS simulator (docs/11 §5.3), against a stub backend serving the real wires:
  - a Free self read → the upsell on entry;
  - Premium → Idle;
  - a result tap → the detail opens with the real city, like state, reply count and image;
  - a `404` by-id read → the fallback opens.
  - Screenshots go in the PR body (docs/11 §5 DoD).
- [ ] 7.3 PR title/body current at each phase boundary; body carries `Closes #253` and `Closes #255` on separate lines; archive via `/opsx:archive`, then hand-edit the now-stale `## Purpose` of `mobile-search` (on-entry gate + by-id hydration), since deltas cannot modify Purpose
