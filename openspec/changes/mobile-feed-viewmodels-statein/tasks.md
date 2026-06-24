# Tasks — mobile-feed-viewmodels-statein (audit 05-#6)

> Each VM follows the SAME mechanical shape: add `uiState` via `combine(_outcome, _isInitialLoad).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …Loading)` delegating to the unchanged pure projection; make the initial-load flag private (drop the public `isInitialLoad` accessor); keep `outcome` + the auxiliary flows public; the screen collects `uiState` (drop the in-composable `remember(outcome, isInitialLoad) { … }`). Behavior-preserving.

## 1. NearbyTimelineViewModel + screen

- [ ] 1.1 In `NearbyTimelineViewModel.kt` add `val uiState: StateFlow<NearbyTimelineUiState> = combine(_outcome, _isInitialLoad) { o, i -> nearbyTimelineUiState(o, i) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyTimelineUiState.Loading)`; remove the public `val isInitialLoad` accessor (keep private `_isInitialLoad`); keep public `outcome`, `isRefreshing`, `selectedRadiusM`, `isPremiumKnown`, `radiusUpsell`, `likeCapRetryAfterSeconds`, `isLoadingMore`, `loadMoreError`. Update the class KDoc to describe the single-`uiState` shape.
- [ ] 1.2 In `NearbyTimelineScreen.kt` replace the `val outcome`/`val isInitialLoad` collects + `uiState = remember(outcome, isInitialLoad) { nearbyTimelineUiState(...) }` with `val uiState by viewModel.uiState.collectAsStateWithLifecycle()` passed through; keep the `isRefreshing`/footer/dialog/radius collects.

## 2. GlobalTimelineViewModel + screen

- [ ] 2.1 In `GlobalTimelineViewModel.kt` add the `uiState` `stateIn` flow delegating to `globalTimelineUiState`; remove the public `isInitialLoad` accessor; keep `outcome`/`isRefreshing`/`likeCapRetryAfterSeconds`/`isLoadingMore`/`loadMoreError`. Update the KDoc.
- [ ] 2.2 In `GlobalTimelineScreen.kt` collect the single `uiState` via `collectAsStateWithLifecycle()`; drop the in-composable projection.

## 3. FollowingTimelineViewModel + screen

- [ ] 3.1 In `FollowingTimelineViewModel.kt` add the `uiState` `stateIn` flow delegating to `followingTimelineUiState`; remove the public `isInitialLoad` accessor; keep the auxiliary flows. Update the KDoc.
- [ ] 3.2 In `FollowingTimelineScreen.kt` collect the single `uiState` via `collectAsStateWithLifecycle()`; drop the in-composable projection.

## 4. NotificationsViewModel + screen

- [ ] 4.1 In `NotificationsViewModel.kt` add the `uiState` `stateIn` flow delegating to `notificationsUiState`; remove the public `isInitialLoad` accessor; keep `outcome`/`isRefreshing`/`isLoadingMore`/`loadMoreError`/`pendingNavTarget`/`postUnavailable`/`resolvingRowId`. Update the KDoc.
- [ ] 4.2 In `NotificationsScreen.kt` collect the single `uiState` via `collectAsStateWithLifecycle()`; drop the in-composable projection.

## 5. ViewModel test migration (the going-private `isInitialLoad` reads → `uiState`)

- [ ] 5.1 `NearbyTimelineViewModelTest`: migrate the 3 `isInitialLoad` reads to `uiState` assertions (`uiState.value is/!is Loading`) using a `backgroundScope.launch { viewModel.uiState.collect {} }` collector; keep the `.outcome` reads (cursor/like/radius assertions) as-is.
- [ ] 5.2 `GlobalTimelineViewModelTest`: migrate the 3 `isInitialLoad` reads to `uiState` assertions with a `backgroundScope` collector; keep the `.outcome` reads.
- [ ] 5.3 `FollowingTimelineViewModelTest`: migrate the 3 `isInitialLoad` reads to `uiState` assertions with a `backgroundScope` collector; keep the `.outcome` reads.
- [ ] 5.4 `NotificationsViewModelTest` + `NotificationsViewModelNavTest`: migrate the `isInitialLoad` read to a `uiState` assertion with a `backgroundScope` collector; keep the `.outcome` reads.

## 6. New uiState scenario coverage (per spec deltas)

- [ ] 6.1 Add to each of `NearbyTimelineViewModelTest` / `GlobalTimelineViewModelTest` / `FollowingTimelineViewModelTest` / `NotificationsViewModelTest`: a "uiState delegates to the pure projection" test (`uiState.value == xxxTimelineUiState(outcome, isInitialLoad)` for a held Loaded) and a "config-change proxy" test (a fresh `backgroundScope` collector on the same VM still sees `Content`), mirroring `SignInViewModelTest`.
- [ ] 6.2 Confirm the existing pure-projection unit tests (`NearbyTimelineUiStateTest`, `GlobalTimelineUiStateTest`, `FollowingTimelineUiStateTest`, `NotificationsUiStateTest`) still pass unchanged (the projection function is reused, not modified).
- [ ] 6.3 Confirm the Robolectric screen tests (`NearbyTimelineScreenTest`, `NotificationsScreenNavFreeScanTest`, `ShellAndTimelineSourceGuardTest`, plus any Global/Following screen tests) render the same states with the `uiState`-collecting screens (fix only collection-shape references if a test inspects the screen's flow wiring).

## 7. Spec sync + gate + verification

- [ ] 7.1 `openspec validate mobile-feed-viewmodels-statein --strict` passes.
- [ ] 7.2 Pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + `:mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` all green (do NOT run while a local `:backend:ktor:run` is alive).
- [ ] 7.3 `./gradlew :mobile:app:iosSimulatorArm64Test` green (the VMs are commonMain → compile to K/N).
- [ ] 7.4 Manual verification (verify-loop §B/§C): launch the app on an emulator/device and confirm the four feeds (Nearby / Following / Global / Notifications) render their loading → content → refresh → empty/error states identically to `main` (behavior-preserving) — capture screenshot evidence in the PR body.
