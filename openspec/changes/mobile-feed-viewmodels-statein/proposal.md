## Why

`docs/11-Engineering-Standards.md` §2.2 mandates that every screen-level ViewModel "**Expose ONE `StateFlow<XxxUiState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`**". The four feed/list ViewModels still violate this: `NearbyTimelineViewModel`, `GlobalTimelineViewModel`, `FollowingTimelineViewModel`, and `NotificationsViewModel` each expose the raw `outcome` + `isInitialLoad` `StateFlow`s and run the pure projection (`xxxTimelineUiState(outcome, isInitialLoad)`) **in the composable** via `remember(outcome, isInitialLoad) { … }` — so the "one UiState" contract is split across two hot flows plus the UI layer. This is 2026-06-10 holistic-audit finding **05-#6** (MEDIUM). It is a baseline deviation any new screen copies: `FollowingTimelineViewModel`, added *after* the audit, copied the shape verbatim — proof the drift propagates. The single-`stateIn` shape is already the established convention for `SignInViewModel`/`AgeGateViewModel`/`ConsentViewModel` (`mobile-auth-flow-viewmodels`, PR #405), `UsernameCustomizationViewModel`, and `PaywallViewModel`; this aligns the feed family to it.

## What Changes

- Each of the four ViewModels exposes exactly one `uiState: StateFlow<XxxTimelineUiState>` produced via `combine(_outcome, _isInitialLoad) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …Loading)`, **delegating to the existing pure projection function unchanged** (reused, never reimplemented — mirroring `mobile-auth-signin`'s "the projection's value equals `signInUiState(…)`").
- The public `outcome` / `isInitialLoad` `StateFlow` exposures are **removed**; the private `_outcome` / `_isInitialLoad` `MutableStateFlow`s stay (the internal `InlineLikeController` / `LoadMoreController` / `authorUserIdForPost` / mark-read + nav-resolution paths already read the private fields).
- The independently-varying signals stay as **separate** flows (per §2.2, "data class when fields vary independently"): `isRefreshing` (pull-to-refresh), `isLoadingMore` / `loadMoreError` (footer), `likeCapRetryAfterSeconds` (like-cap dialog), Nearby's `selectedRadiusM` / `isPremiumKnown` / `radiusUpsell`, and Notifications' `pendingNavTarget` / `postUnavailable` / `resolvingRowId`. They are **not** folded into the content `uiState`.
- Each screen drops its `val outcome` / `val isInitialLoad` collects and the in-composable `remember(…) { xxxTimelineUiState(…) }`, and instead collects `val uiState by viewModel.uiState.collectAsStateWithLifecycle()` (already imported in all four screens — audit 05-#13 shipped).
- **Scope = four ViewModels** (a fork-avoidance correction to the audit's "3"): the audit named Nearby/Global/Notifications because `FollowingTimelineViewModel` did not exist at audit time. Following has the identical shape and is migrated in the same change so retrofitting three does not fork the convention.
- This is a **behavior-preserving** internal state-exposure refactor: identical content states + transitions, identical PII discipline, identical config-change survival (the VM is already entry-scoped and already retains `_outcome`). No API/wire/schema change, no new dependency. **Not breaking.**

## Capabilities

### New Capabilities

<!-- none — no new capability is introduced -->

### Modified Capabilities

- `mobile-nearby-timeline`: the VM SHALL expose one `uiState` `StateFlow` via `stateIn(WhileSubscribed(5_000))` delegating to the unchanged `nearbyTimelineUiState(…)`; the screen collects that single state (no in-composable projection).
- `mobile-global-timeline`: same single-`uiState` requirement, delegating to `globalTimelineUiState(…)`.
- `mobile-following-timeline`: same single-`uiState` requirement, delegating to `followingTimelineUiState(…)`.
- `mobile-notifications-list`: same single-`uiState` requirement, delegating to `notificationsUiState(…)`.

## Impact

- **Code (`:mobile:app`, commonMain)**: `screens/timeline/{Nearby,Global,Following}TimelineViewModel.kt` + `screens/notifications/NotificationsViewModel.kt` (add `uiState`, drop public `outcome`/`isInitialLoad`); `screens/timeline/{Nearby,Global,Following}TimelineScreen.kt` + `screens/notifications/NotificationsScreen.kt` (collect the single `uiState`). The pure projection files (`*UiState.kt`) are unchanged.
- **Tests**: `GlobalTimelineViewModelTest` + `FollowingTimelineViewModelTest` migrate their `.outcome` / `.isInitialLoad` assertions to `.uiState` (collected via a `backgroundScope` collector to activate `WhileSubscribed`, per `SignInViewModelTest`); `NearbyTimelineViewModelTest` + `NotificationsViewModelTest` gain the single-`uiState` + config-change-retention scenarios; the existing pure-projection unit tests (`NearbyTimelineUiStateTest` etc.) stay valid (function reused); the Robolectric screen tests render the same states (verify unchanged). Kotlin/Native compile is covered by `:mobile:app:iosSimulatorArm64Test` (the VMs are commonMain).
- **No backend / admin / wire / schema impact.**
