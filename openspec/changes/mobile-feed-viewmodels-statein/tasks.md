# Tasks — mobile-feed-viewmodels-statein (audit 05-#6)

> Each VM follows the SAME mechanical shape: add `uiState` via `combine(_outcome, _isInitialLoad).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …Loading)` delegating to the unchanged pure projection; make the initial-load flag private (drop the public `isInitialLoad` accessor); keep `outcome` + the auxiliary flows public; the screen collects `uiState` (drop the in-composable `remember(outcome, isInitialLoad) { … }`). Behavior-preserving.

## 1. NearbyTimelineViewModel + screen

- [x] 1.1 In `NearbyTimelineViewModel.kt` add `val uiState: StateFlow<NearbyTimelineUiState> = combine(_outcome, _isInitialLoad) { o, i -> nearbyTimelineUiState(o, i) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyTimelineUiState.Loading)`; remove the public `val isInitialLoad` accessor (keep private `_isInitialLoad`); keep public `outcome`, `isRefreshing`, `selectedRadiusM`, `isPremiumKnown`, `radiusUpsell`, `likeCapRetryAfterSeconds`, `isLoadingMore`, `loadMoreError`. Update the class KDoc to describe the single-`uiState` shape.
- [x] 1.2 In `NearbyTimelineScreen.kt` replace the `val outcome`/`val isInitialLoad` collects + `uiState = remember(outcome, isInitialLoad) { nearbyTimelineUiState(...) }` with `val uiState by viewModel.uiState.collectAsStateWithLifecycle()` passed through; keep the `isRefreshing`/footer/dialog/radius collects.

## 2. GlobalTimelineViewModel + screen

- [x] 2.1 In `GlobalTimelineViewModel.kt` add the `uiState` `stateIn` flow delegating to `globalTimelineUiState`; remove the public `isInitialLoad` accessor; keep `outcome`/`isRefreshing`/`likeCapRetryAfterSeconds`/`isLoadingMore`/`loadMoreError`. Update the KDoc.
- [x] 2.2 In `GlobalTimelineScreen.kt` collect the single `uiState` via `collectAsStateWithLifecycle()`; drop the in-composable projection.

## 3. FollowingTimelineViewModel + screen

- [x] 3.1 In `FollowingTimelineViewModel.kt` add the `uiState` `stateIn` flow delegating to `followingTimelineUiState`; remove the public `isInitialLoad` accessor; keep the auxiliary flows. Update the KDoc.
- [x] 3.2 In `FollowingTimelineScreen.kt` collect the single `uiState` via `collectAsStateWithLifecycle()`; drop the in-composable projection.

## 4. NotificationsViewModel + screen

- [x] 4.1 In `NotificationsViewModel.kt` add the `uiState` `stateIn` flow delegating to `notificationsUiState`; remove the public `isInitialLoad` accessor; keep `outcome`/`isRefreshing`/`isLoadingMore`/`loadMoreError`/`pendingNavTarget`/`postUnavailable`/`resolvingRowId`. Update the KDoc.
- [x] 4.2 In `NotificationsScreen.kt` collect the single `uiState` via `collectAsStateWithLifecycle()`; drop the in-composable projection.

## 5. ViewModel test migration (the going-private `isInitialLoad` reads → `uiState`)

- [x] 5.1 `NearbyTimelineViewModelTest`: migrate the 3 `isInitialLoad` reads to `uiState` assertions (`uiState.value is/!is Loading`) using a `backgroundScope.launch { viewModel.uiState.collect {} }` collector; keep the `.outcome` reads (cursor/like/radius assertions) as-is.
- [x] 5.2 `GlobalTimelineViewModelTest`: migrate the 3 `isInitialLoad` reads to `uiState` assertions with a `backgroundScope` collector; keep the `.outcome` reads.
- [x] 5.3 `FollowingTimelineViewModelTest`: migrate the 3 `isInitialLoad` reads to `uiState` assertions with a `backgroundScope` collector; keep the `.outcome` reads.
- [x] 5.4 `NotificationsViewModelTest`: migrate its 1 `isInitialLoad` read to a `uiState` assertion with a `backgroundScope` collector; keep its `.outcome` reads. `NotificationsViewModelNavTest` has NO `isInitialLoad` read — its 3 `.outcome` reads stay (no accessor migration), but confirm it still compiles against the new VM surface.

## 6. New uiState scenario coverage (per spec deltas)

- [x] 6.1 Add to each of `NearbyTimelineViewModelTest` / `GlobalTimelineViewModelTest` / `FollowingTimelineViewModelTest` / `NotificationsViewModelTest`: a "uiState delegates to the pure projection" test (`uiState.value == xxxTimelineUiState(outcome, isInitialLoad)` for a held Loaded) and a "config-change proxy" test (a fresh `backgroundScope` collector on the same VM still sees `Content`), mirroring `SignInViewModelTest`.
- [x] 6.2 Confirm the existing pure-projection unit tests (`NearbyTimelineUiStateTest`, `GlobalTimelineUiStateTest`, `FollowingTimelineUiStateTest`, `NotificationsUiStateTest`) still pass unchanged (the projection function is reused, not modified).
- [x] 6.3 Confirm the Robolectric screen tests (`NearbyTimelineScreenTest`, `NotificationsScreenNavFreeScanTest`, `ShellAndTimelineSourceGuardTest`, plus any Global/Following screen tests) render the same states with the `uiState`-collecting screens (fix only collection-shape references if a test inspects the screen's flow wiring).

## 7. Spec sync + gate + verification

- [x] 7.1 `openspec validate mobile-feed-viewmodels-statein --strict` passes.
- [x] 7.2 Pre-push gate: `ktlintCheck` ✓, `detekt` ✓, `:lint:detekt-rules:test` ✓, `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` ✓. `:backend:ktor:test` deferred to CI (mobile-commonMain-only change, zero backend files touched, local DB not migrated).
- [x] 7.3 `./gradlew :mobile:app:iosSimulatorArm64Test`: K/N compile ✓ + the migrated/new commonTest VM tests pass on iOS. The 36 `*FlowIosTest` failures are PRE-EXISTING DI-drift (proven by a base-branch run: same 14 timeline `LikeFlow`-missing failures; +22 the #348 baseline) — iOS is never CI-gated, and this change touches no DI.
- [ ] 7.4 Manual verification (verify-loop §C): launch on the emulator/device and confirm the four feeds (Nearby / Following / Global / Notifications) render loading → content → refresh identically to `main` — screenshot evidence in the PR body. (Supporting evidence already in hand: the Robolectric `*ScreenTest` suite renders the real screens over the new single-`uiState` collection and passes; the single-`stateIn` + `collectAsStateWithLifecycle` pattern is device-shipped on `SignInViewModel`/`FollowListViewModel`.)
