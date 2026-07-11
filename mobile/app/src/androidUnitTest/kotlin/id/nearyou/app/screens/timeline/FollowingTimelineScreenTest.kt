package id.nearyou.app.screens.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.block.FakeBlockSubmitter
import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeFollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeFollowingPost
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_CLOSE_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_TAG
import id.nearyou.app.ui.components.LOAD_MORE_FOOTER_TAG
import id.nearyou.app.ui.components.LOAD_MORE_RETRY_TAG
import id.nearyou.app.ui.components.POST_CARD_LIKE_ACTION_TAG
import id.nearyou.app.ui.components.POST_CARD_LIKE_FILLED_TAG
import id.nearyou.app.ui.components.POST_CARD_LIKE_OUTLINED_TAG
import id.nearyou.app.ui.components.POST_CARD_REPLY_ACTION_TAG
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

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val LOADING = "Sedang memuat postingan…" // timeline_loading (initial-load skeleton)
private const val FOLLOWING_PLACEHOLDER = "Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu." // timeline_following_placeholder
private const val SEE_GLOBAL = "Lihat Global" // cta_see_global
private const val LIMIT_HARD = "Kamu sudah mencapai batas baca untuk jam ini. Coba lagi sebentar lagi ya."
private const val LIMIT_SOFT = "Kamu lagi aktif-aktifnya! Premium membuka akses baca tanpa batas."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…" // timeline_session_redirect (terminal 401)
private const val RETRY = "Coba lagi"

