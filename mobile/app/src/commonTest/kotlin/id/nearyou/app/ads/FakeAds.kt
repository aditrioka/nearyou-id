package id.nearyou.app.ads

import id.nearyou.app.data.ads.AdsConfigFlow
import id.nearyou.app.data.ads.AdsConfigOutcome
import id.nearyou.app.data.consent.ConsentSnapshot
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.infra.admob.AdProvider
import id.nearyou.app.infra.admob.AdRequestMode
import id.nearyou.app.infra.admob.ConsentState
import id.nearyou.app.infra.admob.NativeAdContent

/** A loaded test native ad whose handle is null → [id.nearyou.app.infra.admob.NativeAdSurface] renders the
 *  vendor-free fields as text, so the slot + label assert without a live Google native ad. */
fun sampleNativeAd(): NativeAdContent =
    NativeAdContent(
        headline = "Iklan Uji",
        body = "Body iklan uji",
        advertiser = "Pengiklan",
        callToAction = "Pasang",
        iconUrl = null,
        handle = null,
    )

/** A configurable fake [AdProvider] for screen/controller tests (no vendor SDK). */
class FakeAdProvider(
    private val consentResult: ConsentState = ConsentState.OBTAINED,
    private val ad: NativeAdContent? = sampleNativeAd(),
) : AdProvider {
    override suspend fun initialize() = Unit

    override suspend fun requestConsent(): ConsentState = consentResult

    override fun consentState(): ConsentState = consentResult

    override suspend fun loadNativeAd(
        adUnitId: String,
        mode: AdRequestMode,
    ): NativeAdContent? = ad

    override fun dispose() = Unit
}

private class FixedConsentStore(private val adsPersonalization: Boolean) : ConsentSnapshotStore {
    override fun read(): ConsentSnapshot = ConsentSnapshot(analytics = false, crash = false, adsPersonalization = adsPersonalization)

    override fun write(snapshot: ConsentSnapshot) = Unit
}

/** Builds an [AdFeedController] over fakes — `enabled = true` → ads serve at [frequency]; false → none. */
fun fakeAdFeedController(
    enabled: Boolean,
    frequency: Int = 6,
    consent: ConsentState = ConsentState.OBTAINED,
    ad: NativeAdContent? = sampleNativeAd(),
    adsPersonalization: Boolean = false,
): AdFeedController =
    AdFeedController(
        adsConfigFlow = AdsConfigFlow { if (enabled) AdsConfigOutcome.Enabled(frequency) else AdsConfigOutcome.Disabled },
        adProvider = FakeAdProvider(consent, ad),
        consentStore = FixedConsentStore(adsPersonalization),
        adUnitId = "test-unit",
    )
