package id.nearyou.app.ads

import id.nearyou.app.billing.MutableSelfUserId
import id.nearyou.app.billing.PremiumEntitlementSession
import id.nearyou.app.data.ads.AdsConfigFlow
import id.nearyou.app.data.ads.AdsConfigOutcome
import id.nearyou.app.data.consent.ConsentSnapshot
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.infra.admob.AdProvider
import id.nearyou.app.infra.admob.AdRequestMode
import id.nearyou.app.infra.admob.ConsentState
import id.nearyou.app.infra.admob.NativeAdContent
import id.nearyou.app.screens.paywall.FakePurchaseController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

/** A config seam that counts fetches and answers with the current [outcome] (switchable per account). */
private class CountingAdsConfig(var outcome: AdsConfigOutcome) : AdsConfigFlow {
    var fetches = 0

    override suspend fun fetchConfig(): AdsConfigOutcome {
        fetches++
        return outcome
    }
}

/** Ad-eligibility + UMP gate ordering + data-minimization coverage with a fake provider (task 5.2). */
class AdFeedControllerTest {
    @Test
    fun `consent is requested before any ad load and frequency is published`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            val controller = controller(AdsConfigOutcome.Enabled(6), provider)

            controller.prepare()
            assertEquals(6, controller.frequency.first())

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
            assertNull(controller.frequency.first())
            assertNull(controller.loadAd("ad:6"))
            assertFalse(provider.calls.contains("loadNativeAd"))
        }

    @Test
    fun `a disabled config initializes nothing and serves no ads`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            val controller = controller(AdsConfigOutcome.Disabled, provider)

            controller.prepare()
            assertNull(controller.frequency.first())
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

    // ---- premium-entitlement-lifecycle ----

    @Test
    fun `a confirmed purchase removes ad slots immediately`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            val confirmed = MutableStateFlow(false)
            val controller =
                AdFeedController(AdsConfigFlow { AdsConfigOutcome.Enabled(6) }, provider, FakeConsentStore(consent(false)), UNIT, confirmed)
            controller.prepare()
            assertEquals(6, controller.frequency.first())

            confirmed.value = true

            assertNull(controller.frequency.first(), "slots vanish without a cold start")
            assertNull(controller.loadAd("ad:6"))
            assertFalse(provider.calls.contains("loadNativeAd"), "no provider load for a confirmed buyer")
        }

    @Test
    fun `a confirmed purchase skips SDK init and UMP on prepare`() =
        runTest {
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            val controller =
                AdFeedController(
                    AdsConfigFlow { AdsConfigOutcome.Enabled(6) },
                    provider,
                    FakeConsentStore(consent(false)),
                    UNIT,
                    purchaseConfirmed = MutableStateFlow(true),
                )

            controller.prepare()

            assertTrue(provider.calls.isEmpty(), "no initialize, no consent: ${provider.calls}")
            assertNull(controller.frequency.first())
        }

    @Test
    fun `the same account stays latched`() =
        runTest {
            val config = CountingAdsConfig(AdsConfigOutcome.Enabled(6))
            val controller =
                AdFeedController(
                    config,
                    RecordingAdProvider(ConsentState.OBTAINED),
                    FakeConsentStore(consent(false)),
                    UNIT,
                    currentSessionKey = { "u-1" },
                )

            controller.prepare()
            controller.prepare()

            assertEquals(1, config.fetches)
        }

    @Test
    fun `a different account re-evaluates and its disabled config wins`() =
        runTest {
            val config = CountingAdsConfig(AdsConfigOutcome.Enabled(6))
            var account: String? = "u-1"
            val controller =
                AdFeedController(
                    config,
                    RecordingAdProvider(ConsentState.OBTAINED),
                    FakeConsentStore(consent(false)),
                    UNIT,
                    currentSessionKey = { account },
                )
            controller.prepare()
            assertEquals(6, controller.frequency.first())

            account = "u-2"
            config.outcome = AdsConfigOutcome.Disabled
            controller.prepare()

            assertEquals(2, config.fetches, "the second account re-fetches ads-config")
            assertNull(controller.frequency.first(), "u-2's disabled config wins; u-1's frequency is gone")
        }

    @Test
    fun `a buyer signing back in as the same account re-evaluates instead of reusing the stale frequency`() =
        runTest {
            // Free u-1 prepared (frequency 6) → buys → signs out → signs back in as u-1. The confirmed signal resets
            // on sign-out, so a user-id-keyed latch would re-show the pre-purchase frequency; the session key must
            // force a fresh ads-config read (now Disabled — the server knows u-1 is Premium).
            val self = MutableSelfUserId("u-1")
            val session = PremiumEntitlementSession(self, FakePurchaseController())
            session.syncIdentity()
            val config = CountingAdsConfig(AdsConfigOutcome.Enabled(6))
            val controller =
                AdFeedController(
                    config,
                    RecordingAdProvider(ConsentState.OBTAINED),
                    FakeConsentStore(consent(false)),
                    UNIT,
                    purchaseConfirmed = session.purchaseConfirmed,
                    currentSessionKey = session::sessionKey,
                )
            controller.prepare()
            assertEquals(6, controller.frequency.first())
            session.onPurchaseConfirmed()
            assertNull(controller.frequency.first())

            self.id = null
            session.syncIdentity() // sign-out
            self.id = "u-1"
            session.syncIdentity() // same account signs back in
            config.outcome = AdsConfigOutcome.Disabled
            controller.prepare()

            assertEquals(2, config.fetches, "the new session re-reads ads-config")
            assertNull(controller.frequency.first(), "the paying user never sees the stale pre-purchase frequency")
        }

    @Test
    fun `the synchronous frequency seed matches the flow`() =
        runTest {
            val confirmed = MutableStateFlow(false)
            val controller =
                AdFeedController(
                    AdsConfigFlow { AdsConfigOutcome.Enabled(6) },
                    RecordingAdProvider(ConsentState.OBTAINED),
                    FakeConsentStore(consent(false)),
                    UNIT,
                    confirmed,
                )
            controller.prepare()
            assertEquals(6, controller.currentFrequency)
            confirmed.value = true
            assertNull(controller.currentFrequency)
        }

    @Test
    fun `a different account never gets the previous account's cached ad`() =
        runTest {
            // Both accounts ad-eligible, so loadAd reaches the cache: u-2 must trigger a FRESH provider load.
            val provider = RecordingAdProvider(ConsentState.OBTAINED)
            var account: String? = "u-1"
            val controller =
                AdFeedController(
                    CountingAdsConfig(AdsConfigOutcome.Enabled(6)),
                    provider,
                    FakeConsentStore(consent(false)),
                    UNIT,
                    currentSessionKey = { account },
                )
            controller.prepare()
            controller.loadAd("ad:6")

            account = "u-2"
            controller.prepare()
            controller.loadAd("ad:6")

            assertEquals(2, provider.calls.count { it == "loadNativeAd" }, "u-1's cached ad was dropped, u-2 loads its own")
        }
}
