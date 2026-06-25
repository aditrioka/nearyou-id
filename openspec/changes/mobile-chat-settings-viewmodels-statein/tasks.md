# Tasks — mobile-chat-settings-viewmodels-statein (audit follow-up #410)

> Both VMs follow the SAME mechanical shape: add `uiState` via `combine(_outcome, initialLoad).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …Loading)` delegating to the unchanged pure projection; make the initial-load flag private (rename `_isInitialLoad`→`initialLoad`, drop the public `isInitialLoad` accessor); keep `outcome` + the auxiliary flows public; the screen collects `uiState` (drop the in-composable `remember(outcome, isInitialLoad) { … }`). Behavior-preserving.

## 1. ConversationListViewModel + screen

- [x] 1.1 In `ConversationListViewModel.kt` add `val uiState: StateFlow<ConversationListUiState> = combine(_outcome, initialLoad) { o, i -> conversationListUiState(o, i) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState.Loading)`; rename `_isInitialLoad`→`initialLoad` and remove the public `val isInitialLoad` accessor (update `reload()`'s guard + `load()`'s `finally`); keep public `outcome` + `isRefreshing`. Update the class KDoc to describe the single-`uiState` shape.
- [x] 1.2 In `ConversationListScreen.kt` replace the `val outcome` / `val isInitialLoad` collects + `uiState = remember(outcome, isInitialLoad) { conversationListUiState(...) }` with `val uiState by viewModel.uiState.collectAsStateWithLifecycle()` passed through; keep the `isRefreshing` collect. Drop the now-unused `remember` import if no longer referenced.

## 2. BlockedUsersViewModel + screen

- [x] 2.1 In `BlockedUsersViewModel.kt` add the `uiState` `stateIn` flow delegating to `blockedUsersUiState`; rename `_isInitialLoad`→`initialLoad` and remove the public `isInitialLoad` accessor (update `reload()`'s guard + `load()`'s `finally`); keep public `outcome` + `isRefreshing` + `unblockError` + `unblocking`. Update the KDoc.
- [x] 2.2 In `BlockedUsersScreen.kt` replace the `val isInitialLoad` collect + `uiState = remember(outcome, isInitialLoad) { blockedUsersUiState(...) }` with `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`; KEEP the `val outcome` collect (the `LaunchedEffect(outcome)` terminal-401 → sign-in side-effect) and the `unblockError`/`unblocking` collects.

## 3. ViewModel test migration (the going-private `isInitialLoad` read → `uiState`)

- [x] 3.1 `BlockedUsersViewModelTest`: migrate the line-53 `assertEquals(false, vm.isInitialLoad.value)` read to a `uiState` assertion (`vm.uiState.value !is BlockedUsersUiState.Loading`, i.e. the loaded `Content`/`Empty`) using a `CoroutineScope(Dispatchers.Main).launch { vm.uiState.collect {} }` collector (the `GlobalTimelineViewModelTest.activateUiState()` pattern); keep the `.outcome` / `.unblockError` reads as-is.

## 4. New uiState scenario coverage (per spec deltas)

- [x] 4.1 Add to `BlockedUsersViewModelTest`: a "uiState delegates to the pure projection" test (`uiState.value == blockedUsersUiState(outcome, isInitialLoad = false)` for a held `Loaded`, projects to `Content`) and a "config-change proxy" test (a fresh collector on the same VM still sees `Content`), mirroring `GlobalTimelineViewModelTest`.
- [x] 4.2 Add a new `ConversationListViewModelTest` (the VM has no dedicated unit test today): first-page-loads-once-on-construction, `reload()` re-fetches, a load failure maps to `NetworkError` (and `uiState` projects to `Error`), plus the "uiState delegates to the pure projection" + "config-change proxy" scenarios — all using the `activateUiState()` collector pattern over a `FakeConversationsFlow`.
- [x] 4.3 Confirm the existing pure-projection unit tests (`ConversationListUiStateTest`, `BlockedUsersUiStateTest`) still pass unchanged (the projection function is reused, not modified).
- [x] 4.4 Confirm the Robolectric screen tests (`ConversationListScreenTest`, `BlockedUsersScreenTest`) render the same states with the `uiState`-collecting screens (fix only collection-shape references if a test inspects the screen's flow wiring).

## 5. Spec sync + gate + verification

- [x] 5.1 `openspec validate mobile-chat-settings-viewmodels-statein --strict` passes.
- [x] 5.2 Pre-push gate: `./gradlew ktlintCheck detekt :lint:detekt-rules:test :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`. `:backend:ktor:test` deferred to CI (mobile-commonMain-only change, zero backend files touched, local DB not migrated).
- [ ] 5.3 `./gradlew :mobile:app:iosSimulatorArm64Test`: K/N compile ✓ + the migrated/new commonTest VM tests pass on iOS. (Any pre-existing `*FlowIosTest` DI-drift failures, #348, are unrelated — iOS is never CI-gated and this change touches no DI.)
- [ ] 5.4 Manual verification per docs/11 §5 DoD: the Robolectric `ConversationListScreenTest` + `BlockedUsersScreenTest` render the REAL screens over the new single-`uiState` collection (all visual states); the change is provably behavior-preserving (same pure projection, same states/transitions); record the evidence (and operator buy-in if accepting test-coverage in lieu of a fresh device screenshot, per the 05-#6 precedent) in the PR body before archive.
