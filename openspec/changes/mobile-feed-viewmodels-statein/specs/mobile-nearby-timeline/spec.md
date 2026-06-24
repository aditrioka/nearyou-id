## MODIFIED Requirements

### Requirement: Nearby feed load state is scoped to the Home NavEntry and survives the composer round-trip

The Nearby feed's first-page load state (the fetched outcome + the **initial-load flag** + the **refreshing flag** + the reload trigger) SHALL be held in a `HomeRoute`-scoped ViewModel (`NearbyTimelineViewModel`, resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` — see `mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators"), NOT in composition-scoped `remember` and NOT in a per-tab NavEntry store. The first page SHALL load exactly once on the ViewModel's construction. The ViewModel SHALL expose exactly ONE `uiState: StateFlow<NearbyTimelineUiState>` produced via `combine(_outcome, _isInitialLoad) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyTimelineUiState.Loading)`, whose projection delegates to the unchanged pure `nearbyTimelineUiState(outcome, isInitialLoad)` function (reused, not reimplemented) — so the outcome→state mapping is owned by the ViewModel and is NOT recomputed in the composable. The initial-load flag SHALL be an INTERNAL (private) `MutableStateFlow` reflected only through `uiState` (which projects to `Loading` until the first outcome arrives); it SHALL NOT be a separate public flow. The raw fetched `NearbyTimelineOutcome` SHALL remain exposed as the ViewModel's domain-state seam — it carries cursor/anchor paging state (a coordinate-bearing anchor that the PII-free `NearbyTimelineUiState` deliberately strips) and is read by the inline-like / load-more controllers and the white-box ViewModel tests; it is NOT a screen-rendered `XxxUiState`. The pull-to-refresh indicator SHALL be a SEPARATE `isRefreshing` flag, NOT folded into `uiState` (per docs/11 §2.2, "data class when fields vary independently"). On `reload()` the ViewModel SHALL keep the existing outcome and set `isRefreshing = true` (so `uiState` keeps projecting `Content`), then swap the outcome and clear `isRefreshing` on completion. Pull-to-refresh and the error-retry control SHALL re-fetch page 1 via the ViewModel. Because the ViewModel is scoped to `HomeRoute` — which survives both the post composer being on top AND switching/swiping between the Nearby/Following/Global feeds (and bottom-nav sections) — opening the composer and returning, or swiping away and back to Nearby, SHALL NOT re-fetch the Nearby feed; the already-loaded posts are shown immediately. The ViewModel is cleared only when `HomeRoute` is popped. A coordinate-acquisition failure SHALL continue to map to the existing retryable `NearbyTimelineOutcome.NetworkError` (no new outcome member).

#### Scenario: ViewModel loads once on construction and reloads on pull-to-refresh / retry

- **GIVEN** a `commonTest` `NearbyTimelineViewModel` over a `FakeNearbyTimelineFlow`
- **WHEN** the ViewModel is constructed
- **THEN** `loadFirstPage()` is invoked exactly once and the outcome is exposed; AND a subsequent `reload()` invokes `loadFirstPage()` a second time

#### Scenario: VM exposes one uiState StateFlow via stateIn delegating to the pure projection

- **WHEN** inspecting `NearbyTimelineViewModel`
- **THEN** it exposes a single `uiState: StateFlow<NearbyTimelineUiState>` produced via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyTimelineUiState.Loading)` AND for the held `(outcome, isInitialLoad)` its value equals `nearbyTimelineUiState(outcome, isInitialLoad)` (the pure function is reused, not reimplemented) AND the initial-load flag is NOT exposed as a separate public flow

#### Scenario: reload keeps the prior outcome and toggles isRefreshing, with uiState staying Content

- **GIVEN** a `NearbyTimelineViewModel` that has loaded a `Loaded` outcome (so `uiState.value` is `Content`) AND a `backgroundScope` collector on `uiState`
- **WHEN** `reload()` is invoked and is in flight
- **THEN** `isRefreshing` is `true` AND `uiState.value` stays `Content` (it does NOT revert to `Loading`) AND the previously exposed `Loaded` outcome is retained (not nulled); on completion `isRefreshing` returns to `false` and the outcome is swapped

#### Scenario: uiState retains the resolved state across a fresh collector (configuration-change proxy)

- **GIVEN** a `NearbyTimelineViewModel` whose load resolved to a `Loaded` outcome so `uiState.value` is `Content`
- **WHEN** the screen composition is recreated (the configuration-change case) and a fresh `backgroundScope` collector re-collects the same entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState.value` is still `Content` (the outcome was retained by the entry-scoped ViewModel, not reset to `Loading`)

#### Scenario: NearbyFeed observes the entry-scoped ViewModel's single uiState, not a composition-local projection

- **WHEN** inspecting `NearbyFeed` in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the feed collects the ViewModel's single `uiState` (plus the separate `isRefreshing` flag) via `collectAsStateWithLifecycle()` AND does NOT recompute `nearbyTimelineUiState(...)` in the composable over separately-collected `outcome` / `isInitialLoad` flows

#### Scenario: Swiping away and returning to Nearby does not re-fetch

- **GIVEN** a `FakeNearbyTimelineFlow` counting fetch invocations, the tab host composed with Nearby selected (one Nearby fetch having occurred)
- **WHEN** the test swipes to the Global tab and then back to the Nearby tab
- **THEN** the Nearby fetch invocation count remains 1 (the `HomeRoute`-scoped ViewModel survived the swipe — no re-fetch)
