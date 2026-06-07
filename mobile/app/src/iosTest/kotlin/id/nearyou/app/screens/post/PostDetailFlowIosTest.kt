package id.nearyou.app.screens.post

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.post.FakePostDetailFlow
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.ReplyPostOutcome
import id.nearyou.app.post.fakeReply
import id.nearyou.app.screens.routing.PostDetailRoute
import id.nearyou.app.theme.NearYouTheme
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val CONTENT = "halo"
private const val POSTED_FROM = "Diposting dari Jakarta Selatan, 2026-06-06"
private const val REPLIES_EMPTY = "Belum ada balasan. Jadilah yang pertama."
private const val CTA_REPLY = "Balas"
private const val CTA_CLOSE = "Tutup"
private const val LIKE_CAP_1H =
    "Kamu sudah menggunakan 10 like hari ini. Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam 1 jam."

/**
 * iOS counterpart to the Robolectric [PostDetailScreenTest][id.nearyou.app.screens.post.PostDetailScreenTest]
 * — the post-detail flow run natively on the iOS simulator. Covers the header, the replies empty/content
 * states, the optimistic like toggle + the 429 cap upsell, the 201 local-append, and the back affordance,
 * reusing the commonTest `FakePostDetailFlow`. See `SignInFlowIosTest` for the v1-API + iosTest-placement
 * rationale (Kotlin/Native test-name restrictions: no `(`,`)`,`,`,`#`).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class PostDetailFlowIosTest {
    private fun installKoin(flow: PostDetailFlow) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module { single { flow } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private fun route(
        cityName: String = "Jakarta Selatan",
        likedByViewer: Boolean = false,
        replyCount: Int = 2,
    ): PostDetailRoute =
        PostDetailRoute(
            postId = "p1",
            content = CONTENT,
            cityName = cityName,
            distanceM = 1234.5,
            createdAtIso = "2026-06-06T10:00:00Z",
            likedByViewer = likedByViewer,
            replyCount = replyCount,
        )

    @Test
    fun header_showsContentAndPostedFrom() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(CONTENT).assertExists()
            onNodeWithText(POSTED_FROM).assertExists()
        }
    }

    @Test
    fun emptyReplies_showsEmptyCopy() {
        installKoin(FakePostDetailFlow(repliesOutcome = RepliesOutcome.Loaded(emptyList(), nextCursor = null)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(REPLIES_EMPTY).assertExists()
        }
    }

    @Test
    fun repliesContent_showsReplyContent() {
        installKoin(
            FakePostDetailFlow(repliesOutcome = RepliesOutcome.Loaded(listOf(fakeReply(content = "IOS_REPLY")), nextCursor = null)),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText("IOS_REPLY").assertExists()
        }
    }

    @Test
    fun optimisticLike_reflectsLikedState() {
        val fake = FakePostDetailFlow(toggleOutcome = LikeOutcome.Liked)
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(likedByViewer = false), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_LIKE_NOT_LIKED_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(POST_DETAIL_LIKE_LIKED_TAG, useUnmergedTree = true).assertExists()
            assertEquals(1, fake.toggleLikeCount)
        }
    }

    @Test
    fun like429_showsCapUpsell() {
        installKoin(FakePostDetailFlow(toggleOutcome = LikeOutcome.RateLimited(retryAfterSeconds = 3600)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(likedByViewer = false), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(POST_DETAIL_LIKE_NOT_LIKED_TAG, useUnmergedTree = true).assertExists()
            onNodeWithText(LIKE_CAP_1H).assertExists()
        }
    }

    @Test
    fun reply201_appendsLocallyAndBumpsCount() {
        val fake =
            FakePostDetailFlow(
                repliesOutcome = RepliesOutcome.Loaded(emptyList(), nextCursor = null),
                replyOutcome = ReplyPostOutcome.Success(fakeReply(content = "IOS_NEW_REPLY")),
            )
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(replyCount = 2), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_REPLY).performClick()
            waitForIdle()
            onNodeWithText("IOS_NEW_REPLY").assertExists()
            onNodeWithTag(POST_DETAIL_REPLY_COUNT_TAG).assertTextEquals("3")
            assertEquals(1, fake.loadRepliesCount, "the 201 append must NOT trigger a replies re-fetch")
        }
    }

    @Test
    fun back_invokesOnBack() {
        installKoin(FakePostDetailFlow())
        var backCount = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = { backCount++ }) } } }
            onNodeWithText(CTA_CLOSE).assertExists()
            onNodeWithTag(POST_DETAIL_BACK_TAG).performClick()
            waitForIdle()
            assertEquals(1, backCount)
        }
    }
}
