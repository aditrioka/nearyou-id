package id.nearyou.app.ads

import id.nearyou.app.data.ads.AdsConfigFlow
import id.nearyou.app.data.ads.AdsConfigOutcome
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.infra.admob.AdProvider
import id.nearyou.app.infra.admob.AdRequestMode
import id.nearyou.app.infra.admob.ConsentState
import id.nearyou.app.infra.admob.NativeAdContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
 * gate runs at most once per signed-in session (premium-entitlement-lifecycle keys the latch on
 * `PremiumEntitlementSession.sessionKey`, so even a sign-out → sign-in into the same account re-evaluates).
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
    // premium-entitlement-lifecycle: a confirmed client purchase suppresses ads (it can only REMOVE them — the
    // server ads-config remains the sole source that enables them), covering the window before the webhook.
    private val purchaseConfirmed: StateFlow<Boolean> = MutableStateFlow(false),
    // The signed-in SESSION the prepare latch is keyed to (PremiumEntitlementSession.sessionKey): any sign-out →
    // sign-in — even back into the same account — re-evaluates eligibility instead of reusing a stale frequency.
    private val currentSessionKey: suspend () -> String? = { null },
) {
    private val _frequency = MutableStateFlow<Int?>(null)

    /** The published ad frequency (null = no ads); null while a purchase is confirmed. */
    val frequency: Flow<Int?> = combine(_frequency, purchaseConfirmed) { f, confirmed -> f.takeUnless { confirmed } }

    /** The current [frequency] value, read synchronously — seeds the UI collector so feed re-entry never
     *  renders one ad-less frame before the cold [frequency] flow emits. */
    val currentFrequency: Int? get() = _frequency.value.takeUnless { purchaseConfirmed.value }

    private val prepareMutex = Mutex()
    private var prepared = false
    private var preparedFor: String? = null
    private var requestMode: AdRequestMode = AdRequestMode.NON_PERSONALIZED

    private val loadMutex = Mutex()
    private val loadedAds = mutableMapOf<String, NativeAdContent?>()

    suspend fun prepare() {
        prepareMutex.withLock {
            val session = currentSessionKey()
            if (prepared && session == preparedFor) return
            // First prepare, or a new session since the last one: forget the previous eligibility + ads.
            prepared = true
            preparedFor = session
            _frequency.value = null
            loadMutex.withLock { loadedAds.clear() }
            // Premium viewers see zero ads: no SDK init, no UMP form.
            if (purchaseConfirmed.value) return
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
        if (_frequency.value == null || purchaseConfirmed.value) return null
        // A slot rendered in the gap before a NEW session's prepare() must not load on the previous session's
        // eligibility (e.g. a Premium account seeing the prior Free account's frequency for a frame).
        if (currentSessionKey() != preparedFor) return null
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

    // ponytail: no caller today — this controller is a Koin app-singleton with no onCleared, so loaded ads
    // are retained for the process lifetime (bounded: one per feed slot the user scrolled past). Acceptable
    // while ads are gated OFF pre-launch; wire this to a real lifecycle owner (or a memory-pressure hook)
    // when ads go live if the retained-ad count matters. Tracked with the ads follow-ups (#442–#444).
    fun dispose() {
        loadedAds.clear()
        adProvider.dispose()
    }
}
