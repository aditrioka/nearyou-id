package id.nearyou.app.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.hidedistance.HideDistanceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** One-shot Settings events surfaced as a snackbar then cleared via [SettingsViewModel.onHideDistanceEventShown]. */
enum class HideDistanceEvent {
    /** A non-Premium caller activated the hide-distance toggle → show the Premium upsell. */
    Upsell,

    /** The hide-distance PATCH failed → the toggle reverted; show a non-trapping error. */
    WriteFailed,
}

/**
 * `SettingsRoute`-scoped ViewModel. Owns logout (a CLIENT-side token wipe — clears [TokenStore] and
 * raises [loggedOut] so the screen routes to sign-in; server-side bearer revoke is a pre-launch follow-up)
 * AND the Premium hide-distance toggle (the `hide-distance` capability).
 *
 * On init it seeds the toggle from [HideDistanceRepository.loadState] (`GET /api/v1/user/hide-distance`):
 * [hideDistanceChecked] reflects the stored flag ONLY when the caller is effectively Premium, and
 * [hideDistancePremium] drives interactive-vs-upsell. Toggling: a non-Premium caller gets [HideDistanceEvent.Upsell]
 * and NO write; a Premium caller writes optimistically and reverts + emits [HideDistanceEvent.WriteFailed] on failure.
 * A null [hideDistance] (tests that don't wire it) keeps the toggle off / non-Premium and never calls the network.
 */
class SettingsViewModel(
    private val tokenStore: TokenStore,
    private val hideDistance: HideDistanceRepository? = null,
) : ViewModel() {
    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    private val _hideDistanceChecked = MutableStateFlow(false)
    val hideDistanceChecked: StateFlow<Boolean> = _hideDistanceChecked.asStateFlow()

    private val _hideDistancePremium = MutableStateFlow(false)
    val hideDistancePremium: StateFlow<Boolean> = _hideDistancePremium.asStateFlow()

    private val _hideDistanceEvent = MutableStateFlow<HideDistanceEvent?>(null)
    val hideDistanceEvent: StateFlow<HideDistanceEvent?> = _hideDistanceEvent.asStateFlow()

    private val hideDistanceWriting = MutableStateFlow(false)

    init {
        val repo = hideDistance
        if (repo != null) {
            viewModelScope.launch {
                repo.loadState()?.let { state ->
                    _hideDistancePremium.value = state.premium
                    // Reflect the stored flag only when effectively Premium; a Free caller (stale TRUE or not)
                    // sees the toggle off + the upsell affordance.
                    _hideDistanceChecked.value = state.premium && state.hideDistance
                }
            }
        }
    }

    fun confirmLogout() {
        viewModelScope.launch {
            tokenStore.clear()
            _loggedOut.value = true
        }
    }

    /** Handle a toggle request from the row or its switch. Premium-gated + optimistic with revert-on-failure. */
    fun onHideDistanceToggle(requested: Boolean) {
        val repo = hideDistance
        if (repo == null || !_hideDistancePremium.value) {
            _hideDistanceEvent.value = HideDistanceEvent.Upsell
            return
        }
        if (hideDistanceWriting.value) return
        val previous = _hideDistanceChecked.value
        _hideDistanceChecked.value = requested
        viewModelScope.launch {
            hideDistanceWriting.value = true
            try {
                if (!repo.setHideDistance(requested)) {
                    _hideDistanceChecked.value = previous
                    _hideDistanceEvent.value = HideDistanceEvent.WriteFailed
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                hideDistanceWriting.value = false
            }
        }
    }

    fun onHideDistanceEventShown() {
        _hideDistanceEvent.value = null
    }
}
