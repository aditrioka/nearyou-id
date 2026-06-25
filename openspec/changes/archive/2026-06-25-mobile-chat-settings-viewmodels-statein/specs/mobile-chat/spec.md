## MODIFIED Requirements

### Requirement: Conversation list fetches the canonical endpoint and projects six states

`ConversationsApiClient` SHALL issue `GET /api/v1/conversations` (cursor-paginated). `ConversationsRepository` SHALL map the HTTP **status** to a sealed `ConversationListOutcome` (`Loaded(conversations, nextCursor)`, `NetworkError`, `Error`, `SessionExpired`) with no generic fallthrough; a terminal 401 SHALL map to `SessionExpired` (delegated to the shipped `Auth` plugin / `SessionInvalidator`, not reimplemented). A pure Compose-free `conversationListUiState(outcome, isInitialLoad)` projection SHALL map to `Loading` / `Content` / `Empty` / `Error` / `SessionRedirect`. The loading/refresh behavior SHALL follow `mobile-design-system` § "Canonical list loading and refresh pattern" (initial-load skeleton vs refresh-over-retained-content; never two indicators; non-`Content` states rendered inside a scrollable).

The `ConversationListRoute`-scoped `ConversationListViewModel` SHALL expose exactly ONE `uiState: StateFlow<ConversationListUiState>` produced via `combine(_outcome, initialLoad) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState.Loading)`, whose projection delegates to the unchanged pure `conversationListUiState(outcome, isInitialLoad)` function (reused, not reimplemented) — so the outcome→state mapping is owned by the ViewModel and is NOT recomputed in the composable (docs/11 §2.2). The initial-load flag SHALL be an INTERNAL (private) `MutableStateFlow` reflected only through `uiState` (which projects to `Loading` until the first outcome arrives); it SHALL NOT be a separate public flow. The raw fetched `ConversationListOutcome` SHALL remain exposed as the ViewModel's domain-state seam (a `StateFlow<ConversationListOutcome?>`, not a `ConversationListUiState`; the raw `Loaded` carries the partner UUID the PII-free `Content` rows strip). The pull-to-refresh indicator SHALL be a SEPARATE `isRefreshing` flag, NOT folded into `uiState`. `ConversationListScreen` SHALL collect that single `uiState` via `collectAsStateWithLifecycle()` and SHALL NOT recompute `conversationListUiState(...)` in the composable over separately-collected `outcome` / `isInitialLoad` flows.

#### Scenario: First-page request shape
- **GIVEN** a Ktor MockEngine capturing outbound requests
- **WHEN** `ConversationsApiClient` loads the first page
- **THEN** the captured request is `GET` with path `/api/v1/conversations` AND carries NO `cursor` parameter, AND the Bearer `Authorization` header is attached by the shipped `Auth` plugin (the client does not set it manually)

#### Scenario: Empty list projects to Empty, not Error
- **WHEN** the endpoint returns `200` with an empty conversation array
- **THEN** the outcome is `Loaded(conversations = [], nextCursor = null)` AND the projection (post-initial-load) is `ConversationListUiState.Empty` (rendering `chat_list_empty`), distinct from `Error`

#### Scenario: Terminal 401 projects to SessionRedirect
- **WHEN** the load results in a terminal 401 after the `Auth` refresh fails
- **THEN** the outcome is `SessionExpired` AND the projection is `SessionRedirect` (a neutral placeholder, NOT the network-error/retry copy)

#### Scenario: Pull-to-refresh is available from a non-Content state
- **GIVEN** the screen is in the `Empty` or `Error` state
- **WHEN** the pull-to-refresh gesture is performed
- **THEN** the reload fetch is invoked AND the state remains the same non-`Content` state during the refresh (it does NOT flip to the initial-load skeleton)

#### Scenario: VM exposes one uiState StateFlow via stateIn delegating to the pure projection
- **WHEN** inspecting `ConversationListViewModel`
- **THEN** it exposes a single `uiState: StateFlow<ConversationListUiState>` produced via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState.Loading)` AND for the held `(outcome, isInitialLoad)` its value equals `conversationListUiState(outcome, isInitialLoad)` (the pure function is reused, not reimplemented) AND the initial-load flag is NOT exposed as a separate public flow

#### Scenario: ConversationListScreen observes the entry-scoped ViewModel's single uiState, not a composition-local projection
- **WHEN** inspecting the conversation-list composable in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/chat/ConversationListScreen.kt`
- **THEN** the screen collects the ViewModel's single `uiState` (plus the separate `isRefreshing` flag) via `collectAsStateWithLifecycle()` AND does NOT recompute `conversationListUiState(...)` in the composable over separately-collected `outcome` / `isInitialLoad` flows

#### Scenario: uiState retains the resolved state across a fresh collector (configuration-change proxy)
- **GIVEN** a `ConversationListViewModel` whose load resolved to a `Loaded` outcome so `uiState.value` is `Content`
- **WHEN** the screen composition is recreated (the configuration-change case) and a fresh collector re-collects the same entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState.value` is still `Content` (the outcome was retained by the entry-scoped ViewModel, not reset to `Loading`)
