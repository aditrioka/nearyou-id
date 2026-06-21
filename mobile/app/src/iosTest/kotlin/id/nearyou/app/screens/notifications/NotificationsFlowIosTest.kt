package id.nearyou.app.screens.notifications

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.notifications.fakeNotification
import id.nearyou.app.screens.home.PostDetailTarget
import id.nearyou.app.theme.NearYouTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val TITLE = "Notifikasi"
private const val LOADING = "Sedang memuat notifikasi…"
private const val EMPTY = "Belum ada notifikasi"
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val RETRY = "Coba lagi"
private const val COPY_POST_LIKED = "Seseorang menyukai postingan kamu"

/**
 * iOS counterpart to the Robolectric `NotificationsScreenTest` (+ `NotificationsScreenNavTest` for the
 * deep-link tap) — the notifications screen run natively on the iOS simulator (task 12.9). Covers the four
 * fetch states + the type-keyed copy/excerpt + mark-read on tap + a post-target deep-link tap → onOpenPost,
 * reusing the commonTest fakes. The pull-to-refresh swipe is left to the Android suite (gesture-timing
 * flakiness). See `id.nearyou.app.screens.auth.SignInFlowIosTest` for the v1-API + iosTest-placement
 * rationale; uses kotlin.test `@Test` with K/N-legal fn names (no `,()#`).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class NotificationsFlowIosTest {
    private lateinit var fake: FakeNotificationsFlow

    private fun installKoin(
        outcome: NotificationsOutcome = NotificationsOutcome.Loaded(emptyList(), null),
        suspendForever: Boolean = false,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeNotificationsFlow(outcome = outcome, suspendForever = suspendForever)
        startKoin { modules(module { single<NotificationsFlow> { fake } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun content_showsTitleAndTypeCopyAndExcerpt() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(fakeNotification(type = "post_liked", bodyData = buildJsonObject { put("post_excerpt", "halo dunia") })),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(COPY_POST_LIKED, substring = true).assertExists()
            onNodeWithText("halo dunia", substring = true).assertExists()
        }
    }

    @Test
    fun loading_showsLoadingCopy() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            onNodeWithText(LOADING).assertExists()
        }
    }

    @Test
    fun empty_showsEmptyCopy() {
        installKoin(NotificationsOutcome.Loaded(emptyList(), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            onNodeWithText(EMPTY).assertExists()
        }
    }

    @Test
    fun error_showsNetworkCopyAndRetry_andRetryReInvokes() {
        installKoin(NotificationsOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            onNodeWithText(ERROR_NETWORK).assertExists()
            onNodeWithText(RETRY).assertExists()
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithText(RETRY).performClick()
            waitForIdle()
            assertEquals(2, fake.loadInvocationCount, "retry re-invokes the fetch")
        }
    }

    @Test
    fun tappingPostTargetRow_invokesOnOpenPost_withNullDistance() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(fakeNotification(id = "n1", type = "post_liked", targetType = "post", targetId = "p1")),
                null,
            ),
        )
        runComposeUiTest {
            var captured: PostDetailTarget? = null
            setContent { KoinContext { NearYouTheme { NotificationsScreen(onOpenPost = { captured = it }) } } }
            onNodeWithText(COPY_POST_LIKED, substring = true).performClick()
            waitUntil(timeoutMillis = 5_000) { captured != null }
            assertNull(captured?.distanceM, "the by-id projection omits coordinates → distanceM is null")
        }
    }

    @Test
    fun tappingUnreadRow_marksItRead() {
        installKoin(NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = null)), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            onNodeWithText(COPY_POST_LIKED, substring = true).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(NOTIFICATION_UNREAD_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(listOf("n1"), fake.markReadIds, "tapping the row marks it read")
        }
    }
}
