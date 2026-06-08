package id.nearyou.app.screens.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeGlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.fakeGlobalPost
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…" // timeline_session_redirect (terminal 401)
private const val RETRY = "Coba lagi"

/**
 * iOS counterpart to the Robolectric [GlobalTimelineScreenTest] — the Global feed run natively on the
 * iOS simulator (`:mobile:app:iosSimulatorArm64Test`). Global has NO location gate, so the screen is
 * composed directly. Focused on the states this change adds/affects: content, the connectivity error
 * (still retryable), and the new terminal-401 redirect placeholder
 * (mobile-session-expiry-and-proactive-refresh D4 — iOS parity with the Nearby flow test). Reuses the
 * commonTest [FakeGlobalTimelineFlow].
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class GlobalTimelineFlowIosTest {
    private lateinit var fake: FakeGlobalTimelineFlow

    private fun installKoin(outcome: GlobalTimelineOutcome) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeGlobalTimelineFlow(outcome = outcome)
        startKoin {
            modules(module { single<GlobalTimelineFlow> { fake } })
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun content_showsPost() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "HALO_GLOBAL")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("HALO_GLOBAL").assertExists()
            assertEquals(1, fake.loadInvocationCount, "load fires exactly once on entry")
        }
    }

    @Test
    fun networkError_showsConnectivityCopyAndRetry() {
        installKoin(GlobalTimelineOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            onNodeWithText(ERROR_NETWORK).assertExists()
            onNodeWithText(RETRY).assertExists()
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithText(RETRY).performClick()
            waitForIdle()
            assertEquals(2, fake.loadInvocationCount, "retry re-invokes the fetch")
        }
    }

    @Test
    fun sessionExpired_showsRedirectNotice_noRetry_notNetworkCopy() {
        installKoin(GlobalTimelineOutcome.SessionExpired)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            onNodeWithText(SESSION_REDIRECT).assertExists()
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
            onNodeWithText(RETRY).assertDoesNotExist()
        }
    }
}
