package id.nearyou.app.ads

import id.nearyou.app.data.ads.AdsConfigFlow
import id.nearyou.app.data.ads.AdsConfigOutcome
import id.nearyou.app.data.consent.ConsentSnapshot
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.infra.admob.AdProvider
import id.nearyou.app.infra.admob.AdRequestMode
import id.nearyou.app.infra.admob.ConsentState
import id.nearyou.app.infra.admob.NativeAdContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UNIT = "test-unit"

private class RecordingAdProvider(
    private val consentResult: ConsentState,
    private val ad: NativeAdContent? = NativeAdContent("Head", "Body", "Adv", "Cta", null, handle = null),
) : AdProvider {
    val calls = mutableListOf<String>()
    val loadArgs = mutableListOf<Pair<String, AdRequestMode>>()

    override suspend fun initialize() {
        calls += "initialize"
    }

    override suspend fun requestConsent(): ConsentState {
        calls += "requestConsent"
        return consentResult
    }

    override fun consentState(): ConsentState = consentResult

    override suspend fun loadNativeAd(
        adUnitId: String,
        mode: AdRequestMode,
    ): NativeAdContent? {
        calls += "loadNativeAd"
        loadArgs += adUnitId to mode
        return ad
    }

    override fun dispose() {
        calls += "dispose"
    }
}

private class FakeConsentStore(private val snapshot: ConsentSnapshot?) : ConsentSnapshotStore {
    override fun read(): ConsentSnapshot? = snapshot

    override fun write(snapshot: ConsentSnapshot) = Unit
}

private fun consent(adsPersonalization: Boolean) =
    ConsentSnapshot(analytics = false, crash = false, adsPersonalization = adsPersonalization)

private fun controller(
    outcome: AdsConfigOutcome,
    provider: RecordingAdProvider,
    snapshot: ConsentSnapshot? = consent(false),
) = AdFeedController(
    adsConfigFlow = AdsConfigFlow { outcome },
    adProvider = provider,
    consentStore = FakeConsentStore(snapshot),
    adUnitId = UNIT,
)

/** Ad-eligibility + UMP gate ordering + data-minimization coverage with a fake provider (task 5.2). */
class AdFeedControllerTest {
    @Test
    fun `consent is requested before any ad load and frequency is published`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            val controller = controller(AdsConfigOutcome.Enabled(6), provider)

            controller.prepare()
            assertEquals(6, controller.frequency.value)

            controller.loadAd("ad:6")
            val consentIdx = provider.calls.indexOf("requestConsent")
            val loadIdx = provider.calls.indexOf("loadNativeAd")
            assertTrue(consentIdx >= 0, "consent must be requested")
            assertTrue(loadIdx >= 0, "ad must load when eligible")
            assertTrue(consentIdx < loadIdx, "UMP consent must precede the first ad load")
            // initialize precedes consent too.
            assertTrue(provider.calls.indexOf("initialize") < consentIdx)
        }

    @Test
    fun `a consent failure yields no ads - fail-safe`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.ERROR)
            val controller = controller(AdsConfigOutcome.Enabled(6), provider)

            controller.prepare()
            assertNull(controller.frequency.value)
            assertNull(controller.loadAd("ad:6"))
            assertFalse(provider.calls.contains("loadNativeAd"))
        }

    @Test
    fun `a disabled config initializes nothing and serves no ads`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            val controller = controller(AdsConfigOutcome.Disabled, provider)

            controller.prepare()
            assertNull(controller.frequency.value)
            assertNull(controller.loadAd("ad:6"))
            assertTrue(provider.calls.isEmpty()) // no initialize, no consent, no load
        }

    @Test
    fun `personalization OFF forces a non-personalized request`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            val controller = controller(AdsConfigOutcome.Enabled(6), provider, snapshot = consent(false))

            controller.prepare()
            controller.loadAd("ad:6")
            assertEquals(AdRequestMode.NON_PERSONALIZED, provider.loadArgs.single().second)
        }

    @Test
    fun `personalization ON with consent allows a personalized request`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            val controller = controller(AdsConfigOutcome.Enabled(6), provider, snapshot = consent(true))

            controller.prepare()
            controller.loadAd("ad:6")
            assertEquals(AdRequestMode.PERSONALIZED, provider.loadArgs.single().second)
        }

    @Test
    fun `the ad request carries no precise coordinate - data minimization`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            val controller = controller(AdsConfigOutcome.Enabled(6), provider)

            controller.prepare()
            controller.loadAd("ad:6")
            // The load seam is (adUnitId, mode) ONLY — there is structurally no lat/lng channel to the SDK.
            val (unit, _) = provider.loadArgs.single()
            assertEquals(UNIT, unit)
        }

    @Test
    fun `the same slot loads at most one ad - cached single-flight`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            val controller = controller(AdsConfigOutcome.Enabled(6), provider)

            controller.prepare()
            controller.loadAd("ad:6")
            controller.loadAd("ad:6")
            assertEquals(1, provider.calls.count { it == "loadNativeAd" })
        }
}
