package id.nearyou.app.screens.settings

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
import id.nearyou.app.diagnostics.CrashReportingController
import id.nearyou.app.diagnostics.FakeCrashReporter
import id.nearyou.app.infra.sentry.CrashReporterConfig
import id.nearyou.app.theme.NearYouTheme
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

private const val SAVE = "Simpan"
private const val RETRYABLE = "Gagal menyimpan preferensi. Coba lagi."

/**
 * Render coverage of `ConsentSettingsScreen`. The submit/snapshot logic is covered by
 * `ConsentSettingsViewModelTest`; this suite verifies the toggles render seeded from the store, save
 * forwards the triple, a retryable error shows the banner, and a 401 routes to sign-in.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class ConsentSettingsScreenTest {
    private lateinit var fake: FakeConsentFlow

    private fun installKoin(
        outcome: ConsentOutcome = ConsentOutcome.Success,
        store: ConsentSnapshotStore = InMemoryConsentSnapshotStore(),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeConsentFlow(outcome = outcome)
        startKoin {
            modules(
                module {
                    single<ConsentFlow> { fake }
                    single { store }
                    single {
                        CrashReportingController(
                            FakeCrashReporter(),
                            CrashReporterConfig(dsn = "", environment = "test", release = "test"),
                        )
                    }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun togglesSeedFromSnapshot() {
        val store = InMemoryConsentSnapshotStore()
        store.write(ConsentSnapshot(analytics = true, crash = true, adsPersonalization = false))
        installKoin(store = store)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentSettingsScreen(onBack = {}, onTokenInvalid = {}) } } }
            val switches = onAllNodes(isToggleable())
            switches[0].assertIsOn() // analytics (from snapshot)
            switches[1].assertIsOn() // crash
            switches[2].assertIsOff() // ads
        }
    }

    @Test
    fun save_forwardsTheToggleTriple() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentSettingsScreen(onBack = {}, onTokenInvalid = {}) } } }
            onNodeWithText(SAVE).performScrollTo().performClick()
            waitForIdle()
            assertEquals(1, fake.submitInvocationCount)
        }
    }

    @Test
    fun retryableError_showsTheBanner() {
        installKoin(outcome = ConsentOutcome.RetryableError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentSettingsScreen(onBack = {}, onTokenInvalid = {}) } } }
            onNodeWithText(SAVE).performScrollTo().performClick()
            waitUntil { onAllNodesWithText(RETRYABLE).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(RETRYABLE).assertExists()
        }
    }

    @Test
    fun terminal401_routesToSignIn() {
        installKoin(outcome = ConsentOutcome.TokenInvalid)
        var redirected = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConsentSettingsScreen(onBack = {}, onTokenInvalid = { redirected++ }) } } }
            onNodeWithText(SAVE).performScrollTo().performClick()
            waitUntil { redirected == 1 }
            assertEquals(1, redirected)
        }
    }
}
