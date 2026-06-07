package id.nearyou.app.screens.post

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.post.FakePostDetailFlow
import id.nearyou.app.post.LikeApiClient
import id.nearyou.app.post.LikeCountOutcome
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.PostDetailRepository
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.ReplyApiClient
import id.nearyou.app.post.ReplyPostOutcome
import id.nearyou.app.post.fakeReply
import id.nearyou.app.screens.routing.PostDetailRoute
import id.nearyou.app.theme.NearYouTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertTrue

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val CONTENT = "halo"
private const val CITY = "Jakarta Selatan"
private const val CREATED_AT = "2026-06-06T10:00:00Z"
private const val POSTED_FROM = "Diposting dari Jakarta Selatan, 2026-06-06" // post_detail_posted_from
private const val POSTED_FROM_NO_CITY = "Diposting 2026-06-06" // post_detail_posted_from_no_city
private const val REPLIES_EMPTY = "Belum ada balasan. Jadilah yang pertama."
private const val LOADING = "Sedang memuat postingan…" // timeline_loading
private const val ERR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu." // signin_error_network
private const val RETRY = "Coba lagi" // cta_retry
private const val PLACEHOLDER = "Tulis balasan…" // post_detail_reply_placeholder
private const val CTA_REPLY = "Balas" // cta_reply
private const val CTA_CLOSE = "Tutup" // cta_close (the back/close affordance)

// reset countdown of 3600s → resetHours = 1 → "1 jam" fills the cap-upsell %1$s.
private const val LIKE_CAP_1H =
    "Kamu sudah menggunakan 10 like hari ini. Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam 1 jam."
private const val REPLY_CAP_1H =
    "Kamu sudah menggunakan 20 balasan hari ini. Upgrade ke Premium untuk balas tanpa batas, atau tunggu reset dalam 1 jam."
private const val POST_GONE = "Postingan ini sudah tidak tersedia." // post_detail_post_gone

private const val AUTHOR_UUID = "11111111-1111-1111-1111-111111111111"
private val JSON = headersOf("Content-Type", "application/json")

