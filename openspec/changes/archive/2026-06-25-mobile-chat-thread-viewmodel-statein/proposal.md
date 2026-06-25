## Why

`docs/11-Engineering-Standards.md` §2.2 mandates that every screen-level ViewModel "**Expose ONE `StateFlow<XxxUiState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`**". The feed/list family was aligned in `mobile-feed-viewmodels-statein` (audit finding **05-#6**, PR [#409](https://github.com/aditrioka/nearyou-id/pull/409)); the two same-shape forks outside the feed domain (`ConversationListViewModel` + `BlockedUsersViewModel`) followed in `mobile-chat-settings-viewmodels-statein` (follow-up [#410](https://github.com/aditrioka/nearyou-id/issues/410), PR [#413](https://github.com/aditrioka/nearyou-id/pull/413)).

A **third** ViewModel carries the identical old shape but was named in **neither** list — surfaced while shipping #410 and tracked as follow-up [#414](https://github.com/aditrioka/nearyou-id/issues/414):

- `ChatThreadViewModel` (`mobile-chat`) — exposes raw `historyOutcome` + `isInitialLoad` + `rows` `StateFlow`s and runs `chatThreadUiState(historyOutcome, isInitialLoad, rows)` **in the composable** via `remember(historyOutcome, isInitialLoad, rows) { … }` (`ChatThreadScreen.kt:189`), with a public `isInitialLoad` accessor (`ChatThreadViewModel.kt:73-74`).

Leaving this on the old shape keeps the exact deviation finding 05-#6 exists to eliminate alive in the chat-thread surface ("a baseline deviation any new screen copies"). This change applies the same mechanical pass so the single-`uiState` convention is uniform across the app, closing #414.

**Extra wrinkle vs #410.** `chatThreadUiState` takes **three** inputs — the realtime-merged `rows` (REST + optimistic + realtime, id-deduped) is a separate flow alongside `historyOutcome` + `isInitialLoad`. So the fold is a 3-input `combine(_historyOutcome, initialLoad, _rows).stateIn(…)` delegating to the unchanged 3-arg projection (genuinely a touch more involved than the 2-input #410 VMs — hence its own change, not folded into #410).

## What Changes

- `ChatThreadViewModel` exposes exactly one content `uiState: StateFlow<ChatThreadUiState>` produced via `combine(_historyOutcome, initialLoad, _rows) { o, i, r -> chatThreadUiState(o, i, r) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatThreadUiState.Loading)`, **delegating to the existing pure projection unchanged** (reused, never reimplemented — mirroring the 05-#6 / #410 "uiState delegates to the pure projection" template).
- The public `isInitialLoad` `StateFlow` accessor is **removed**; the backing flag is renamed `_isInitialLoad`→`initialLoad` (private) to satisfy the backing-property naming rule once its public accessor is gone (ktlint `BackingPropertyNaming`: `_foo` requires a public `foo`). It is subsumed by `uiState` (`Loading` ⟺ initial load).
- Both `historyOutcome` (`StateFlow<ChatThreadOutcome?>`) **and** the realtime-merged `rows` (`StateFlow<List<ChatMessageRow>>`) **stay public** as the ViewModel's raw-domain-state seams: the white-box realtime/merge tests read them (`viewModel.rows.value` / `viewModel.historyOutcome.value`), and `rows` is the id-deduped REST+optimistic+realtime list the merge spec pins.
- The independently-varying / one-shot signals stay as **separate** flows (per §2.2, "data class when fields vary independently" + "one-shot events are nullable state fields"): the send-bar sub-state `sendBarState(sendOutcome, sendInFlight)`, plus `sendOutcome` / `sendInFlight` / `reportTargetMessageId` / `reportMessage` / `startedConversationId` / `startOutcome`. They are **not** folded into the content `uiState`; the send-bar's own in-composable `remember(sendOutcome, sendInFlight) { sendBarState(…) }` projection is an orthogonal signal (like #410's `isRefreshing`) and is out of scope for this finding.
- `ChatThreadScreen` drops its `val rows` / `val historyOutcome` / `val isInitialLoad` collects and the in-composable `remember(…) { chatThreadUiState(…) }`, collecting `val uiState by viewModel.uiState.collectAsStateWithLifecycle()` instead.
- This is a **behavior-preserving** internal state-exposure refactor: identical content states + transitions, identical realtime-merge behavior, identical PII discipline, identical config-change survival (the VM is already `ChatThreadRoute`-scoped and already retains `_historyOutcome` + `_rows`). No API/wire/schema change, no new dependency. **Not breaking.**

## Capabilities

### New Capabilities

<!-- none — no new capability is introduced -->

### Modified Capabilities

- `mobile-chat`: the `ChatThreadViewModel` SHALL expose one content `uiState` `StateFlow` via `stateIn(WhileSubscribed(5_000))` delegating to the unchanged `chatThreadUiState(…)` 3-arg projection; `ChatThreadScreen` collects that single state (no in-composable `chatThreadUiState(…)` projection), keeping `historyOutcome` + `rows` as the raw domain-state seams and the send-bar / report signals separate.

## Impact

- **Code (`:mobile:app`, commonMain)**: `screens/chat/ChatThreadViewModel.kt` (add `uiState`, drop public `isInitialLoad`, rename backing flag private); `screens/chat/ChatThreadScreen.kt` (collect the single `uiState`, drop the three now-subsumed collects + the in-composable projection). The pure projection file (`ChatThreadUiState.kt`) is **unchanged** (the 3-arg `chatThreadUiState` signature is reused verbatim).
- **Tests**: `ChatThreadViewModelTest` gains the single-`uiState` delegate + config-change-retention scenarios (collected via a `WhileSubscribed`-activating collector, per `GlobalTimelineViewModelTest`/`ConversationListViewModelTest`). **No `isInitialLoad` test read exists to migrate** (the VM's `isInitialLoad` is read only by `ChatThreadScreen.kt:140`, which this change removes — unlike #410's `BlockedUsersViewModelTest:53`). The existing pure-projection unit tests (`ChatThreadUiStateTest`) stay valid (function reused, signature unchanged); the Robolectric `ChatThreadScreenTest` renders the same states over the `uiState`-collecting screen (drives via `FakeChatFlow`, inspects no VM flow wiring — verify unchanged). Kotlin/Native compile is covered by `:mobile:app:iosSimulatorArm64Test` (the VM is commonMain).
- **No backend / admin / wire / schema impact.** Pure mobile vertical slice — no counterpart layer is deferred (docs/12: an internal client-only refactor has no backend/admin surface).
