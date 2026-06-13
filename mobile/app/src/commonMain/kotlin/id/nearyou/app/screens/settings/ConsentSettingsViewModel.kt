package id.nearyou.app.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.consent.ConsentFlow
import id.nearyou.app.consent.ConsentOutcome
import id.nearyou.app.data.consent.ConsentSnapshot
import id.nearyou.app.data.consent.ConsentSnapshotStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `ConsentSettingsRoute`-scoped ViewModel for the Settings › "Privasi & data" sub-screen. Initializes
 * the three toggles from the device-local [ConsentSnapshotStore] (the last-submitted triple) or — when
 * no snapshot exists — the **V2 column defaults: analytics OFF, crash ON, ads OFF** (no consent-read is
 * issued; there is no GET endpoint). Submits via the REUSED [ConsentFlow] (`PATCH /api/v1/user/consent`,
 * `mobile-analytics-consent` seam — NO second consent path), and on `200` writes the just-submitted
 * triple back to the snapshot store so a later re-entry seeds from it.
 *
 * The double-submit guard is twofold: the [inFlight] flag here AND the `ConsentRepository`'s `Mutex`
 * (returns `Cancelled` on a concurrent call) — so a rapid double-tap issues exactly one `PATCH`.
 */
class ConsentSettingsViewModel(
    private val flow: ConsentFlow,
    private val snapshotStore: ConsentSnapshotStore,
) : ViewModel() {
    private val seed: ConsentSnapshot =
        snapshotStore.read() ?: ConsentSnapshot(analytics = false, crash = true, adsPersonalization = false)

    private val _analytics = MutableStateFlow(seed.analytics)
    val analytics: StateFlow<Boolean> = _analytics.asStateFlow()

    private val _crash = MutableStateFlow(seed.crash)
    val crash: StateFlow<Boolean> = _crash.asStateFlow()

    private val _ads = MutableStateFlow(seed.adsPersonalization)
    val ads: StateFlow<Boolean> = _ads.asStateFlow()

    private val _outcome = MutableStateFlow<ConsentOutcome?>(null)
    val outcome: StateFlow<ConsentOutcome?> = _outcome.asStateFlow()

    private val _inFlight = MutableStateFlow(false)
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    fun setAnalytics(value: Boolean) {
        _analytics.value = value
    }

    fun setCrash(value: Boolean) {
        _crash.value = value
    }

    fun setAds(value: Boolean) {
        _ads.value = value
    }

    fun save() {
        if (_inFlight.value) return
        viewModelScope.launch {
            _inFlight.value = true
            try {
                val result = flow.submitConsent(_analytics.value, _crash.value, _ads.value)
                _outcome.value = result
                if (result == ConsentOutcome.Success) {
                    snapshotStore.write(
                        ConsentSnapshot(
                            analytics = _analytics.value,
                            crash = _crash.value,
                            adsPersonalization = _ads.value,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                _inFlight.value = false
            }
        }
    }
}
