package id.nearyou.app.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `HomeRoute`-scoped ViewModel owning the Nearby feed's first-page load state. Resolved via
 * `viewModel { … }` inside the `HomeRoute` `NavEntry` (with `rememberViewModelStoreNavEntryDecorator`),
 * so it **survives the entry going off-screen** — e.g. while the post composer (`PostCreationRoute`)
 * is on top — and is cleared only when `HomeRoute` is popped. That is the fix for the
 * reload-on-return papercut: returning from the composer reuses the already-loaded feed instead of
 * re-running `loadFirstPage()` (which would re-acquire location + re-hit the network). This is the
 * canonical Navigation 3 state-retention pattern (design Decision 5).
 *
 * The first load runs once on construction; [reload] re-fetches (pull-to-refresh + error retry). A
 * coordinate-acquisition failure maps to the EXISTING retryable [NearbyTimelineOutcome.NetworkError]
 * (no new outcome member) — identical to the prior in-composable behavior, just hoisted off the
 * composition so it is not lost when the entry is disposed.
 *
 * Loading is split into two distinct flags (mobile-design-system § "Canonical list loading and refresh
 * pattern", design D3), replacing the prior single `inFlight`:
 * - [isInitialLoad] — true only until the FIRST outcome arrives (no content yet). Drives the screen's
 *   skeleton state via `nearbyTimelineUiState(outcome, isInitialLoad)`.
 * - [isRefreshing] — true during a [reload] while a prior outcome is RETAINED (so the list stays
 *   mounted and the screen keeps rendering `Content`). Drives only the `PullToRefreshBox` indicator.
 *
 * On [reload] the existing outcome is kept (never nulled) and [isRefreshing] is set true; the outcome
 * is swapped + [isRefreshing] cleared on completion. So there is never an initial-load skeleton AND a
 * pull-to-refresh spinner at once.
 */
class NearbyTimelineViewModel(
    private val flow: NearbyTimelineFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<NearbyTimelineOutcome?>(null)
    val outcome: StateFlow<NearbyTimelineOutcome?> = _outcome.asStateFlow()

    private val _isInitialLoad = MutableStateFlow(true)
    val isInitialLoad: StateFlow<Boolean> = _isInitialLoad.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        load(initial = true)
    }

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        // Reentrancy guard (2026-06-10 audit, 06 medium): stacked PTR + retry taps
        // raced concurrent fetches — latest-writer-wins on outcome and a flickering
        // isRefreshing. One reload at a time; the next gesture re-fires after.
        if (_isRefreshing.value || _isInitialLoad.value) return
        load(initial = false)
    }

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            // A refresh keeps the prior outcome + flips only isRefreshing (the list stays mounted, the
            // screen keeps rendering Content). The initial load keeps isInitialLoad = true (skeleton).
            if (!initial) _isRefreshing.value = true
            try {
                _outcome.value = flow.loadFirstPage()
            } catch (cancellation: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cancellation
            } catch (_: Throwable) {
                // Granted-but-no-fix: the provider could not acquire a coordinate → existing retryable
                // error state (network copy + retry). No new NearbyTimelineOutcome member is introduced.
                _outcome.value = NearbyTimelineOutcome.NetworkError
            } finally {
                // After the first outcome arrives the screen leaves the skeleton for good; subsequent
                // reloads toggle isRefreshing only.
                _isInitialLoad.value = false
                _isRefreshing.value = false
            }
        }
    }
}
