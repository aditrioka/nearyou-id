## ADDED Requirements

### Requirement: NotificationsScreen state is exposed as one uiState StateFlow from the entry-scoped ViewModel

`NotificationsViewModel` SHALL expose exactly ONE `uiState: StateFlow<NotificationsUiState>` produced via `combine(_outcome, _isInitialLoad) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationsUiState.Loading)`, whose projection delegates to the unchanged pure `notificationsUiState(outcome, isInitialLoad)` function (reused, not reimplemented) — so the outcome→state mapping is owned by the ViewModel and is NOT recomputed in the composable. The initial-load flag SHALL be an INTERNAL (private) `MutableStateFlow` reflected only through `uiState` (which projects to `Loading` until the first outcome arrives); it SHALL NOT be a separate public flow. The raw fetched `NotificationsOutcome` SHALL remain exposed as the ViewModel's domain-state seam (it carries the unprojected read-state + cursor paging and is read by the optimistic mark-read / mark-all-read mutations, the load-more controller, the deep-link resolution, and the white-box ViewModel tests; it is NOT a screen-rendered `XxxUiState`). The independently-varying signals — `isRefreshing` (the pull-to-refresh indicator), `isLoadingMore` / `loadMoreError` (the footer), and the consumed-once `pendingNavTarget` / `postUnavailable` / `resolvingRowId` deep-link signals — SHALL remain SEPARATE flows, NOT folded into `uiState` (per docs/11 §2.2, "data class when fields vary independently" and "one-shot events are nullable state fields"). `NotificationsScreen` SHALL collect the single `uiState` (plus those separate signals) via `collectAsStateWithLifecycle()` and SHALL NOT recompute `notificationsUiState(...)` in the composable over separately-collected `outcome` / `isInitialLoad` flows. All observable behavior (the four content states + transitions, the optimistic read mutations, the deep-link resolution, the PII discipline, and the section-switch survival) SHALL be preserved exactly.

#### Scenario: VM exposes one uiState StateFlow via stateIn delegating to the pure projection

- **WHEN** inspecting `NotificationsViewModel`
- **THEN** it exposes a single `uiState: StateFlow<NotificationsUiState>` produced via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationsUiState.Loading)` AND for the held `(outcome, isInitialLoad)` its value equals `notificationsUiState(outcome, isInitialLoad)` (the pure function is reused, not reimplemented) AND the initial-load flag is NOT exposed as a separate public flow

#### Scenario: uiState retains the resolved state across a fresh collector (configuration-change proxy)

- **GIVEN** a `NotificationsViewModel` whose load resolved to a `Loaded` outcome so `uiState.value` is `Content`
- **WHEN** the screen composition is recreated (the configuration-change case) and a fresh `backgroundScope` collector re-collects the same entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState.value` is still `Content` (the outcome was retained by the entry-scoped ViewModel, not reset to `Loading`)

#### Scenario: A refresh keeps Content and toggles only isRefreshing

- **GIVEN** a `NotificationsViewModel` that has loaded a non-empty `Loaded` outcome (so `uiState.value` is `Content`) AND a `backgroundScope` collector on `uiState`
- **WHEN** `reload()` is invoked and is in flight
- **THEN** `isRefreshing` is `true` AND `uiState.value` stays `Content` (it does NOT revert to `Loading`); on completion `isRefreshing` returns to `false`

#### Scenario: NotificationsScreen observes the single uiState, not a composition-local projection

- **WHEN** inspecting `NotificationsScreen` in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/notifications/NotificationsScreen.kt`
- **THEN** the screen collects the ViewModel's single `uiState` (plus the separate `isRefreshing` / footer / deep-link signals) via `collectAsStateWithLifecycle()` AND does NOT recompute `notificationsUiState(...)` in the composable over separately-collected `outcome` / `isInitialLoad` flows
