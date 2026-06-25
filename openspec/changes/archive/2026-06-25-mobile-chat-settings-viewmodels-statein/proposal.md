## Why

`docs/11-Engineering-Standards.md` §2.2 mandates that every screen-level ViewModel "**Expose ONE `StateFlow<XxxUiState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`**". The feed/list family was aligned to this in `mobile-feed-viewmodels-statein` (audit finding **05-#6**, PR [#409](https://github.com/aditrioka/nearyou-id/pull/409)). That change explicitly deferred two **same-shape forks** that live outside the feed/timeline domain — tracked as follow-up [#410](https://github.com/aditrioka/nearyou-id/issues/410):

- `ConversationListViewModel` (`mobile-chat`) — exposes raw `outcome` + `isInitialLoad` `StateFlow`s and runs `conversationListUiState(outcome, isInitialLoad)` **in the composable** via `remember(outcome, isInitialLoad) { … }`.
- `BlockedUsersViewModel` (`mobile-settings`) — the identical shape with `blockedUsersUiState(outcome, isInitialLoad)`; `BlockedUsersViewModelTest:53` still reads `vm.isInitialLoad`.

Leaving these on the old shape keeps the exact deviation finding 05-#6 exists to eliminate alive in two more surfaces ("a baseline deviation any new screen copies"). This change applies the same mechanical pass so the convention is uniform across the app, closing #410.

## What Changes

- Each of the two ViewModels exposes exactly one `uiState: StateFlow<XxxUiState>` produced via `combine(_outcome, initialLoad) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …Loading)`, **delegating to the existing pure projection function unchanged** (reused, never reimplemented — mirroring `mobile-feed-viewmodels-statein`'s "uiState delegates to `globalTimelineUiState(…)`").
- The public `isInitialLoad` `StateFlow` accessor is **removed**; the backing flag is renamed `_isInitialLoad`→`initialLoad` (private) to satisfy the backing-property naming rule once its public accessor is gone. It is subsumed by `uiState` (`Loading` ⟺ initial load).
- `outcome` **stays public** as the raw-domain-state seam: `BlockedUsersViewModel`'s unblock action (row removal + token-invalid routing) and the white-box ViewModel tests read it; for `ConversationListViewModel` it stays public for symmetry + test access (`ConversationListUiState.Content` strips the partner UUID the raw `Loaded` carries).
- The independently-varying signals stay as **separate** flows (per §2.2, "data class when fields vary independently"): `isRefreshing` (both VMs); `BlockedUsersViewModel`'s `unblockError` / `unblocking`. They are **not** folded into the content `uiState`.
- `ConversationListScreen` drops its `val outcome` / `val isInitialLoad` collects and the in-composable `remember(…) { conversationListUiState(…) }`, collecting `val uiState by viewModel.uiState.collectAsStateWithLifecycle()` instead.
- `BlockedUsersScreen` drops its `val isInitialLoad` collect and the in-composable `remember(…) { blockedUsersUiState(…) }`, collecting the single `uiState` for rendering; it **keeps** collecting the raw `outcome` for the terminal-`401` → sign-in `LaunchedEffect` side-effect (a navigation side-effect, not a re-derivation of the rendered state).
- This is a **behavior-preserving** internal state-exposure refactor: identical content states + transitions, identical PII discipline, identical config-change survival (both VMs are already entry-scoped and already retain `_outcome`). No API/wire/schema change, no new dependency. **Not breaking.**

## Capabilities

### New Capabilities

<!-- none — no new capability is introduced -->

### Modified Capabilities

- `mobile-chat`: the `ConversationListViewModel` SHALL expose one `uiState` `StateFlow` via `stateIn(WhileSubscribed(5_000))` delegating to the unchanged `conversationListUiState(…)`; `ConversationListScreen` collects that single state (no in-composable projection).
- `mobile-settings`: the `BlockedUsersViewModel` SHALL expose one `uiState` `StateFlow` via `stateIn(WhileSubscribed(5_000))` delegating to the unchanged `blockedUsersUiState(…)`; `BlockedUsersScreen` collects that single state for rendering (no in-composable projection), keeping the raw-`outcome` terminal-401 side-effect.

## Impact

- **Code (`:mobile:app`, commonMain)**: `screens/chat/ConversationListViewModel.kt` + `screens/settings/BlockedUsersViewModel.kt` (add `uiState`, drop public `isInitialLoad`, rename backing flag private); `screens/chat/ConversationListScreen.kt` + `screens/settings/BlockedUsersScreen.kt` (collect the single `uiState`). The pure projection files (`ConversationListUiState.kt`, `BlockedUsersUiState.kt`) are unchanged.
- **Tests**: `BlockedUsersViewModelTest` migrates its one `.isInitialLoad` read (line 53) to a `uiState` assertion (collected via a `WhileSubscribed`-activating collector, per `GlobalTimelineViewModelTest`) and gains the single-`uiState` delegate + config-change-retention scenarios; a new `ConversationListViewModelTest` is added (the VM had no dedicated unit test) covering load-once/reload + the single-`uiState` delegate + config-change scenarios. The existing pure-projection unit tests (`ConversationListUiStateTest`, `BlockedUsersUiStateTest`) stay valid (function reused); the Robolectric screen tests (`ConversationListScreenTest`, `BlockedUsersScreenTest`) render the same states (verify unchanged). Kotlin/Native compile is covered by `:mobile:app:iosSimulatorArm64Test` (the VMs are commonMain).
- **No backend / admin / wire / schema impact.** Pure mobile vertical slice — no counterpart layer is deferred (docs/12: an internal client-only refactor has no backend/admin surface).
