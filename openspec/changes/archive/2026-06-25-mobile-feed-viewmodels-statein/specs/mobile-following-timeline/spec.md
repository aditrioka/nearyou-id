## MODIFIED Requirements

### Requirement: Following feed load state is scoped to the HomeRoute NavEntry and survives tab switch and the composer round-trip

The Following feed's first-page load state (the fetched outcome + the **initial-load flag** + the **refreshing flag** + the reload trigger) SHALL be held in a `HomeRoute`-scoped `FollowingTimelineViewModel` (resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` for `HomeRoute` — `mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators"), NOT in composition-scoped `remember` and NOT in a per-tab NavEntry store. The first page SHALL load exactly once on the ViewModel's construction (the first time the Following tab/page is shown). The ViewModel SHALL expose exactly ONE `uiState: StateFlow<FollowingTimelineUiState>` produced via `combine(_outcome, _isInitialLoad) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FollowingTimelineUiState.Loading)`, whose projection delegates to the unchanged pure `followingTimelineUiState(outcome, isInitialLoad)` function (reused, not reimplemented) — so the outcome→state mapping is owned by the ViewModel and is NOT recomputed in the composable. The initial-load flag SHALL be an INTERNAL (private) `MutableStateFlow` reflected only through `uiState` (which projects to `Loading` until the first outcome arrives); it SHALL NOT be a separate public flow. The raw fetched `FollowingTimelineOutcome` SHALL remain exposed as the ViewModel's domain-state seam (it carries cursor paging state and is read by the inline-like / load-more controllers and the white-box ViewModel tests; it is NOT a screen-rendered `XxxUiState`). The pull-to-refresh indicator SHALL be a SEPARATE `isRefreshing` flag, NOT folded into `uiState` (per docs/11 §2.2, "data class when fields vary independently"). On `reload()` it SHALL keep the existing outcome and set `isRefreshing = true` (so `uiState` keeps projecting `Content`), then swap the outcome and clear `isRefreshing` on completion. Pull-to-refresh and the error-retry control SHALL re-fetch page 1 via the ViewModel. Because the ViewModel is scoped to `HomeRoute` (which survives both feed swipes/tab switches and the composer being pushed above it), switching/swiping away from the Following feed and back, or opening the composer and returning, SHALL NOT re-fetch the Following feed.

#### Scenario: ViewModel loads once on construction and reloads on pull-to-refresh / retry

- **GIVEN** a commonTest `FollowingTimelineViewModel` over a `FakeFollowingTimelineFlow`
- **WHEN** the ViewModel is constructed
- **THEN** `loadFirstPage()` is invoked exactly once and the outcome is exposed; AND a subsequent `reload()` invokes `loadFirstPage()` a second time

#### Scenario: VM exposes one uiState StateFlow via stateIn delegating to the pure projection

- **WHEN** inspecting `FollowingTimelineViewModel`
- **THEN** it exposes a single `uiState: StateFlow<FollowingTimelineUiState>` produced via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FollowingTimelineUiState.Loading)` AND for the held `(outcome, isInitialLoad)` its value equals `followingTimelineUiState(outcome, isInitialLoad)` (the pure function is reused, not reimplemented) AND the initial-load flag is NOT exposed as a separate public flow

#### Scenario: reload keeps the prior outcome and toggles isRefreshing, with uiState staying Content

- **GIVEN** a `FollowingTimelineViewModel` that has loaded a `Loaded` outcome (so `uiState.value` is `Content`) AND a `backgroundScope` collector on `uiState`
- **WHEN** `reload()` is invoked and is in flight
- **THEN** `isRefreshing` is `true` AND `uiState.value` stays `Content` (it does NOT revert to `Loading`) AND the previously exposed `Loaded` outcome is retained (not nulled); on completion `isRefreshing` returns to `false` and the outcome is swapped

#### Scenario: uiState retains the resolved state across a fresh collector (configuration-change proxy)

- **GIVEN** a `FollowingTimelineViewModel` whose load resolved to a `Loaded` outcome so `uiState.value` is `Content`
- **WHEN** the screen composition is recreated (the configuration-change case) and a fresh `backgroundScope` collector re-collects the same entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState.value` is still `Content` (the outcome was retained by the entry-scoped ViewModel, not reset to `Loading`)

#### Scenario: FollowingFeed observes the entry-scoped ViewModel's single uiState, not a composition-local projection

- **WHEN** inspecting the Following feed composable in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/FollowingTimelineScreen.kt`
- **THEN** the feed collects the ViewModel's single `uiState` (plus the separate `isRefreshing` flag) via `collectAsStateWithLifecycle()` AND does NOT recompute `followingTimelineUiState(...)` in the composable over separately-collected `outcome` / `isInitialLoad` flows

#### Scenario: A load failure maps to the existing retryable error

- **GIVEN** a `FakeFollowingTimelineFlow` whose `loadFirstPage()` throws
- **WHEN** the `FollowingTimelineViewModel` loads
- **THEN** its exposed outcome is `FollowingTimelineOutcome.NetworkError` (the retryable state — no special outcome member for this) AND its `uiState.value` projects to `Error`
