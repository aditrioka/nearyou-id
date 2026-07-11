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
import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.block.FakeBlockSubmitter
import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeGlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeGlobalPost
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_CLOSE_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_TAG
import id.nearyou.app.ui.components.LOAD_MORE_FOOTER_TAG
import id.nearyou.app.ui.components.LOAD_MORE_RETRY_TAG
import id.nearyou.app.ui.components.POST_CARD_BLOCK_ITEM_TAG
import id.nearyou.app.ui.components.POST_CARD_KEBAB_TAG
import id.nearyou.app.ui.components.POST_CARD_LIKE_ACTION_TAG
import id.nearyou.app.ui.components.POST_CARD_LIKE_FILLED_TAG
import id.nearyou.app.ui.components.POST_CARD_LIKE_OUTLINED_TAG
import id.nearyou.app.ui.components.POST_CARD_REPLY_ACTION_TAG
import id.nearyou.app.ui.components.POST_CARD_REPORT_ITEM_TAG
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
import kotlin.test.assertFalse

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val REMOVED_HEADER = "Seluruh Indonesia" // timeline_global_title — NO LONGER rendered (header removed)
private const val LOADING = "Sedang memuat postingan…" // timeline_loading (also the Empty skeleton copy)
private const val LIMIT_HARD = "Kamu sudah mencapai batas baca untuk jam ini. Coba lagi sebentar lagi ya."
private const val LIMIT_SOFT = "Kamu lagi aktif-aktifnya! Premium membuka akses baca tanpa batas."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…" // timeline_session_redirect (terminal 401)
private const val RETRY = "Coba lagi"

