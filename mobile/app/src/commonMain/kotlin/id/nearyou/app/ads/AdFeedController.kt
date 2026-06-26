package id.nearyou.app.ads

import id.nearyou.app.data.ads.AdsConfigFlow
import id.nearyou.app.data.ads.AdsConfigOutcome
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.infra.admob.AdProvider
import id.nearyou.app.infra.admob.AdRequestMode
import id.nearyou.app.infra.admob.ConsentState
import id.nearyou.app.infra.admob.NativeAdContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Maps the stored `ads_personalization` consent to the ad request mode (the mandatory non-personalized
 * fallback, docs/01 / design D3). Pure + standalone so the mapping is unit-testable (task 5.1).
 */
fun adRequestModeFor(adsPersonalization: Boolean): AdRequestMode =
    if (adsPersonalization) AdRequestMode.PERSONALIZED else AdRequestMode.NON_PERSONALIZED

/**
 * App-singleton ad-eligibility + native-ad load controller for the timeline feeds
 * (mobile-admob-ads-foundation). Shared across Nearby/Following/Global so the SDK initializes + the UMP
 * gate runs at most once per session.
 *
 * [prepare] (single-flight) fetches the server `ads-config` and, ONLY when ads are enabled for the viewer,
 * initializes the [AdProvider] + runs the UMP consent gate BEFORE any ad loads; it then publishes the
 * `timelineFrequency` on [frequency] (null = no ads). A disabled config, a consent failure, or a
 * not-obtained consent all resolve to `frequency = null` (fail-safe — no ads, no error chrome).
 *
 * [loadAd] loads + caches ONE native ad per slot key off the composition path (the caller invokes it from a
 * `produceState`/`LaunchedEffect` coroutine, never during recomposition — docs/11 §2.4 / task 4.6),
 * applying the [AdRequestMode] derived from the stored `ads_personalization`. [dispose] releases the cached
 * ads + the provider.
 */
class AdFeedController(
    private val adsConfigFlow: AdsConfigFlow,
    private val adProvider: AdProvider,
    private val consentStore: ConsentSnapshotStore,
    private val adUnitId: String,
) {
    private val _frequency = MutableStateFlow<Int?>(null)
    val frequency: StateFlow<Int?> = _frequency.asStateFlow()

    private val prepareMutex = Mutex()
    private var prepared = false
    private var requestMode: AdRequestMode = AdRequestMode.NON_PERSONALIZED

    private val loadMutex = Mutex()
    private val loadedAds = mutableMapOf<String, NativeAdContent?>()

    suspend fun prepare() {
        prepareMutex.withLock {
            if (prepared) return
            prepared = true
            when (val outcome = adsConfigFlow.fetchConfig()) {
                is AdsConfigOutcome.Enabled -> {
                    adProvider.initialize()
                    // UMP consent is requested BEFORE any ad load (spec § "UMP consent gate before any ad
                    // loads"). Only an ad-eligible terminal state proceeds; anything else → no ads.
                    val consent = adProvider.requestConsent()
                    if (consent == ConsentState.OBTAINED || consent == ConsentState.NOT_REQUIRED) {
                        val personalization = consentStore.read()?.adsPersonalization == true
                        requestMode = adRequestModeFor(personalization)
                        _frequency.value = outcome.timelineFrequency
                    } else {
                        _frequency.value = null
                    }
                }
                AdsConfigOutcome.Disabled -> _frequency.value = null
            }
        }
    }

    suspend fun loadAd(slotKey: String): NativeAdContent? {
        if (_frequency.value == null) return null
        return loadMutex.withLock {
            if (loadedAds.containsKey(slotKey)) {
                loadedAds[slotKey]
            } else {
                val content = adProvider.loadNativeAd(adUnitId, requestMode)
                loadedAds[slotKey] = content
                content
            }
        }
    }

    fun dispose() {
        loadedAds.clear()
        adProvider.dispose()
    }
}
