package id.nearyou.app.screens.consent

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.consent.ConsentFlow
import id.nearyou.app.consent.ConsentOutcome
import id.nearyou.app.consent.FakeConsentFlow
import id.nearyou.app.data.consent.ConsentSnapshot
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.data.consent.InMemoryConsentSnapshotStore
import id.nearyou.app.screens.routing.ConsentRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.consent_ads_desc
import id.nearyou.resources.generated.resources.consent_analytics_desc
import id.nearyou.resources.generated.resources.consent_crash_desc
import id.nearyou.resources.generated.resources.consent_cta_continue
import org.jetbrains.compose.resources.stringResource
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TITLE = "Privasi & data"
private const val ANALYTICS_LABEL = "Analitik penggunaan"
private const val CRASH_LABEL = "Laporan crash"
private const val ADS_LABEL = "Personalisasi iklan"
private const val CONTINUE_CTA = "Simpan & lanjutkan"
private const val SKIP = "Lewati untuk sekarang"

// Byte-identical to docs/03-UX-Design.md § "Analytics & Tracking Consent Screen (UU PDP)".
private const val ANALYTICS_DESC = "Bantu kami perbaiki aplikasi dengan data penggunaan anonim (Amplitude)"
private const val CRASH_DESC = "Laporkan crash otomatis untuk perbaikan bug (Sentry)"
private const val ADS_DESC = "Iklan dapat disesuaikan dengan minat kamu (Google AdMob UMP)"

/**
 * Render coverage of `ConsentScreen` via the Robolectric-backed CMP UI runner. The outcome→state
 * projection is covered by `ConsentUiStateTest`; the route-on-success seam by the pure
 * `ConsentOutcomeHandlerTest`; the submit/mapping by `ConsentRepositoryTest`. This suite verifies the
 * composable renders the canonical strings, the V2 default toggle states, does NOT submit on entry,
 * and shows the non-trapping skip ONLY after a failed submit (per the `mobile-analytics-consent` spec).
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `AgeGateScreenTest` for why this is retained for the
 * multi-test JVM startKoin/stopKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class ConsentScreenTest {
    private lateinit var fake: FakeConsentFlow
    private lateinit var snapshotStore: InMemoryConsentSnapshotStore

    private fun installKoin(outcome: ConsentOutcome = ConsentOutcome.Success) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeConsentFlow(outcome = outcome)
        snapshotStore = InMemoryConsentSnapshotStore()
        startKoin {
            modules(
                module {
                    single<ConsentFlow> { fake }
                    single<ConsentSnapshotStore> { snapshotStore }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun initialRender_showsTitleThreeLabelsAndCta_withV2DefaultToggleStates() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentScreen(onDone = {}) } } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(ANALYTICS_LABEL).assertExists()
            onNodeWithText(CRASH_LABEL).assertExists()
            onNodeWithText(ADS_LABEL).assertExists()
            onNodeWithText(CONTINUE_CTA).assertExists()
            // V2 default: analytics OFF, crash ON, ads OFF (the three Switches, top-to-bottom).
            val switches = onAllNodes(isToggleable())
            switches[0].assertIsOff()
            switches[1].assertIsOn()
            switches[2].assertIsOff()
        }
    }

    // 8.10 (route mapping) — ConsentRoute renders ConsentScreen through the REAL appEntryProvider
    // (hosted by TestNavHost). The signup-201 → ConsentRoute leg itself is wired in AppEntryProvider
    // (replaceAll(ConsentRoute), asserted structurally in ConsentSourceGuardTest); driving it through
    // the real age-gate DOB picker is impractical (the picker's day cells expose only a
    // locale-formatted contentDescription — the same limitation mobile-age-gate documented), so the
    // Success→navigate decision is covered by the pure ConsentOutcomeHandlerTest instead.
    @Test
    fun consentRoute_viaTestNavHost_rendersConsentScreen() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(ConsentRoute) } }
            waitForIdle()
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(CONTINUE_CTA).assertExists()
        }
    }

    @Test
    fun composingScreen_doesNotAutoSubmit() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentScreen(onDone = {}) } } }
            waitForIdle()
            assertEquals(0, fake.submitInvocationCount, "consent must not submit on screen entry (no GET, no PATCH)")
        }
    }

    @Test
    fun skip_isAbsentInitially_appearsAfterRetryableFailure_andRoutesHome() {
        installKoin(ConsentOutcome.RetryableError)
        var done = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentScreen(onDone = { done++ }) } } }
            // Before any submit, the happy path shows NO skip.
            onNodeWithText(SKIP).assertDoesNotExist()
            // Submit → RetryableError → the non-trapping skip appears.
            onNodeWithText(CONTINUE_CTA).performScrollTo().performClick()
            waitUntil { onAllNodesWithText(SKIP).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SKIP).assertExists()
            // Tapping skip proceeds to Home (onDone), keeping the server's safe defaults.
            onNodeWithText(SKIP).performScrollTo().performClick()
            waitForIdle()
            assertEquals(1, done, "skip routes to Home")
        }
    }

    @Test
    fun successfulSubmit_persistsTheConsentSnapshot() {
        installKoin(ConsentOutcome.Success)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentScreen(onDone = {}) } } }
            // Toggle Analytics ON (V2 default is OFF), then submit → 200.
            onAllNodes(isToggleable())[0].performScrollTo().performClick()
            onNodeWithText(CONTINUE_CTA).performScrollTo().performClick()
            waitUntil { snapshotStore.read() != null }
            assertEquals(
                ConsentSnapshot(analytics = true, crash = true, adsPersonalization = false),
                snapshotStore.read(),
                "a successful onboarding submit persists the submitted triple (resolves #198 onboarding write)",
            )
        }
    }

    @Test
    fun failedSubmit_leavesTheSnapshotUnwritten() {
        installKoin(ConsentOutcome.RetryableError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentScreen(onDone = {}) } } }
            onNodeWithText(CONTINUE_CTA).performScrollTo().performClick()
            waitForIdle()
            assertNull(snapshotStore.read(), "a failed (non-200) submit must not write the snapshot")
        }
    }

    @Test
    fun consentStrings_haveExactCanonicalText() {
        var analytics = ""
        var crash = ""
        var ads = ""
        var cta = ""
        runComposeUiTest {
            setContent {
                analytics = stringResource(Res.string.consent_analytics_desc)
                crash = stringResource(Res.string.consent_crash_desc)
                ads = stringResource(Res.string.consent_ads_desc)
                cta = stringResource(Res.string.consent_cta_continue)
            }
            waitForIdle()
        }
        assertEquals(ANALYTICS_DESC, analytics, "consent_analytics_desc must be byte-identical to docs/03")
        assertEquals(CRASH_DESC, crash, "consent_crash_desc must be byte-identical to docs/03")
        assertEquals(ADS_DESC, ads, "consent_ads_desc must be byte-identical to docs/03")
        assertEquals(CONTINUE_CTA, cta)
        assertTrue(analytics.contains("Amplitude") && crash.contains("Sentry") && ads.contains("AdMob"))
    }
}