/**
 * Render coverage of `GlobalTimelineScreen` via the Robolectric-backed CMP UI runner
 * (mobile-home-shell-redesign tasks 10.2). The screen is now **inset-free** (no `Scaffold`/`TopAppBar`,
 * no redundant header): "which feed is on screen" is asserted via the feed list **test tag**
 * ([GLOBAL_TIMELINE_LIST_TAG]), NOT the removed header. The outcome→state projection is covered purely
 * by `GlobalTimelineUiStateTest`; this suite verifies the six states (Global has NO location gate),
 * the initial-load-skeleton vs refresh-keeps-content split (design D3), pull-to-refresh from content AND
 * the empty state, renders no distance, tolerates an empty `city_name`, and leaks no author id /
 * coordinates / distance. The `FakeGlobalTimelineFlow` is synchronous.
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for the multi-test startKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h891dp")
@OptIn(ExperimentalTestApi::class)
class GlobalTimelineScreenTest {
    private lateinit var fake: FakeGlobalTimelineFlow
    private lateinit var likeFake: FakeLikeFlow
    private lateinit var reportFake: FakeReportSubmitter
    private lateinit var blockFake: FakeBlockSubmitter

    private fun installKoin(
        outcome: GlobalTimelineOutcome = GlobalTimelineOutcome.Loaded(emptyList(), null, null),
        suspendForever: Boolean = false,
        suspendFromCall: Int = Int.MAX_VALUE,
        likeOutcome: LikeOutcome = LikeOutcome.Liked,
        loadMorePages: List<GlobalTimelineOutcome> = emptyList(),
        reportOutcome: ReportOutcome = ReportOutcome.Submitted,
        blockOutcome: BlockOutcome = BlockOutcome.Blocked,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake =
            FakeGlobalTimelineFlow(
                outcome = outcome,
                suspendForever = suspendForever,
                suspendFromCall = suspendFromCall,
                loadMorePages = loadMorePages,
            )
        likeFake = FakeLikeFlow(likeOutcome)
        reportFake = FakeReportSubmitter(reportOutcome)
        blockFake = FakeBlockSubmitter(blockOutcome)
        startKoin {
            modules(
                module {
                    single<GlobalTimelineFlow> { fake }
                    single<LikeFlow> { likeFake }
                    // timeline-card-report-kebab: the report seam + the self id the kebab's
                    // authorship gate compares against (fakeGlobalPost defaults to another author).
                    single<ReportSubmitter> { reportFake }
                    // timeline-card-block-kebab: the block seam (recording fake — the tests assert
                    // the submitted author UUID and the Batal-submits-nothing rule).
                    single<BlockSubmitter> { blockFake }
                    single<SelfUserIdProvider> { FakeSelfUserId("self") }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // 10.2 — initial render shows the loaded post content via the list tag and renders NO redundant
    // header; load fires once on entry.
    @Test
    fun initialRender_showsPostContent_andNoHeader() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "HALO_GLOBAL")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("HALO_GLOBAL").assertExists()
            onNodeWithText(REMOVED_HEADER).assertDoesNotExist() // the redundant header is gone
            assertEquals(1, fake.loadInvocationCount, "load fires exactly once on entry")
        }
    }

    // 10.2 — initial-load skeleton: a fetch that never returns keeps the screen on the initial load →
    // the loading copy shows and no post card is shown.
    @Test
    fun initialLoadState_showsSkeletonCopy_noContent() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(LOADING).assertExists()
            onAllNodesWithTag(GLOBAL_POST_CARD_TAG).fetchSemanticsNodes().let {
                assertEquals(0, it.size, "no post card during the initial-load skeleton")
            }
        }
    }

    // 10.2 — Empty (Loaded, empty, no upsell) reuses the loading-skeleton copy (Global is never empty);
    // it renders the timeline_loading copy and zero post cards.
    @Test
    fun emptyState_reusesLoadingSkeletonCopy_noCards() {
        installKoin(GlobalTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(LOADING).assertExists()
            onAllNodesWithTag(GLOBAL_POST_CARD_TAG).fetchSemanticsNodes().let {
                assertEquals(0, it.size, "the Empty state renders no post card")
            }
        }
    }

    // 10.2 / design D3 — a refresh of loaded content KEEPS the content list mounted (it does NOT revert
    // to the loading skeleton). First load returns content; the 2nd call (refresh) suspends mid-flight.
    @Test
    fun refreshOfLoadedContent_keepsTheListMounted_notTheSkeleton() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "STAYS_GLOBAL")), null, null), suspendFromCall = 2)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("STAYS_GLOBAL").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(GLOBAL_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            onNodeWithText("STAYS_GLOBAL").assertExists()
        }
    }

    // 10.2 / mobile-design-system § "Pull-to-refresh is available from a non-Content state" — Global's
    // Empty state is rendered inside a scrollable (the skeleton), so a pull-down re-invokes the fetch.
    @Test
    fun pullToRefresh_worksFromTheEmptyState() {
        installKoin(GlobalTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithTag(GLOBAL_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            assertEquals(2, fake.loadInvocationCount, "pull-to-refresh from the empty skeleton re-invokes the fetch")
        }
    }

    // hard cap (Loaded, empty, upsell.hard) shows the limit copy.
    @Test
    fun hardLimit_showsLimitCopy() {
        installKoin(GlobalTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LIMIT_HARD).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(LIMIT_HARD).assertExists()
        }
    }

    // soft cap (Loaded, non-empty, upsell.soft) shows the posts AND the non-blocking banner.
    @Test
    fun softLimit_showsPostsAndBanner() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "SOFT_GLOBAL")), null, UpsellDto(soft = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("SOFT_GLOBAL").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("SOFT_GLOBAL").assertExists()
            onNodeWithText(LIMIT_SOFT).assertExists()
        }
    }

    // error state shows the network copy + a clickable retry that re-invokes the fetch.
    @Test
    fun errorState_showsNetworkCopyAndRetry_andRetryReInvokes() {
        installKoin(GlobalTimelineOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(ERROR_NETWORK).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(ERROR_NETWORK).assertExists()
            onNodeWithText(RETRY).assertExists()
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithText(RETRY).performClick()
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            assertEquals(2, fake.loadInvocationCount, "retry re-invokes the fetch")
        }
    }

    // terminal 401 (SessionExpired) renders the neutral redirect notice — NO retry, NOT the
    // connectivity copy (mobile-session-expiry-and-proactive-refresh D4).
    @Test
    fun sessionExpired_rendersRedirectNotice_noRetry_notNetworkCopy() {
        installKoin(GlobalTimelineOutcome.SessionExpired)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(SESSION_REDIRECT).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SESSION_REDIRECT).assertExists()
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
            onNodeWithText(RETRY).assertDoesNotExist()
        }
    }

    // empty city_name renders no city label and no literal `""`; the card still renders fine.
    @Test
    fun emptyCityName_rendersNoCityLabelNorLiteralQuotes() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "EMPTY_CITY_GLOBAL", cityName = "")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("EMPTY_CITY_GLOBAL").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("EMPTY_CITY_GLOBAL").assertExists()
            onNodeWithText("\"\"").assertDoesNotExist()
        }
    }

    // author_user_id (a UUID), raw coordinates, AND any distance string are NEVER rendered — while
    // the author DISPLAY identity (mobile-timeline-card-redesign) IS rendered by the shared card.
    @Test
    fun noAuthorIdCoordinatesNorDistance_inRenderedTree_whileDisplayIdentityIs() {
        installKoin(
            GlobalTimelineOutcome.Loaded(
                listOf(
                    fakeGlobalPost(
                        content = "PII_GLOBAL",
                        authorUserId = "11111111-1111-1111-1111-111111111111",
                        authorUsername = "dewi.kuliner",
                        authorDisplayName = "Dewi Lestari",
                        latitude = -6.21,
                        longitude = 106.85,
                    ),
                ),
                null,
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PII_GLOBAL").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("PII_GLOBAL").assertExists()
            onNodeWithText("Dewi Lestari").assertExists()
            onNodeWithText("@dewi.kuliner").assertExists()
            onNodeWithText("11111111-1111-1111-1111-111111111111", substring = true).assertDoesNotExist()
            onNodeWithText("-6.21", substring = true).assertDoesNotExist()
            onNodeWithText("106.85", substring = true).assertDoesNotExist()
            // No distance is rendered (no "km" suffix); Global has no DistanceRenderer call.
            onNodeWithText("km", substring = true).assertDoesNotExist()
        }
    }

    // Spec § "Pull-to-refresh re-invokes the fetch" — a pull-down on the content list re-fires the load.
    @Test
    fun pullToRefresh_reInvokesFetch() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "REFRESH_GLOBAL")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("REFRESH_GLOBAL").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithTag(GLOBAL_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            assertEquals(2, fake.loadInvocationCount, "pull-to-refresh re-invokes the fetch")
        }
    }

    // mobile-global-timeline § "Global post card opens post detail via a hoisted onOpenPost lambda" —
    // tapping a card invokes the hoisted onOpenPost with the card's PII-free fields (no distance).
    @Test
    fun postCard_tap_invokesOnOpenPostWithDisplayFields() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "g9", content = "TAP_GLOBAL", cityName = "Medan")), null, null))
        var tapped: GlobalTimelinePost? = null
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen(onOpenPost = { tapped = it }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_POST_CARD_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(GLOBAL_POST_CARD_TAG).performClick()
            waitForIdle()
            assertEquals("g9", tapped?.id)
            assertEquals("TAP_GLOBAL", tapped?.content)
            assertEquals("Medan", tapped?.cityName)
            assertEquals("dewi.kuliner", tapped?.authorUsername)
            assertEquals("Dewi Lestari", tapped?.authorDisplayName)
            // No coordinates / author UUID in the payload — GlobalTimelinePost drops the DTO's lat/long
            // (fakeGlobalPost defaults -6.21 / 106.85) and the UUID; asserted per the spec scenario.
            assertFalse(tapped.toString().contains("-6.21"), "no latitude in the onOpenPost payload")
            assertFalse(tapped.toString().contains("106.85"), "no longitude in the onOpenPost payload")
            assertFalse(tapped.toString().contains("11111111-1111"), "no author UUID in the onOpenPost payload")
        }
    }

    // ---- mobile-inline-post-actions: the Global surface shares the SAME inline-like lifecycle ----

    @Test
    fun likeTap_flipsTheCardTreatment_throughTheSharedSeam() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(likedByViewer = false)), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
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
    fun rateLimitedLike_showsTheCapDialog_onTheGlobalSurface_tutupClears() {
        installKoin(
            GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(likedByViewer = false)), null, null),
            likeOutcome = LikeOutcome.RateLimited(retryAfterSeconds = 1_140),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_LIKE_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_LIKE_ACTION_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            // The verbatim body with the sub-hour "19 mnt" countdown; the flip is reverted behind it.
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
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "g7")), null, null))
        var opened = 0
        var replied: GlobalTimelinePost? = null
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        GlobalTimelineScreen(
                            onOpenPost = { opened++ },
                            onOpenPostReply = { replied = it },
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_REPLY_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_REPLY_ACTION_TAG).performClick()
            waitForIdle()
            assertEquals("g7", replied?.id, "the reply shortcut carries the tapped post")
            assertEquals(0, opened, "the reply shortcut does NOT fire the whole-card onOpenPost")
        }
    }

    // ---- mobile-global-timeline-infinite-scroll: cursor load-more (screen integration) ----

    // A short page-1 list keeps the footer within the load-more threshold, so the scroll-end detector
    // fires load-more without an explicit gesture; the appended page-2 content appears AND the follow-up
    // reused the page-1 cursor (Global is cursor-only — no anchor).
    @Test
    fun loadMore_appendsTheNextPage_reusingTheCursor() {
        installKoin(
            outcome = GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "g1", content = "PAGE1_POST")), "c1", null),
            loadMorePages =
                listOf(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "g2", content = "PAGE2_POST")), null, null)),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
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
            outcome = GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "g1", content = "PAGE1_POST")), "c1", null),
            loadMorePages =
                listOf(
                    GlobalTimelineOutcome.NetworkError,
                    GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "g2", content = "PAGE2_POST")), null, null),
                ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(LOAD_MORE_RETRY_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("PAGE1_POST").assertExists() // the loaded list is retained on load-more failure
            onNodeWithTag(LOAD_MORE_RETRY_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PAGE2_POST").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag(LOAD_MORE_RETRY_TAG).assertCountEquals(0) // footer clears on success
        }
    }

    // ---- timeline-card-report-kebab: the feed-level report wiring (the Global surface as the ----
    // ---- representative feed — all three share the SAME VM/controller/overlay shape).        ----

    // Spec § "The viewer's own post exposes no report entry point": the kebab renders on another
    // user's card and NOT on the viewer's own (authorUserId == the injected self id).
    @Test
    fun reportKebab_presentOnAnotherUsersPost_absentOnOwnPost() {
        installKoin(
            GlobalTimelineOutcome.Loaded(
                listOf(
                    fakeGlobalPost(id = "g1", content = "OTHERS_POST"),
                    fakeGlobalPost(id = "g2", content = "OWN_POST", authorUserId = "self"),
                ),
                null,
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("OWN_POST").fetchSemanticsNodes().isNotEmpty() }
            // The self id resolves asynchronously; the eligible card's kebab appears on recomposition.
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(POST_CARD_KEBAB_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithTag(POST_CARD_KEBAB_TAG).assertCountEquals(1)
        }
    }

    // Spec § "Another user's post is reportable from the feed": kebab → the "Laporkan" menu entry.
    // NOTE: deliberately stops at the menu entry WITHOUT opening the dialog — the shared ReportDialog
    // (an OutlinedTextField inside an AlertDialog) over this screen's LazyColumn feed triggers the
    // documented Robolectric-only never-settling measure pass (the PostDetailScreenTest NOTE; here it
    // manifested as a 60s waitUntil hang, OOMing the 512m suite JVM). The dialog-open one-shot, the
    // target_type=post submission carrying the tapped post's id, the Submitted/Duplicate→same-success
    // mapping, and the one-shot clear are covered (more strongly, via a capturing fake) in
    // TimelineReportControllerTest; the dialog body itself renders fine in ReportDialogTest
    // (non-LazyColumn host).
    @Test
    fun reportEntryPoint_kebabOffersLaporkan_forAnotherUsersPost() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "g9")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_KEBAB_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_KEBAB_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_REPORT_ITEM_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_REPORT_ITEM_TAG).assertExists()
        }
    }

    // ---- timeline-card-block-kebab: the feed-level block wiring (the Global surface as the ----
    // ---- representative feed — all three share the SAME VM/controller/overlay shape). Unlike ----
    // ---- the report flow, the shared BlockConfirmDialog has NO text field, so opening it over ----
    // ---- the LazyColumn does not trip the documented Robolectric never-settling measure pass  ----
    // ---- (the post-detail block tests set that precedent) — the full confirm path runs here.  ----

    // Spec § "Another user's post is blockable from the feed" + § "A confirmed block removes the
    // author's loaded posts and shows the success toast": kebab → "Blokir @{username}" → the shared
    // dialog → confirm → exactly one BlockSubmitter.submit against the author UUID AND every loaded
    // card by that author leaves the list (the other author's cards stay).
    @Test
    fun blockConfirm_submitsTheAuthorUuid_andRemovesOnlyThatAuthorsCards() {
        installKoin(
            GlobalTimelineOutcome.Loaded(
                listOf(
                    fakeGlobalPost(id = "g1", content = "AUTHOR_A_POST_1", authorUserId = "author-a", authorUsername = "a.jkt"),
                    fakeGlobalPost(id = "g2", content = "AUTHOR_A_POST_2", authorUserId = "author-a", authorUsername = "a.jkt"),
                    fakeGlobalPost(id = "g3", content = "AUTHOR_B_POST", authorUserId = "author-b", authorUsername = "b.jkt"),
                ),
                null,
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(POST_CARD_KEBAB_TAG).fetchSemanticsNodes().size == 3
            }
            onAllNodesWithTag(POST_CARD_KEBAB_TAG)[0].performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(POST_CARD_BLOCK_ITEM_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Blokir @a.jkt").assertExists() // the docs/03 item copy, username interpolated
            onNodeWithTag(POST_CARD_BLOCK_ITEM_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(GLOBAL_BLOCK_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Blokir").performClick() // the destructive confirm (exact match ≠ the title)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("AUTHOR_A_POST_1").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(1, blockFake.submitCount, "exactly one block submission")
            assertEquals("author-a", blockFake.lastUserId, "the block targets the tapped card's author UUID")
            onAllNodesWithText("AUTHOR_A_POST_2").assertCountEquals(0) // ALL of A's cards leave
            onNodeWithText("AUTHOR_B_POST").assertExists() // the other author's card stays
        }
    }

    // Spec § "Dismissing the dialog issues no block": Batal → zero submissions, feed unchanged.
    @Test
    fun blockDialogBatal_submitsNothing_andKeepsTheFeed() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "g1", content = "KEPT_POST")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(POST_CARD_KEBAB_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(POST_CARD_KEBAB_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(POST_CARD_BLOCK_ITEM_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(POST_CARD_BLOCK_ITEM_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(GLOBAL_BLOCK_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Batal").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(GLOBAL_BLOCK_DIALOG_TAG).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(0, blockFake.submitCount, "Batal submits nothing")
            onNodeWithText("KEPT_POST").assertExists()
        }
    }

    // The load-more footer never co-occurs with the initial-load skeleton (it lives inside the Content
    // post list, which the skeleton state does not render) — mobile-design-system load-more pattern.
    @Test
    fun loadMoreFooter_absentDuringInitialLoadSkeleton() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag(LOAD_MORE_FOOTER_TAG).assertCountEquals(0)
            onAllNodesWithTag(LOAD_MORE_RETRY_TAG).assertCountEquals(0)
        }
    }
}
