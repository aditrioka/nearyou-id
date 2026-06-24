## Context

`docs/11-Engineering-Standards.md` §2.2 mandates each screen-level ViewModel "**Expose ONE `StateFlow<XxxUiState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`**, collect with `collectAsStateWithLifecycle()`". The four feed/list ViewModels predate that consolidation: each exposes the raw `outcome` + `isInitialLoad` `StateFlow`s and the screen runs the pure projection `xxxTimelineUiState(outcome, isInitialLoad)` **in the composable** via `remember(outcome, isInitialLoad) { … }`. The single-`stateIn` shape is already shipped for the simpler VMs (`SignInViewModel`/`AgeGateViewModel`/`ConsentViewModel` — `mobile-auth-flow-viewmodels`, PR #405; `UsernameCustomizationViewModel`; `PaywallViewModel`), and `mobile-auth-signin/spec.md` § "SignInScreen state is owned by an entry-scoped SignInViewModel" is the requirement/scenario template this change mirrors.

The four feed VMs are more complex than `SignInViewModel`: each composes an `InlineLikeController` + `LoadMoreController` (Notifications adds deep-link resolution; Nearby adds the radius slider), so beyond the content projection they expose several independently-varying signals (`isRefreshing`, `isLoadingMore`/`loadMoreError`, `likeCapRetryAfterSeconds`, Nearby's `selectedRadiusM`/`isPremiumKnown`/`radiusUpsell`, Notifications' `pendingNavTarget`/`postUnavailable`/`resolvingRowId`). The raw outcome (`XxxTimelineOutcome.Loaded`) carries paging state the display projection deliberately drops — including, for Nearby, a coordinate-bearing `anchor: LatLng` that the PII-free `NearbyTimelineUiState` MUST NOT carry (the `mobile-nearby-timeline` § "No author identifier or coordinate is rendered or logged" invariant). This change is finding **05-#6** of the 2026-06-10 holistic audit.

## Goals / Non-Goals

**Goals:**
- Each of the four feed VMs exposes exactly one `uiState: StateFlow<XxxTimelineUiState>` via `stateIn(WhileSubscribed(5_000))`, delegating to the existing pure projection unchanged.
- Each screen collects that single `uiState` (no in-composable projection).
- Align the feed family to the docs/11 §2.2 single-`stateIn` convention already shipped elsewhere, so the next feed screen copies the right shape.

**Non-Goals:**
- No observable behavior change: identical content states + transitions, identical PII discipline, identical config-change survival.
- No change to the pure projection functions (`*UiState.kt`), the data seams (`*Flow`), the wire/DTOs, the `LoadMoreController`/`InlineLikeController`, or DI wiring.
- Not folding the independently-varying signals into the content state; not the app-wide `koinViewModel()` conversion (audit 05-#7, separate); not the `screens/` package restructure (D6).

## Decisions

**1. Add `uiState` via `combine(_outcome, _isInitialLoad).stateIn(WhileSubscribed(5_000), Loading)`, reusing the pure projection.** The combine maps to the existing `xxxTimelineUiState(outcome, isInitialLoad)` verbatim — the function is reused, not reimplemented (mirrors `mobile-auth-signin`'s "the projection's value equals `signInUiState(…)`"). `WhileSubscribed(5_000)` and the `collectAsStateWithLifecycle()` collection are mandated by docs/11 §2.2 (not a free choice). Initial value is `…Loading` (the projection returns `Loading` for `isInitialLoad = true` / null outcome).

**2. `isInitialLoad` becomes private; `outcome` stays public.** *Alternative considered — make BOTH private and expose only `uiState` (the literal `SignInViewModel` shape):* rejected. The white-box VM tests assert `(outcome.value as Loaded).nextCursor` (cursor advancement) and the controllers/tests read raw paging state that `uiState` deliberately strips; for Nearby the raw `Loaded.anchor` is a `LatLng`, so surfacing it through `uiState` would violate the no-coordinate-in-rendered-state invariant. Keeping `outcome` as the ViewModel's **raw-domain-state seam** (a `StateFlow<XxxOutcome?>`, not a `XxxUiState`) preserves that coverage without PII pollution and is consistent with "expose ONE `StateFlow<XxxUiState>`" (there is exactly one `XxxUiState` flow — `uiState`). `isInitialLoad` *is* fully subsumed by `uiState` (`Loading` ⟺ initial-load), so its public accessor is removed; the ~10 test sites reading it migrate to asserting `uiState` is/!is `Loading`.

**3. The independently-varying signals stay separate flows.** Per docs/11 §2.2 ("data class when fields vary independently" + "one-shot events are nullable state fields"), `isRefreshing`/footer/dialog/radius/deep-link signals are orthogonal to the mutually-exclusive content state and remain their own flows. The screens keep collecting them as before.

**4. Scope = four VMs (Nearby/Global/Following/Notifications).** The audit named three (Following did not exist at audit time). *Alternative — migrate only the audit's three:* rejected — `FollowingTimelineViewModel` copied the multi-flow shape verbatim, so leaving it behind forks the convention this finding exists to eliminate. (Recorded as a menu correction in the audit-burndown skill.)

**5. Test pattern for `WhileSubscribed`.** A `stateIn(WhileSubscribed(5_000))` flow only runs its upstream while collected, so VM unit tests start a `backgroundScope.launch { vm.uiState.collect {} }` collector before asserting `vm.uiState.value` (the shipped `SignInViewModelTest` pattern). The config-change scenario is proxied by a second fresh collector observing the retained state on the same VM instance.

## Risks / Trade-offs

- **[Heavily-tested retrofit — the audit flagged 05-#6 as risky]** → Behavior is preserved; the gate (`:mobile:app:testDevDebugUnitTest` + `…testDevReleaseUnitTest`) and the Robolectric screen tests (which render the same states) are the safety net. The pure-projection unit tests (`*UiStateTest`) are untouched (function reused).
- **[`uiState` initial value vs. eager combine]** → The combine's initial value is `Loading`, matching the projection for the pre-first-outcome window; the first real value lands when `_outcome`/`_isInitialLoad` first emit (synchronously on construction under the test dispatcher), so no observable flash beyond the existing skeleton.
- **[`outcome` staying public could read as "two state flows"]** → Documented here + in each spec delta as the raw-domain-state seam (distinct type, distinct purpose); a reviewer sees the rationale (cursor/anchor + PII) rather than apparent drift.
- **[K/N compile — VMs are commonMain]** → Covered by `:mobile:app:iosSimulatorArm64Test` (the existing `*FlowIosTest` suite exercises the feed data path on Native).

## Migration Plan

Pure refactor, no runtime/data migration. Per VM: introduce `uiState`, make the initial-load flag private, leave `outcome`/auxiliary flows; switch the screen to collect `uiState`; migrate the `isInitialLoad` test reads to `uiState` assertions and add the single-`uiState` + config-change scenario tests. Rollback = revert the PR (no schema/wire/state-persistence surface).

## Open Questions

None. The shape (`uiState` via `stateIn`, `isInitialLoad` private, `outcome` retained as the raw-state seam, signals separate) is settled against docs/11 §2.2 + the `mobile-auth-signin` precedent + the PII invariant.
