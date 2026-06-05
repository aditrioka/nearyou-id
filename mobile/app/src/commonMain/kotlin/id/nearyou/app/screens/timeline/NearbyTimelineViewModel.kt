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
 */
class NearbyTimelineViewModel(
    private val flow: NearbyTimelineFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<NearbyTimelineOutcome?>(null)
    val outcome: StateFlow<NearbyTimelineOutcome?> = _outcome.asStateFlow()

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
                // Granted-but-no-fix: the provider could not acquire a coordinate → existing retryable
                // error state (network copy + retry). No new NearbyTimelineOutcome member is introduced.
                _outcome.value = NearbyTimelineOutcome.NetworkError
            } finally {
                _inFlight.value = false
            }
        }
    }
}
