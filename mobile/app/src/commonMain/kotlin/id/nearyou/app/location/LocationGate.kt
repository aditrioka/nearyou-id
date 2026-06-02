package id.nearyou.app.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Compose-free orchestrator of the Nearby location gate — it owns the rationale-vs-prompt decision
 * logic so it is unit-testable in `commonTest` with a `FakeLocationPermissionController` (which has
 * no Compose runner). The Nearby screen `remember`s one of these, collects [state], and renders the
 * consent modal / denial fallback / fetch path off it; the screen wires the modal's accept →
 * [onRationaleAccepted] and decline → [onRationaleDeclined].
 *
 * Re-entry safety (`openspec/specs/mobile-location` § "A prior denial does not re-show the rationale
 * on every Nearby visit"): [refresh] re-projects the OS status WITHOUT calling
 * [LocationPermissionController.request], and a `DENIED` status projects to
 * [LocationGateUiState.Denied] — so re-composition never re-fires the OS prompt. The controller's
 * `request()` count only increments on an explicit [onRationaleAccepted].
 */
class LocationGate(
    private val controller: LocationPermissionController,
) {
    private val _state = MutableStateFlow<LocationGateUiState>(LocationGateUiState.Loading)
    val state: StateFlow<LocationGateUiState> = _state.asStateFlow()

    /** Query the OS status and project it. Called on entry / re-entry. NEVER fires the OS prompt. */
    suspend fun refresh() {
        _state.value = locationGateUiState(controller.status())
    }

    /** User accepted the consent rationale → fire the OS prompt and re-project the resulting status. */
    suspend fun onRationaleAccepted() {
        _state.value = locationGateUiState(controller.request())
    }

    /**
     * User declined the consent rationale → denial fallback, with NO OS prompt forced
     * (`openspec/specs/mobile-location` § "Declining the rationale SHALL NOT force the OS prompt and
     * SHALL leave the user in the denial fallback").
     */
    fun onRationaleDeclined() {
        _state.value = LocationGateUiState.Denied
    }
}
