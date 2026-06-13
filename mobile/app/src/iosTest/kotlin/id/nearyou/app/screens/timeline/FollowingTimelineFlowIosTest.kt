package id.nearyou.app.screens.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeFollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineOutcome
import id.nearyou.app.timeline.fakeFollowingPost
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val FOLLOWING_PLACEHOLDER = "Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu."
private const val SEE_GLOBAL = "Lihat Global"
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…" // timeline_session_redirect (terminal 401)
private const val RETRY = "Coba lagi"

/**
 * iOS counterpart to the Robolectric [FollowingTimelineScreenTest] — the Following feed run natively on
 * the iOS simulator (`:mobile:app:iosSimulatorArm64Test`). Following has NO location gate, so the screen
 * is composed directly. Focused on the states this change adds/affects: content, the directive empty
 * (placeholder + "Lihat Global" CTA — the divergence from Global), the connectivity error (still
 * retryable), and the terminal-401 redirect placeholder. Reuses the commonTest [FakeFollowingTimelineFlow].
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class FollowingTimelineFlowIosTest {
    private lateinit var fake: FakeFollowingTimelineFlow

    private fun installKoin(outcome: FollowingTimelineOutcome) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeFollowingTimelineFlow(outcome = outcome)
        startKoin {
            modules(module { single<FollowingTimelineFlow> { fake } })
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun content_showsPost() {
        installKoin(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "HALO_FOLLOWING")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(FOLLOWING_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("HALO_FOLLOWING").assertExists()
            assertEquals(1, fake.loadInvocationCount, "load fires exactly once on entry")
        }
    }

    @Test
    fun emptyState_showsDirectiveCopyAndSeeGlobalCta() {
        installKoin(FollowingTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            onNodeWithText(FOLLOWING_PLACEHOLDER).assertExists()
            onNodeWithText(SEE_GLOBAL).assertExists()
        }
    }

    @Test
    fun networkError_showsConnectivityCopyAndRetry() {
        installKoin(FollowingTimelineOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
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
        installKoin(FollowingTimelineOutcome.SessionExpired)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            onNodeWithText(SESSION_REDIRECT).assertExists()
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
            onNodeWithText(RETRY).assertDoesNotExist()
        }
    }
}
