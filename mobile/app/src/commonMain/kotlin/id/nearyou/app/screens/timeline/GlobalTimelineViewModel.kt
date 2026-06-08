package id.nearyou.app.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `HomeRoute`-scoped ViewModel owning the Global feed's first-page load state. Resolved via
 * `viewModel { … }` inside the Global tab's screen — which (design D1/D2) composes **directly** under
 * the `HomeRoute` `NavEntry`, so the VM binds to the `HomeRoute` ViewModel store and **survives both
 * tab switches and the composer round-trip**, cleared only when `HomeRoute` is popped. That is what
 * makes returning to the Global tab reuse the already-loaded feed instead of re-running
 * `loadFirstPage()` — mirroring the shipped `NearbyTimelineViewModel`.
 *
 * The first load runs once on construction; [reload] re-fetches (pull-to-refresh + error retry). A
 * load failure maps to the EXISTING retryable [GlobalTimelineOutcome.NetworkError] (no new outcome
 * member).
 *
 * Loading is split into [isInitialLoad] (true until the first outcome arrives — drives the skeleton)
 * and [isRefreshing] (true during a [reload] while a prior outcome is retained — drives only the
 * `PullToRefreshBox` indicator), replacing the prior single `inFlight` flag (mobile-design-system §
 * "Canonical list loading and refresh pattern", design D3). On [reload] the existing outcome is kept
 * and only [isRefreshing] flips, so the list stays mounted (Content) and there is never a skeleton +
 * pull-to-refresh spinner at once. Mirrors `NearbyTimelineViewModel`.
 */
class GlobalTimelineViewModel(
    private val flow: GlobalTimelineFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<GlobalTimelineOutcome?>(null)
    val outcome: StateFlow<GlobalTimelineOutcome?> = _outcome.asStateFlow()

    private val _isInitialLoad = MutableStateFlow(true)
    val isInitialLoad: StateFlow<Boolean> = _isInitialLoad.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        load(initial = true)
    }

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        load(initial = false)
    }

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            if (!initial) _isRefreshing.value = true
            try {
                _outcome.value = flow.loadFirstPage()
            } catch (cancellation: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cancellation
            } catch (_: Throwable) {
                // The fetch threw → existing retryable error state (network copy + retry). No new
                // GlobalTimelineOutcome member is introduced.
                _outcome.value = GlobalTimelineOutcome.NetworkError
            } finally {
                _isInitialLoad.value = false
                _isRefreshing.value = false
            }
        }
    }
}