/**
 * Render + interaction coverage of `PostDetailScreen` via the Robolectric-backed CMP UI runner (task 9.3),
 * driven by `FakePostDetailFlow` (plus one real-repository-over-MockEngine test for the no-single-post-GET
 * guarantee). The outcome→state projection is covered purely by `PostDetailUiStateTest`; this suite
 * verifies the composable renders the header (empty-city tolerated, no `author_id`/coordinate), the
 * replies states (incl. a viewer's-own auto-hidden reply rendering normally), the like toggle (optimistic
 * + revert + 429 upsell + count + graceful degradation), and the reply composer (counter, 280-disable,
 * 201 local-append-without-refetch, 429 upsell, error banner), plus the deferral negatives (no block/
 * report affordance). In the Release-variant `*ScreenTest` exclude (the ui-test-manifest host is debug-only).
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for the multi-test startKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class PostDetailScreenTest {
    private fun installKoin(flow: PostDetailFlow) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module { single { flow } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private fun route(
        cityName: String = CITY,
        likedByViewer: Boolean = false,
        replyCount: Int = 2,
    ): PostDetailRoute =
        PostDetailRoute(
            postId = "p1",
            content = CONTENT,
            cityName = cityName,
            distanceM = 1234.5,
            createdAtIso = CREATED_AT,
            likedByViewer = likedByViewer,
            replyCount = replyCount,
        )

    // ---- header ----

    @Test
    fun header_showsContentAndPostedFrom_andNoBlockReportAffordance() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(CONTENT).assertExists()
            onNodeWithText(POSTED_FROM).assertExists()
            // Deferral negatives: no block/report affordance on the detail surface.
            onNodeWithText("Blokir", substring = true).assertDoesNotExist()
            onNodeWithText("Laporkan", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun emptyCityName_rendersNoCityFragment_noLiteralQuotes() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(cityName = ""), onBack = {}) } } }
            onNodeWithText(CONTENT).assertExists()
            onNodeWithText(POSTED_FROM_NO_CITY).assertExists() // date only, no dangling "dari ,"
            onNodeWithText("\"\"").assertDoesNotExist() // no literal empty-quotes leaked
        }
    }

    @Test
    fun noAuthorIdNorCoordinate_inRenderedTree() {
        installKoin(
            FakePostDetailFlow(
                repliesOutcome = RepliesOutcome.Loaded(listOf(fakeReply(authorId = AUTHOR_UUID, content = "PII_REPLY")), nextCursor = null),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText("PII_REPLY").assertExists()
            onNodeWithText(AUTHOR_UUID, substring = true).assertDoesNotExist()
            onNodeWithText("1234.5", substring = true).assertDoesNotExist() // no raw distance/coordinate
        }
    }

    // ---- replies states ----

    @Test
    fun repliesLoading_showsLoadingCopy() {
        installKoin(FakePostDetailFlow(suspendRepliesForever = true))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(LOADING).assertExists()
        }
    }

    @Test
    fun repliesEmpty_showsEmptyCopy() {
        installKoin(FakePostDetailFlow(repliesOutcome = RepliesOutcome.Loaded(emptyList(), nextCursor = null)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(REPLIES_EMPTY).assertExists()
        }
    }

    @Test
    fun autoHiddenReply_rendersIdenticallyToALiveReply() {
        // The viewer's own auto-hidden reply (is_auto_hidden = true) renders normally — no badge, no dimming.
        installKoin(
            FakePostDetailFlow(
                repliesOutcome =
                    RepliesOutcome.Loaded(listOf(fakeReply(content = "MY_HIDDEN_REPLY", isAutoHidden = true)), nextCursor = null),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText("MY_HIDDEN_REPLY").assertExists()
        }
    }

    @Test
    fun repliesError_showsNetworkCopyAndRetry_reInvokesLoad() {
        // First load errors; the retry re-load succeeds with a reply → the screen recovers to Content.
        val fake =
            FakePostDetailFlow(
                repliesOutcome = RepliesOutcome.NetworkError,
                secondRepliesOutcome = RepliesOutcome.Loaded(listOf(fakeReply(content = "RETRIED_REPLY")), nextCursor = null),
            )
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(ERR_NETWORK).assertExists()
            assertEquals(1, fake.loadRepliesCount)
            // The retry control is a scrollable LazyColumn item below the fold on the small test surface
            // — scroll it into view before clicking.
            onNodeWithTag(POST_DETAIL_REPLIES_RETRY_TAG).performScrollTo().performClick()
            // Wait on the recovered Content (a Compose-tree condition) — the retry re-ran the load.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("RETRIED_REPLY").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("RETRIED_REPLY").assertExists() // recovered to Content
            assertEquals(2, fake.loadRepliesCount, "retry re-invokes the replies load")
        }
    }

    // ---- like control ----

    @Test
    fun likeCountAvailable_showsCount() {
        installKoin(FakePostDetailFlow(likeCountOutcome = LikeCountOutcome.Available(42)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText("42 suka").assertExists()
        }
    }

    @Test
    fun likeCountUnavailable_hidesCount_andToggleStillWorks() {
        val fake = FakePostDetailFlow(likeCountOutcome = LikeCountOutcome.Unavailable, toggleOutcome = LikeOutcome.Liked)
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText("suka", substring = true).assertDoesNotExist() // count degraded → no count node
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            assertEquals(1, fake.toggleLikeCount, "the toggle stays functional when the count is unavailable")
        }
    }

    @Test
    fun optimisticLike_reflectsLikedState_andInvokesToggle() {
        val fake = FakePostDetailFlow(toggleOutcome = LikeOutcome.Liked)
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(likedByViewer = false), onBack = {}) } } }
            // The like-state dot is nested inside the clickable (merged) toggle → assert via the unmerged tree.
            onNodeWithTag(POST_DETAIL_LIKE_NOT_LIKED_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(POST_DETAIL_LIKE_LIKED_TAG, useUnmergedTree = true).assertExists() // reflects the liked state
            assertEquals(1, fake.toggleLikeCount)
            assertEquals(false, fake.lastToggleCurrentlyLiked, "toggled from the not-liked nav-arg state")
        }
    }

    @Test
    fun like429_revertsOptimisticFlip_andShowsCapUpsell() {
        installKoin(FakePostDetailFlow(toggleOutcome = LikeOutcome.RateLimited(retryAfterSeconds = 3600)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(likedByViewer = false), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(POST_DETAIL_LIKE_NOT_LIKED_TAG, useUnmergedTree = true).assertExists() // reverted
            onNodeWithText(LIKE_CAP_1H).assertExists()
        }
    }

    @Test
    fun likeFailure_restoresTheExactPriorCount() {
        // A known count (42 suka); the optimistic flip bumps to 43, then a failure must restore EXACTLY 42
        // (the count is restored directly from the captured prior value, not via an inverse delta).
        installKoin(FakePostDetailFlow(likeCountOutcome = LikeCountOutcome.Available(42), toggleOutcome = LikeOutcome.NetworkError))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(likedByViewer = false), onBack = {}) } } }
            onNodeWithText("42 suka").assertExists()
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithText("42 suka").assertExists() // restored exactly — not 41, not 43
            onNodeWithText("43 suka").assertDoesNotExist()
            onNodeWithText("41 suka").assertDoesNotExist()
        }
    }

    @Test
    fun like404_showsTerminalPostGoneBanner() {
        installKoin(FakePostDetailFlow(toggleOutcome = LikeOutcome.PostGone))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(likedByViewer = false), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(POST_DETAIL_LIKE_NOT_LIKED_TAG, useUnmergedTree = true).assertExists() // reverted
            onNodeWithText(POST_GONE).assertExists() // terminal copy, distinct from the network-retry banner
            onNodeWithText(ERR_NETWORK).assertDoesNotExist()
        }
    }

    // ---- reply composer ----

    @Test
    fun replyComposer_counterUpdates_and281Disables() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(PLACEHOLDER).assertExists()
            onNodeWithText(CTA_REPLY).assertIsNotEnabled() // empty → disabled
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).performTextInput("a".repeat(281))
            onNodeWithText("281/280").assertExists()
            onNodeWithText(CTA_REPLY).assertIsNotEnabled() // over-limit → disabled
        }
    }

    @Test
    fun reply201_appendsLocally_bumpsCount_withoutRefetch() {
        val fake =
            FakePostDetailFlow(
                repliesOutcome = RepliesOutcome.Loaded(emptyList(), nextCursor = null),
                replyOutcome = ReplyPostOutcome.Success(fakeReply(content = "NEW_REPLY")),
            )
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(replyCount = 2), onBack = {}) } } }
            onNodeWithText(REPLIES_EMPTY).assertExists()
            onNodeWithTag(POST_DETAIL_REPLY_COUNT_TAG).assertTextEquals("2")
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_REPLY).performClick()
            waitForIdle()
            onNodeWithText("NEW_REPLY").assertExists() // appended locally
            onNodeWithTag(POST_DETAIL_REPLY_COUNT_TAG).assertTextEquals("3") // count bumped
            assertEquals(1, fake.postReplyCount)
            assertEquals(1, fake.loadRepliesCount, "the 201 append must NOT trigger a replies re-fetch")
        }
    }

    @Test
    fun reply429_showsReplyCapUpsell() {
        installKoin(FakePostDetailFlow(replyOutcome = ReplyPostOutcome.RateLimited(retryAfterSeconds = 3600)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_REPLY).performClick()
            waitForIdle()
            onNodeWithText(REPLY_CAP_1H).assertExists()
        }
    }

    @Test
    fun replyInvalidContent_showsRetryableNetworkBanner() {
        installKoin(FakePostDetailFlow(replyOutcome = ReplyPostOutcome.InvalidContent))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_REPLY).performClick()
            waitForIdle()
            // The defensive InvalidContent maps to the generic retryable banner (no dedicated copy in v1).
            onNodeWithText(ERR_NETWORK).assertExists()
        }
    }

    // ---- back affordance ----

    @Test
    fun backAffordance_invokesOnBack() {
        installKoin(FakePostDetailFlow())
        var backCount = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = { backCount++ }) } } }
            onNodeWithText(CTA_CLOSE).assertExists()
            onNodeWithTag(POST_DETAIL_BACK_TAG).performClick()
            waitForIdle()
            assertEquals(1, backCount, "the back affordance invokes the hoisted onBack")
        }
    }

    // ---- no single-post GET (real repository over a capturing MockEngine) ----

    @Test
    fun noSinglePostGet_issuesOnlySubResourceRequests() {
        val captured = mutableListOf<String>()
        val mockClient =
            HttpClientFactory.create(
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine =
                    MockEngine { request ->
                        captured += request.url.encodedPath
                        when {
                            request.url.encodedPath.endsWith("/likes/count") -> respond("""{"count":0}""", HttpStatusCode.OK, JSON)
                            else -> respond("""{"replies":[],"next_cursor":null}""", HttpStatusCode.OK, JSON)
                        }
                    },
                installLogging = false,
                nowMillis = { 0L },
            )
        val repo = PostDetailRepository(LikeApiClient(mockClient), ReplyApiClient(mockClient))
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module { single<PostDetailFlow> { repo } }) }
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { captured.size >= 2 } // loadReplies + likeCount fire on entry
            // The header came from the route payload — NO bare single-post GET was issued.
            assertTrue(captured.none { it == "/api/v1/posts/p1" }, "no single-post by-id GET; captured=$captured")
            assertTrue(
                captured.all {
                    it == "/api/v1/posts/p1/replies" || it == "/api/v1/posts/p1/likes/count"
                },
                "only sub-resource paths; captured=$captured",
            )
        }
    }
}
