## Context

`docs/11-Engineering-Standards.md` §2.2 mandates each screen-level ViewModel "**Expose ONE `StateFlow<XxxUiState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`**, collect with `collectAsStateWithLifecycle()`". `mobile-feed-viewmodels-statein` (audit 05-#6, PR #409) aligned the four feed/list VMs and **explicitly deferred** the two same-shape forks in other feature domains to follow-up #410: `ConversationListViewModel` (`mobile-chat`) and `BlockedUsersViewModel` (`mobile-settings`). Both still expose raw `outcome` + `isInitialLoad` `StateFlow`s and run the pure projection `xxxUiState(outcome, isInitialLoad)` **in the composable** via `remember(outcome, isInitialLoad) { … }`. This change is that follow-up — the same mechanical pass over the two remaining VMs, mirroring the shipped 05-#6 shape and its `mobile-global-timeline` requirement/scenario template.

Both VMs are simpler than the feed VMs (no `InlineLikeController` / `LoadMoreController`): `ConversationListViewModel` exposes `outcome` + `isInitialLoad` + `isRefreshing`; `BlockedUsersViewModel` adds `unblockError` + `unblocking` + the `unblock(userId)` action (which mutates `_outcome` directly and can surface `TokenInvalid`). The raw `Loaded` outcomes carry fields the PII-free `XxxUiState.Content` deliberately drops (`ConversationListOutcome.Loaded` carries the partner user UUID via its DTOs; the projected `ConversationRow` strips it).

## Goals / Non-Goals

**Goals:**
- Each of the two VMs exposes exactly one `uiState: StateFlow<XxxUiState>` via `stateIn(WhileSubscribed(5_000))`, delegating to the existing pure projection unchanged.
- Each screen collects that single `uiState` for rendering (no in-composable projection).
- Make the convention uniform across the app, closing the two forks #410 tracks.

**Non-Goals:**
- No observable behavior change: identical content states + transitions, identical PII discipline, identical config-change survival.
- No change to the pure projection functions (`ConversationListUiState.kt`, `BlockedUsersUiState.kt`), the data seams (`*Flow`), the wire/DTOs, or DI wiring.
- Not folding the independently-varying signals (`isRefreshing` / `unblockError` / `unblocking`) into the content state; not the app-wide `koinViewModel()` conversion (audit 05-#7, separate); not the `screens/` package restructure (D6).

## Decisions

**1. Add `uiState` via `combine(_outcome, initialLoad).stateIn(WhileSubscribed(5_000), Loading)`, reusing the pure projection.** The combine maps to the existing `xxxUiState(outcome, isInitialLoad)` verbatim — the function is reused, not reimplemented (mirrors 05-#6). `WhileSubscribed(5_000)` and the `collectAsStateWithLifecycle()` collection are mandated by docs/11 §2.2 (not a free choice). Initial value is `…Loading` (the projection returns `Loading` for `isInitialLoad = true` / null outcome).

**2. `isInitialLoad` becomes private (`_isInitialLoad`→`initialLoad`); `outcome` stays public.** The initial-load flag is fully subsumed by `uiState` (`Loading` ⟺ initial load), so its public accessor is removed. Once private, the `_`-prefixed backing-property name would orphan (ktlint `BackingPropertyNaming`: `_foo` requires a public `foo`); renaming to a plain private `initialLoad` is the fix used by the 05-#6 VMs. `outcome` is kept as the ViewModel's **raw-domain-state seam** (a `StateFlow<XxxOutcome?>`, not a `XxxUiState`): `BlockedUsersViewModel.unblock()` reads/writes it (row removal) and routes terminal `401` through it; the white-box tests assert on it; and `ConversationListOutcome.Loaded` carries the partner UUID the PII-free `Content` rows strip. Exposing exactly one `XxxUiState` flow (`uiState`) satisfies "expose ONE `StateFlow<XxxUiState>`".

**3. The independently-varying signals stay separate flows.** Per docs/11 §2.2 ("data class when fields vary independently" + "one-shot events are nullable state fields"), `isRefreshing` (both VMs), and `BlockedUsersViewModel`'s `unblockError` (one-shot snackbar) / `unblocking` (in-flight row set) are orthogonal to the mutually-exclusive content state and remain their own flows. The screens keep collecting them as before.

**4. `BlockedUsersScreen` keeps collecting the raw `outcome` for the terminal-401 side-effect.** `BlockedUsersScreen` has a `LaunchedEffect(outcome) { if (outcome is TokenInvalid) onTokenInvalid() }` navigation side-effect. *Alternative considered — re-key it on `uiState is SessionRedirect` so the screen drops the `outcome` collect entirely:* rejected as an unnecessary semantic change to the trigger. Keeping the side-effect keyed on the raw `outcome` seam (which stays public) is the minimal behavior-preserving diff; the screen still stops **re-deriving the rendered state** in the composable (it collects `uiState`), which is the actual finding. `ConversationListScreen` has no such side-effect (its `SessionRedirect` is purely rendered, no `onTokenInvalid` callback), so it drops both the `outcome` and `isInitialLoad` collects.

**5. Test pattern for `WhileSubscribed`.** A `stateIn(WhileSubscribed(5_000))` flow only runs its upstream while collected, so VM unit tests start a `CoroutineScope(Dispatchers.Main).launch { vm.uiState.collect {} }` collector (on the `UnconfinedTestDispatcher` Main) before asserting `vm.uiState.value` — the shipped `GlobalTimelineViewModelTest.activateUiState()` pattern. The config-change scenario is proxied by a second fresh collector observing the retained state on the same VM instance. `ConversationListViewModel` has **no** dedicated VM unit test today (only `ConversationListUiStateTest` for the pure projection + `ConversationListScreenTest` for the rendered states); a new `ConversationListViewModelTest` is added.

## Risks / Trade-offs

- **[Behavior-preserving retrofit of two live screens]** → Behavior is preserved; the gate (`:mobile:app:testDevDebugUnitTest` + `…testDevReleaseUnitTest`) and the Robolectric screen tests (`ConversationListScreenTest`, `BlockedUsersScreenTest`, which render the same states) are the safety net. The pure-projection unit tests are untouched (function reused).
- **[`outcome` staying public could read as "two state flows"]** → Documented here + in each spec delta as the raw-domain-state seam (distinct type `StateFlow<XxxOutcome?>`, distinct purpose: paging/PII/side-effect); there is exactly one `XxxUiState` flow.
- **[`BlockedUsersScreen` still collecting `outcome`]** → Scoped narrowly to the documented terminal-401 navigation side-effect; the rendered state is the single `uiState`. Mirrors how the feed screens keep collecting their auxiliary flows alongside `uiState`.
- **[K/N compile — VMs are commonMain]** → Covered by `:mobile:app:iosSimulatorArm64Test` (the existing `ConversationListFlowIosTest` / `BlockedUsersFlowIosTest` exercise these data paths on Native).

## Migration Plan

Pure refactor, no runtime/data migration. Per VM: introduce `uiState`, rename the initial-load flag private, leave `outcome` + auxiliary flows; switch the screen to collect `uiState` (`BlockedUsersScreen` keeps the `outcome` side-effect collect); migrate the `BlockedUsersViewModelTest` `isInitialLoad` read to a `uiState` assertion, add the single-`uiState` + config-change scenarios to both VM tests (adding `ConversationListViewModelTest`). Rollback = revert the PR (no schema/wire/state-persistence surface).

## Open Questions

None. The shape (`uiState` via `stateIn`, `isInitialLoad` private, `outcome` retained as the raw-state seam, signals separate, the 401 side-effect kept on `outcome`) is settled against docs/11 §2.2 + the shipped 05-#6 precedent.