/**
 * Render coverage of `FollowingTimelineScreen` via the Robolectric-backed CMP UI runner. The screen is
 * inset-free (no `Scaffold`/`TopAppBar`): "which feed is on screen" is asserted via the feed list test
 * tag ([FOLLOWING_TIMELINE_LIST_TAG]). The outcome→state projection is covered purely by
 * `FollowingTimelineUiStateTest`; this suite verifies the six states (Following has NO location gate),
 * the **directive empty** divergence from Global (placeholder copy + a "Lihat Global" CTA that fires
 * `onSeeGlobal` — NOT the loading skeleton), the initial-load-skeleton vs refresh-keeps-content split,
 * pull-to-refresh from content AND the empty state, renders no distance, tolerates an empty `city_name`,
 * and leaks no author id / coordinates / distance. The `FakeFollowingTimelineFlow` is synchronous.
 * Mirrors `GlobalTimelineScreenTest`.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h891dp")
@OptIn(ExperimentalTestApi::class)
class FollowingTimelineScreenTest {
    private lateinit var fake: FakeFollowingTimelineFlow
    private lateinit var likeFake: FakeLikeFlow

    private fun installKoin(
        outcome: FollowingTimelineOutcome = FollowingTimelineOutcome.Loaded(emptyList(), null, null),
        suspendForever: Boolean = false,
        suspendFromCall: Int = Int.MAX_VALUE,
        likeOutcome: LikeOutcome = LikeOutcome.Liked,
        loadMorePages: List<FollowingTimelineOutcome> = emptyList(),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake =
            FakeFollowingTimelineFlow(
                outcome = outcome,
                suspendForever = suspendForever,
                suspendFromCall = suspendFromCall,
                loadMorePages = loadMorePages,
            )
        likeFake = FakeLikeFlow(likeOutcome)
        startKoin {
            modules(
                module {
                    single<FollowingTimelineFlow> { fake }
                    single<LikeFlow> { likeFake }
                    // timeline-card-report-kebab: the report seam + the self id for the kebab gate.
                    single<ReportSubmitter> { FakeReportSubmitter() }
                    single<BlockSubmitter> { FakeBlockSubmitter() }
                    single<SelfUserIdProvider> { FakeSelfUserId("self") }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun initialRender_showsPostContent() {
        installKoin(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "HALO_FOLLOWING")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(FOLLOWING_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("HALO_FOLLOWING").assertExists()
            assertEquals(1, fake.loadInvocationCount, "load fires exactly once on entry")
        }
    }

    @Test
    fun initialLoadState_showsSkeletonCopy_noContent() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(LOADING).assertExists()
            onAllNodesWithTag(FOLLOWING_POST_CARD_TAG).fetchSemanticsNodes().let {
                assertEquals(0, it.size, "no post card during the initial-load skeleton")
            }
        }
    }

    // The DIVERGENCE from Global: Empty (Loaded, empty, no upsell) is a DIRECTIVE state — the placeholder
    // copy + a "Lihat Global" CTA — NOT the loading skeleton. It renders zero post cards and does NOT show
    // the loading copy.
    @Test
    fun emptyState_showsDirectiveCopyAndSeeGlobalCta_noCards_notLoadingCopy() {
        installKoin(FollowingTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(FOLLOWING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(FOLLOWING_PLACEHOLDER).assertExists()
            onNodeWithText(SEE_GLOBAL).assertExists()
            onNodeWithText(LOADING).assertDoesNotExist()
            onAllNodesWithTag(FOLLOWING_POST_CARD_TAG).fetchSemanticsNodes().let {
                assertEquals(0, it.size, "the directive Empty state renders no post card")
            }
        }
    }

    // mobile-following-timeline § "The empty-state CTA switches the Home pager to the Global tab" — the
    // "Lihat Global" control invokes the hoisted onSeeGlobal callback.
    @Test
    fun emptyState_seeGlobalCta_invokesOnSeeGlobal() {
        installKoin(FollowingTimelineOutcome.Loaded(emptyList(), null, null))
        var seeGlobalCount = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen(onSeeGlobal = { seeGlobalCount++ }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(SEE_GLOBAL).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SEE_GLOBAL).performClick()
            waitForIdle()
            assertEquals(1, seeGlobalCount, "the Lihat Global CTA fires the hoisted onSeeGlobal exactly once")
        }
    }

    @Test
    fun refreshOfLoadedContent_keepsTheListMounted_notTheSkeleton() {
        installKoin(
            FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "STAYS_FOLLOWING")), null, null),
            suspendFromCall = 2,
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("STAYS_FOLLOWING").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(FOLLOWING_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            onNodeWithText("STAYS_FOLLOWING").assertExists()
        }
    }

    // mobile-design-system § "Pull-to-refresh is available from a non-Content state" — Following's directive
    // Empty is rendered inside a scrollable, so a pull-down re-invokes the fetch.
    @Test
    fun pullToRefresh_worksFromTheEmptyState() {
        installKoin(FollowingTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(FOLLOWING_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithTag(FOLLOWING_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            assertEquals(2, fake.loadInvocationCount, "pull-to-refresh from the directive empty re-invokes the fetch")
        }
    }

    @Test
    fun hardLimit_showsLimitCopy() {
        installKoin(FollowingTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LIMIT_HARD).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(LIMIT_HARD).assertExists()
            // The hard cap is distinct from the directive empty — the placeholder copy must NOT show.
            onNodeWithText(FOLLOWING_PLACEHOLDER).assertDoesNotExist()
        }
    }

    @Test
    fun softLimit_showsPostsAndBanner() {
        installKoin(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "SOFT_FOLLOWING")), null, UpsellDto(soft = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("SOFT_FOLLOWING").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("SOFT_FOLLOWING").assertExists()
            onNodeWithText(LIMIT_SOFT).assertExists()
        }
    }

    @Test
    fun errorState_showsNetworkCopyAndRetry_andRetryReInvokes() {
        installKoin(FollowingTimelineOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(ERROR_NETWORK).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(ERROR_NETWORK).assertExists()
            onNodeWithText(RETRY).assertExists()
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithText(RETRY).performClick()
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            assertEquals(2, fake.loadInvocationCount, "retry re-invokes the fetch")
        }
    }

    @Test
    fun sessionExpired_rendersRedirectNotice_noRetry_notNetworkCopy() {
        installKoin(FollowingTimelineOutcome.SessionExpired)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(SESSION_REDIRECT).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SESSION_REDIRECT).assertExists()
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
            onNodeWithText(RETRY).assertDoesNotExist()
        }
    }

    @Test
    fun emptyCityName_rendersNoCityLabelNorLiteralQuotes() {
        installKoin(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "EMPTY_CITY_FOLLOWING", cityName = "")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("EMPTY_CITY_FOLLOWING").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("EMPTY_CITY_FOLLOWING").assertExists()
            onNodeWithText("\"\"").assertDoesNotExist()
        }
    }

    // author_user_id (a UUID), raw coordinates, AND any distance string are NEVER rendered — while the
    // author DISPLAY identity IS rendered by the shared card.
    @Test
    fun noAuthorIdCoordinatesNorDistance_inRenderedTree_whileDisplayIdentityIs() {
        installKoin(
            FollowingTimelineOutcome.Loaded(
                listOf(
                    fakeFollowingPost(
                        content = "PII_FOLLOWING",
                        authorUserId = "11111111-1111-1111-1111-111111111111",
                        authorUsername = "fajar.r",
                        authorDisplayName = "Fajar Ramadhan",
                        latitude = -6.21,
                        longitude = 106.85,
                    ),
                ),
                null,
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PII_FOLLOWING").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("PII_FOLLOWING").assertExists()
            onNodeWithText("Fajar Ramadhan").assertExists()
            onNodeWithText("@fajar.r").assertExists()
            onNodeWithText("11111111-1111-1111-1111-111111111111", substring = true).assertDoesNotExist()
            onNodeWithText("-6.21", substring = true).assertDoesNotExist()
            onNodeWithText("106.85", substring = true).assertDoesNotExist()
            // No distance is rendered (no "km" suffix); Following has no DistanceRenderer call.
            onNodeWithText("km", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun pullToRefresh_reInvokesFetch() {
        installKoin(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "REFRESH_FOLLOWING")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("REFRESH_FOLLOWING").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithTag(FOLLOWING_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            assertEquals(2, fake.loadInvocationCount, "pull-to-refresh re-invokes the fetch")
        }
    }

    // mobile-following-timeline § "Following post card opens post detail via a hoisted onOpenPost lambda" —
    // tapping a card invokes the hoisted onOpenPost with the card's PII-free fields (no distance).
    @Test
    fun postCard_tap_invokesOnOpenPostWithDisplayFields() {
        installKoin(
            FollowingTimelineOutcome.Loaded(
                listOf(fakeFollowingPost(id = "f9", content = "TAP_FOLLOWING", cityName = "Medan")),
                null,
                null,
            ),
        )
        var tapped: FollowingTimelinePost? = null
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen(onOpenPost = { tapped = it }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(FOLLOWING_POST_CARD_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(FOLLOWING_POST_CARD_TAG).performClick()
            waitForIdle()
            assertEquals("f9", tapped?.id)
            assertEquals("TAP_FOLLOWING", tapped?.content)
            assertEquals("Medan", tapped?.cityName)
            assertEquals("fajar.r", tapped?.authorUsername)
            assertEquals("Fajar Ramadhan", tapped?.authorDisplayName)
            // No coordinates / author UUID in the payload — FollowingTimelinePost drops the DTO's lat/long
            // (fakeFollowingPost defaults -6.21 / 106.85) and the UUID.
            assertEquals(false, tapped.toString().contains("-6.21"), "no latitude in the onOpenPost payload")
            assertEquals(false, tapped.toString().contains("106.85"), "no longitude in the onOpenPost payload")
            assertEquals(false, tapped.toString().contains("11111111-1111"), "no author UUID in the onOpenPost payload")
        }
    }

    // ---- mobile-inline-post-actions: the Following surface shares the SAME inline-like lifecycle ----

    @Test
    fun likeTap_flipsTheCardTreatment_throughTheSharedSeam() {
        installKoin(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(likedByViewer = false)), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_LIKE_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_LIKE_OUTLINED_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(POST_CARD_LIKE_ACTION_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(POST_CARD_LIKE_FILLED_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(listOf("p1" to false), likeFake.invocations, "the like direction is the card's current state")
        }
    }

    @Test
    fun rateLimitedLike_showsTheCapDialog_onTheFollowingSurface_tutupClears() {
        installKoin(
            FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(likedByViewer = false)), null, null),
            likeOutcome = LikeOutcome.RateLimited(retryAfterSeconds = 1_140),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_LIKE_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_LIKE_ACTION_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(
                "Kamu sudah menggunakan 10 like hari ini. " +
                    "Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam 19 mnt.",
            ).assertExists()
            onNodeWithTag(POST_CARD_LIKE_OUTLINED_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(DAILY_CAP_DIALOG_CLOSE_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun replyAffordance_invokesOnOpenPostReply_notOnOpenPost() {
        installKoin(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(id = "f7")), null, null))
        var opened = 0
        var replied: FollowingTimelinePost? = null
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        FollowingTimelineScreen(
                            onOpenPost = { opened++ },
                            onOpenPostReply = { replied = it },
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_REPLY_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_REPLY_ACTION_TAG).performClick()
            waitForIdle()
            assertEquals("f7", replied?.id, "the reply shortcut carries the tapped post")
            assertEquals(0, opened, "the reply shortcut does NOT fire the whole-card onOpenPost")
        }
    }

    // ---- mobile-following-timeline-infinite-scroll: cursor load-more (screen integration) ----

    // A short page-1 list keeps the footer within the load-more threshold, so the scroll-end detector
    // fires load-more without an explicit gesture; the appended page-2 content appears AND the follow-up
    // reused the page-1 cursor (Following is cursor-only — no anchor).
    @Test
    fun loadMore_appendsTheNextPage_reusingTheCursor() {
        installKoin(
            outcome = FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(id = "f1", content = "PAGE1_POST")), "c1", null),
            loadMorePages =
                listOf(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(id = "f2", content = "PAGE2_POST")), null, null)),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PAGE2_POST").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("PAGE1_POST").assertExists()
            onNodeWithText("PAGE2_POST").assertExists()
            assertEquals(listOf("c1"), fake.loadMoreCalls, "load-more fetched the retained cursor c1")
        }
    }

    // A failed load-more shows the non-destructive retry footer (page-1 retained); tapping retry recovers.
    @Test
    fun loadMoreError_showsRetryFooter_andRetryRecovers() {
        installKoin(
            outcome = FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(id = "f1", content = "PAGE1_POST")), "c1", null),
            loadMorePages =
                listOf(
                    FollowingTimelineOutcome.NetworkError,
                    FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(id = "f2", content = "PAGE2_POST")), null, null),
                ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(LOAD_MORE_RETRY_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("PAGE1_POST").assertExists() // the loaded list is retained on load-more failure
            onNodeWithTag(LOAD_MORE_RETRY_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PAGE2_POST").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag(LOAD_MORE_RETRY_TAG).assertCountEquals(0) // footer clears on success
        }
    }

    // The load-more footer never co-occurs with the initial-load skeleton (it lives inside the Content
    // post list, which the skeleton state does not render) — mobile-design-system load-more pattern.
    @Test
    fun loadMoreFooter_absentDuringInitialLoadSkeleton() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { FollowingTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag(LOAD_MORE_FOOTER_TAG).assertCountEquals(0)
            onAllNodesWithTag(LOAD_MORE_RETRY_TAG).assertCountEquals(0)
        }
    }
}
