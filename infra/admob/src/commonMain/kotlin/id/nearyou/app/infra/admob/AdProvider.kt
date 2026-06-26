package id.nearyou.app.infra.admob

/**
 * How an ad request treats personalization. [NON_PERSONALIZED] is the mandatory fallback (docs/01 §
 * Privacy Compliance) — requested whenever the user's stored `ads_personalization` is OFF / declined,
 * enforced client-side via the SDK request extra (`npa=1`), independent of UMP regional behaviour.
 */
enum class AdRequestMode { PERSONALIZED, NON_PERSONALIZED }

/**
 * Vendor-free projection of the UMP consent status. The provider maps the SDK's consent enum onto this so
 * `:mobile:app` never imports a UMP type. [OBTAINED] / [NOT_REQUIRED] are the ad-eligible terminal states;
 * [REQUIRED] means a form must be shown; [ERROR] is the fail-safe (no ads, never blocks the feed).
 */
enum class ConsentState { UNKNOWN, REQUIRED, OBTAINED, NOT_REQUIRED, ERROR }

/**
 * A loaded native ad, projected vendor-free so the Google SDK native-ad type never crosses the
 * `:mobile:app` boundary (invariant #16). The display fields drive the card chrome; [handle] is the opaque
 * platform native-ad object (`com.google.android.gms.ads.nativead.NativeAd` / `GADNativeAd`) that the
 * platform [NativeAdSurface] binds into the registered `NativeAdView` / `GADNativeAdView` so impressions
 * and clicks track per AdMob policy. `:mobile:app` treats [handle] as an opaque [Any] — it is NEVER cast or
 * inspected outside `:infra:admob`.
 */
data class NativeAdContent(
    val headline: String?,
    val body: String?,
    val advertiser: String?,
    val callToAction: String?,
    val iconUrl: String?,
    val handle: Any?,
)

/**
 * The vendor-SDK-FREE ad-serving seam for the `mobile-ads` capability. The production bindings
 * (`AndroidAdProvider` / `IosAdProvider`, in this module alongside the Google SDK) fence the vendor import;
 * `:mobile:app` consumes ONLY this interface + [NativeAdContent], and commonTest substitutes a
 * `FakeAdProvider`. Mirrors the docs/11 §2.6 vendor-SDK `:infra` seam (the `PurchaseController` /
 * `ChatRealtimeSubscriber` precedent).
 *
 * Contract: nothing throws a vendor exception across the boundary — every failure maps to a return value
 * ([ConsentState.ERROR] / a null [NativeAdContent]) so the feed renders normally with no error chrome
 * (fail-safe). The caller initializes + requests consent ONLY when the server `ads-config` reports ads
 * enabled for the viewer (premium suppression + the `ads_enabled` kill-switch are decided server-side).
 */
interface AdProvider {
    /** Initialize the underlying Ads SDK once. Idempotent; safe to call before each session's first load. */
    suspend fun initialize()

    /**
     * Request UMP consent information and present the consent form when UMP reports it is required (UU PDP).
     * Returns the resolved [ConsentState]; a request/show failure resolves to [ConsentState.ERROR] (no ads).
     * MUST be called (and resolve to an ad-eligible state) BEFORE any [loadNativeAd].
     */
    suspend fun requestConsent(): ConsentState

    /** The last resolved consent state (without re-requesting). [ConsentState.UNKNOWN] until first request. */
    fun consentState(): ConsentState

    /**
     * Load a single native ad for [adUnitId], applying [mode] (forcing non-personalized via the `npa` extra
     * when [AdRequestMode.NON_PERSONALIZED]). Returns the vendor-free [NativeAdContent], or `null` on any
     * load failure (fail-safe — no slot rendered). The request carries NO precise device location
     * (data-minimization, UU PDP / docs/06): the provider never sets a `location` on the request.
     */
    suspend fun loadNativeAd(
        adUnitId: String,
        mode: AdRequestMode,
    ): NativeAdContent?

    /** Release any retained native-ad / SDK resources (call on feed-item disposal / `onCleared`). */
    fun dispose()
}
