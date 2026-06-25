package id.nearyou.app.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.location.LocationGate
import id.nearyou.app.location.LocationGateUiState
import id.nearyou.app.location.LocationPermissionController
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * `HomeRoute`-scoped ViewModel hosting the Nearby pre-fetch [LocationGate] orchestrator (2026-06-10 audit,
 * finding 05-#12). Resolved via `viewModel { … }` at the top of [NearbyTimelineScreen] — which IS a page of
 * the `HomeRoute` `NavEntry` (with `rememberViewModelStoreNavEntryDecorator`) — so the gate state **survives
 * the Nearby page going off-screen** (a pager swipe to Following/Global, or the composer on top), mirroring
 * the `HomeRoute`-scoped [NearbyTimelineViewModel] beside it.
 *
 * This replaces the legacy `remember { LocationGate(controller) }` the screen used to hold. That
 * composition-scoped holder was rebuilt — state reset to [LocationGateUiState.Loading], the OS status
 * re-queried — on every swipe back to Nearby while the feed VM survived in the store; it was the clearest
 * mixed-pattern screen (VM + custom holder + `remember`). Hosting the gate in the retained VM removes that
 * rebuild and the third holder kind, so the screen observes a single ViewModel (docs/11 §2.2). The
 * `ON_RESUME` re-check is preserved (the screen drives [refresh] on every foreground entry, which NEVER
 * fires the OS prompt), so a return from the OS Settings screen still reflects a newly-granted permission
 * without a cold restart.
 *
 * [LocationGate] stays the Compose-free, fake-driven orchestrator (`openspec/specs/mobile-location`) and is
 * owned here as a VM-internal collaborator — mirroring how [NearbyTimelineViewModel] owns its
 * `InlineLikeController` / `LoadMoreController`; its rationale-vs-prompt decision logic keeps its dedicated
 * `LocationGateTest`. The VM adds entry-scoped survival and moves the `viewModelScope.launch` off the
 * composable (docs/11 §2.2: business/data work never launches from composables), and is the single seam the
 * screen routes the gate actions and the "Buka Pengaturan" deep link through.
 */
class LocationGateViewModel(
    private val controller: LocationPermissionController,
) : ViewModel() {
    private val gate = LocationGate(controller)

    /** The single gate UI state (docs/11 §2.2): the [LocationGate]'s projection, observed by the screen. */
    val uiState: StateFlow<LocationGateUiState> = gate.state

    /**
     * Re-query the OS permission and re-project it. Driven by the screen on every `ON_RESUME` (first entry +
     * every foreground return). NEVER fires the OS prompt, so a prior denial does not re-nag the rationale on
     * re-entry (`openspec/specs/mobile-location` § "A prior denial does not re-show the rationale on every
     * Nearby visit").
     */
    fun refresh() {
        viewModelScope.launch { gate.refresh() }
    }

    /** User accepted the consent rationale → fire the OS prompt once and re-project the resulting status. */
    fun onRationaleAccepted() {
        viewModelScope.launch { gate.onRationaleAccepted() }
    }

    /** User declined the consent rationale → denial fallback, with NO OS prompt forced. */
    fun onRationaleDeclined() {
        gate.onRationaleDeclined()
    }

    /** Deep-links to the OS app-settings screen so the user can flip a denied permission (the denial CTA). */
    fun openAppSettings() {
        controller.openAppSettings()
    }
}
