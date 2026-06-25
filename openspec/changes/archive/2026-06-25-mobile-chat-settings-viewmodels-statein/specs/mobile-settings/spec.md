## MODIFIED Requirements

### Requirement: Settings state holders are scoped to their NavEntry routes

`SettingsScreen`'s logout/state holder, `BlockedUsersScreen`'s `BlockedUsersViewModel`, and the consent sub-screen's view model SHALL each be resolved via `viewModel { }` scoped to their respective NavEntry (`SettingsRoute` / `BlockedUsersRoute` / `ConsentSettingsRoute`), per the established mobile state-holder Pattern Registry entry (docs/11 § 2.2) — NOT a new state pattern. Their dependencies (the API clients / repositories, `SecureTokenStore`) SHALL be provided through the existing Koin module and resolve at runtime.

Additionally, `BlockedUsersViewModel` SHALL expose exactly ONE `uiState: StateFlow<BlockedUsersUiState>` produced via `combine(_outcome, initialLoad) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockedUsersUiState.Loading)`, whose projection delegates to the unchanged pure `blockedUsersUiState(outcome, isInitialLoad)` function (reused, not reimplemented) — so the outcome→state mapping is owned by the ViewModel and is NOT recomputed in the composable (docs/11 §2.2). The initial-load flag SHALL be an INTERNAL (private) `MutableStateFlow` reflected only through `uiState` (which projects to `Loading` until the first outcome arrives); it SHALL NOT be a separate public flow. The raw fetched `BlockedUsersOutcome` SHALL remain exposed as the ViewModel's domain-state seam (a `StateFlow<BlockedUsersOutcome?>`, not a `BlockedUsersUiState`): the `unblock(userId)` action reads/writes it (non-optimistic row removal) and routes a terminal `401` through it, and the white-box ViewModel tests assert on it. The `isRefreshing` / `unblockError` (one-shot snackbar) / `unblocking` (in-flight row set) signals SHALL stay SEPARATE flows, NOT folded into `uiState`. `BlockedUsersScreen` SHALL collect that single `uiState` via `collectAsStateWithLifecycle()` for rendering and SHALL NOT recompute `blockedUsersUiState(...)` in the composable; it MAY still observe the raw `outcome` for the terminal-`401` → sign-in navigation side-effect (a side-effect, not a re-derivation of the rendered state).

#### Scenario: The settings view models resolve from Koin at their route scope

- **WHEN** the Koin graph is validated (a Koin-resolution test) for the settings module
- **THEN** `BlockedUsersViewModel`, the consent settings view model, and the settings/logout holder each resolve with all dependencies satisfied

#### Scenario: BlockedUsersViewModel exposes one uiState StateFlow via stateIn delegating to the pure projection

- **WHEN** inspecting `BlockedUsersViewModel`
- **THEN** it exposes a single `uiState: StateFlow<BlockedUsersUiState>` produced via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockedUsersUiState.Loading)` AND for the held `(outcome, isInitialLoad)` its value equals `blockedUsersUiState(outcome, isInitialLoad)` (the pure function is reused, not reimplemented) AND the initial-load flag is NOT exposed as a separate public flow

#### Scenario: BlockedUsersScreen observes the entry-scoped ViewModel's single uiState, not a composition-local projection

- **WHEN** inspecting the block-list composable in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/settings/BlockedUsersScreen.kt`
- **THEN** the screen collects the ViewModel's single `uiState` via `collectAsStateWithLifecycle()` for rendering AND does NOT recompute `blockedUsersUiState(...)` in the composable over a separately-collected `isInitialLoad` flow AND the terminal-`401` → sign-in routing is driven from the raw `outcome` seam (a navigation side-effect)

#### Scenario: uiState retains the resolved state across a fresh collector (configuration-change proxy)

- **GIVEN** a `BlockedUsersViewModel` whose load resolved to a `Loaded` outcome so `uiState.value` is `Content`
- **WHEN** the screen composition is recreated (the configuration-change case) and a fresh collector re-collects the same entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState.value` is still `Content` (the outcome was retained by the entry-scoped ViewModel, not reset to `Loading`)
