## MODIFIED Requirements

### Requirement: ChatThreadScreen renders the 1:1 thread

The mobile app SHALL ship a Compose Multiplatform screen `ChatThreadScreen` reached via the `ChatThreadRoute` NavKey. It SHALL render a top bar with the partner display identity (from the route), a scrollable message list with own-vs-other alignment (own = sent by the viewer), and a bottom input bar (`chat_thread_input_placeholder` + a send action). The initial-load vs refresh behavior SHALL follow the `mobile-design-system` canonical pattern. The screen SHALL render under `NearYouTheme`.

The `ChatThreadRoute`-scoped `ChatThreadViewModel` SHALL expose exactly ONE content `uiState: StateFlow<ChatThreadUiState>` produced via `combine(_historyOutcome, initialLoad, _rows) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatThreadUiState.Loading)`, whose projection delegates to the unchanged pure `chatThreadUiState(outcome, isInitialLoad, rows)` function (reused, not reimplemented) — so the (history-outcome + initial-load + merged-rows)→state mapping is owned by the ViewModel and is NOT recomputed in the composable (docs/11 §2.2). The initial-load flag SHALL be an INTERNAL (private) `MutableStateFlow` reflected only through `uiState` (which projects to `Loading` until the first history outcome arrives); it SHALL NOT be a separate public flow. The raw `historyOutcome` (a `StateFlow<ChatThreadOutcome?>`) AND the realtime-merged `rows` (a `StateFlow<List<ChatMessageRow>>` — the REST + optimistic + realtime id-deduped list) SHALL remain exposed as the ViewModel's domain-state seams that the white-box realtime/merge tests read. `ChatThreadScreen` SHALL collect that single `uiState` via `collectAsStateWithLifecycle()` and SHALL NOT recompute `chatThreadUiState(...)` in the composable over separately-collected `historyOutcome` / `isInitialLoad` / `rows` flows. The orthogonal send-bar sub-state (`sendBarState(sendOutcome, sendInFlight)`) and the report one-shots (`reportTargetMessageId` / `reportMessage`) SHALL stay SEPARATE signals (independently-varying / nullable one-shots, per docs/11 §2.2) and SHALL NOT be folded into the content `uiState`.

#### Scenario: Own vs other alignment
- **GIVEN** a thread with one message from the viewer and one from the partner
- **WHEN** the thread renders
- **THEN** the viewer's message and the partner's message are distinguishable (alignment/treatment) based on `senderId == viewerId`, AND no sender user-UUID is rendered as text

#### Scenario: Thread fetch shape and pagination
- **GIVEN** a Ktor MockEngine
- **WHEN** the thread loads its first page then loads older messages on scroll-up
- **THEN** the first request is `GET /api/v1/chat/{conversation_id}/messages` with NO `cursor`, and the older-page request carries the `cursor` returned by the first page

#### Scenario: Input bar renders and loading uses a single indicator
- **WHEN** the thread renders loaded history, then a refresh is in flight
- **THEN** the bottom input bar (`chat_thread_input_placeholder` + send action) is present in both cases AND exactly one progress indicator shows at a time (initial-load skeleton vs the `PullToRefreshBox` `isRefreshing` indicator over retained content), per `mobile-design-system` § "Canonical list loading and refresh pattern"

#### Scenario: Shadow-banned/deleted partner top-bar is masked, not a stale real name
- **GIVEN** the partner is shadow-banned or deleted (the conversation list masked them as `Akun Dihapus`)
- **WHEN** the thread top bar renders the partner identity
- **THEN** it shows the `Akun Dihapus` placeholder semantics rather than a stale real name carried on the route payload (the shadow-ban mask is not bypassed via `ChatThreadRoute`)

#### Scenario: ChatThread VM exposes one content uiState StateFlow via stateIn delegating to the pure projection
- **WHEN** inspecting `ChatThreadViewModel`
- **THEN** it exposes a single `uiState: StateFlow<ChatThreadUiState>` produced via `combine(_historyOutcome, initialLoad, _rows) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatThreadUiState.Loading)` AND for the held `(historyOutcome, isInitialLoad, rows)` its value equals `chatThreadUiState(historyOutcome, isInitialLoad, rows)` (the pure function is reused, not reimplemented) AND the initial-load flag is NOT exposed as a separate public flow

#### Scenario: ChatThreadScreen observes the entry-scoped ViewModel's single uiState, not a composition-local projection
- **WHEN** inspecting the chat-thread composable in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/chat/ChatThreadScreen.kt`
- **THEN** the screen collects the ViewModel's single `uiState` via `collectAsStateWithLifecycle()` AND does NOT recompute `chatThreadUiState(...)` in the composable over separately-collected `historyOutcome` / `isInitialLoad` / `rows` flows

#### Scenario: uiState retains the resolved state across a fresh collector (configuration-change proxy)
- **GIVEN** a `ChatThreadViewModel` whose first-page resync resolved to a `Loaded` history outcome with merged rows so `uiState.value` is `Content`
- **WHEN** the screen composition is recreated (the configuration-change case) and a fresh collector re-collects the same entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState.value` is still `Content` (the outcome + rows were retained by the entry-scoped ViewModel, not reset to `Loading`)
