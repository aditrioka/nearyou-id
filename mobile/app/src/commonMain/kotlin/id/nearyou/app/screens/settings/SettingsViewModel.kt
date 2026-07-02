package id.nearyou.app.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.hidedistance.HideDistanceRepository
import id.nearyou.app.privateprofile.PrivateProfileRepository
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

/** One-shot Settings events for the private-profile toggle, cleared via [SettingsViewModel.onPrivateProfileEventShown]. */
enum class PrivateProfileEvent {
    /** A non-Premium caller activated the private-profile toggle → show the Premium upsell. */
    Upsell,

    /** The private-profile PATCH failed → the toggle reverted; show a non-trapping error. */
    WriteFailed,
}

/**
 * `SettingsRoute`-scoped ViewModel. Owns logout (a CLIENT-side token wipe — clears [TokenStore] and
 * raises [loggedOut] so the screen routes to sign-in; server-side bearer revoke is a pre-launch follow-up)
 * AND the Premium privacy toggles: hide-distance (the `hide-distance` capability) and private-profile
 * (the `private-profile` capability).
 *
 * On init it seeds each toggle from its repository's `loadState` (`GET /api/v1/user/{hide-distance|private-profile}`):
 * the `*Checked` flow reflects the stored flag ONLY when the caller is effectively Premium, and the
 * `*Premium` flow drives interactive-vs-upsell. Toggling: a non-Premium caller gets an `Upsell` event and
 * NO write; a Premium caller writes optimistically and reverts + emits `WriteFailed` on failure. A null
 * repository (tests that don't wire it) keeps the toggle off / non-Premium and never calls the network.
 */
class SettingsViewModel(
    private val tokenStore: TokenStore,
    private val hideDistance: HideDistanceRepository? = null,
    private val privateProfile: PrivateProfileRepository? = null,
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

    private val _privateProfileChecked = MutableStateFlow(false)
    val privateProfileChecked: StateFlow<Boolean> = _privateProfileChecked.asStateFlow()

    private val _privateProfilePremium = MutableStateFlow(false)
    val privateProfilePremium: StateFlow<Boolean> = _privateProfilePremium.asStateFlow()

    private val _privateProfileEvent = MutableStateFlow<PrivateProfileEvent?>(null)
    val privateProfileEvent: StateFlow<PrivateProfileEvent?> = _privateProfileEvent.asStateFlow()

    private val privateProfileWriting = MutableStateFlow(false)

    init {
        val hideRepo = hideDistance
        if (hideRepo != null) {
            viewModelScope.launch {
                hideRepo.loadState()?.let { state ->
                    _hideDistancePremium.value = state.premium
                    // Reflect the stored flag only when effectively Premium; a Free caller (stale TRUE or not)
                    // sees the toggle off + the upsell affordance.
                    _hideDistanceChecked.value = state.premium && state.hideDistance
                }
            }
        }
        val privateRepo = privateProfile
        if (privateRepo != null) {
            viewModelScope.launch {
                privateRepo.loadState()?.let { state ->
                    _privateProfilePremium.value = state.premium
                    _privateProfileChecked.value = state.premium && state.privateProfile
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
        // Set the in-flight flag SYNCHRONOUSLY (before launch) so a rapid second tap is guarded even
        // when both taps run before the first coroutine body executes — an airtight single-flight defense.
        hideDistanceWriting.value = true
        val previous = _hideDistanceChecked.value
        _hideDistanceChecked.value = requested
        viewModelScope.launch {
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

    /** Handle a private-profile toggle request. Premium-gated + optimistic with revert-on-failure. */
    fun onPrivateProfileToggle(requested: Boolean) {
        val repo = privateProfile
        if (repo == null || !_privateProfilePremium.value) {
            _privateProfileEvent.value = PrivateProfileEvent.Upsell
            return
        }
        if (privateProfileWriting.value) return
        // Set the in-flight flag SYNCHRONOUSLY (before launch) so a rapid second tap is guarded even
        // when both taps run before the first coroutine body executes — an airtight single-flight defense.
        privateProfileWriting.value = true
        val previous = _privateProfileChecked.value
        _privateProfileChecked.value = requested
        viewModelScope.launch {
            try {
                if (!repo.setPrivateProfile(requested)) {
                    _privateProfileChecked.value = previous
                    _privateProfileEvent.value = PrivateProfileEvent.WriteFailed
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                privateProfileWriting.value = false
            }
        }
    }

    fun onPrivateProfileEventShown() {
        _privateProfileEvent.value = null
    }
}
