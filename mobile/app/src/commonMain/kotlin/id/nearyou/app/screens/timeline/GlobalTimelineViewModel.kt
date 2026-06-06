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
 */
class GlobalTimelineViewModel(
    private val flow: GlobalTimelineFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<GlobalTimelineOutcome?>(null)
    val outcome: StateFlow<GlobalTimelineOutcome?> = _outcome.asStateFlow()

    private val _inFlight = MutableStateFlow(false)
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    init {
        load()
    }

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1. */
    fun reload() {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _inFlight.value = true
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
                _inFlight.value = false
            }
        }
    }
}
