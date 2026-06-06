package id.nearyou.app.screens.consent

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.consent.ConsentFlow
import id.nearyou.app.consent.ConsentOutcome
import id.nearyou.app.consent.FakeConsentFlow
import id.nearyou.app.screens.routing.ConsentRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.theme.NearYouTheme
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TITLE = "Privasi & data"
private const val ANALYTICS_LABEL = "Analitik penggunaan"
private const val CONTINUE_CTA = "Simpan & lanjutkan"

/**
 * iOS counterpart to the Robolectric `ConsentScreenTest` — the consent render + entry invariants run
 * NATIVELY on the iOS simulator (the Kotlin/Native compose-link + render surface that CI/Linux cannot
 * catch; the flow/mapping logic is already exercised on iOS by `ConsentRepositoryTest` in commonTest).
 * Mirrors `AgeGateFlowIosTest`. Verifies `ConsentScreen` composes + renders the canonical strings,
 * does not auto-submit on entry, and renders through the REAL `appEntryProvider` at `ConsentRoute`.
 *
 * See `SignInFlowIosTest` for the v1-API + iosTest-placement rationale.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ConsentFlowIosTest {
    private lateinit var fake: FakeConsentFlow

    private fun installKoin(outcome: ConsentOutcome = ConsentOutcome.Success) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeConsentFlow(outcome = outcome)
        startKoin { modules(module { single<ConsentFlow> { fake } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun initialRender_showsTitleAnalyticsLabelAndCta() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentScreen(onDone = {}) } } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(ANALYTICS_LABEL).assertExists()
            onNodeWithText(CONTINUE_CTA).assertExists()
        }
    }

    @Test
    fun composingScreen_doesNotAutoSubmit() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentScreen(onDone = {}) } } }
            waitForIdle()
            assertEquals(0, fake.submitInvocationCount, "consent must not submit on screen entry")
        }
    }

    @Test
    fun consentRoute_viaTestNavHost_rendersConsentScreen() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(ConsentRoute) } }
            waitForIdle()
            onNodeWithText(TITLE).assertExists()
        }
    }
}
