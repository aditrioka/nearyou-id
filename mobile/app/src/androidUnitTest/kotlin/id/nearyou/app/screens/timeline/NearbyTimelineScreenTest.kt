package id.nearyou.app.screens.timeline

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.screens.home.HomeScreen
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeNearbyPost
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_CLOSE_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_PREMIUM_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_TAG
import id.nearyou.app.ui.components.LOAD_MORE_FOOTER_TAG
import id.nearyou.app.ui.components.LOAD_MORE_RETRY_TAG
import id.nearyou.app.ui.components.POST_CARD_LIKE_ACTION_TAG
import id.nearyou.app.ui.components.POST_CARD_LIKE_FILLED_TAG
import id.nearyou.app.ui.components.POST_CARD_LIKE_OUTLINED_TAG
import id.nearyou.app.ui.components.POST_CARD_REPLY_ACTION_TAG
import id.nearyou.app.ui.components.RADIUS_UPSELL_DIALOG_PREMIUM_TAG
import id.nearyou.app.ui.components.RADIUS_UPSELL_DIALOG_TAG
import id.nearyou.distance.LatLng
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

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml) — captured here
// so the render assertions also pin the timeline copy.
private const val REMOVED_HEADER = "Post dari lokasi ini" // timeline_nearby_title — NO LONGER rendered (header removed)
private const val LOADING = "Sedang memuat postingan…"
private const val EMPTY = "Area kamu belum ramai. Sementara lihat dari seluruh Indonesia dulu?"
private const val LIMIT_HARD = "Kamu sudah mencapai batas baca untuk jam ini. Coba lagi sebentar lagi ya."
private const val LIMIT_SOFT = "Kamu lagi aktif-aktifnya! Premium membuka akses baca tanpa batas."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…" // timeline_session_redirect (terminal 401)
private const val RETRY = "Coba lagi"
private const val SEE_GLOBAL = "Lihat Global" // cta_see_global — the empty-state tab-switch CTA
private const val HOME_PLACEHOLDER_TITLE = "NearYouID" // home_placeholder_title (no longer rendered)

/**
 * Render coverage of `NearbyTimelineScreen` via the Robolectric-backed CMP UI runner
 * (mobile-home-shell-redesign tasks 10.2). The screen is now **inset-free** (no `Scaffold`/`TopAppBar`,
 * no redundant header): "which feed is on screen" is asserted via the feed list **test tag**
 * ([NEARBY_TIMELINE_LIST_TAG]), NOT the removed header. The outcome→state projection is covered purely
 * by `NearbyTimelineUiStateTest`; this suite verifies the composable renders the canonical strings for
 * each of the six states, the initial-load skeleton vs the refresh-keeps-content split (design D3),
 * pull-to-refresh from the content AND the empty state, consumes the shared `DistanceRenderer`, tolerates
 * an empty `city_name`, leaks no author id / coordinates, and that `HomeScreen` hosts it.
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for why this is retained for the
 * multi-test JVM startKoin/stopKoin cycle. `waitUntil` polls the gate+fake settling per
 * `feedback_robolectric_async_repo_screen_test_waituntil`.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h891dp")
@OptIn(ExperimentalTestApi::class)
class NearbyTimelineScreenTest {
    private lateinit var fake: FakeNearbyTimelineFlow
    private lateinit var likeFake: FakeLikeFlow

    // The Nearby surface is gated on location permission (mobile-location-permission-flow): bind a
    // GRANTED FakeLocationPermissionController so these render assertions reach the fetch path.
    private fun installKoin(
        outcome: NearbyTimelineOutcome = NearbyTimelineOutcome.Loaded(emptyList(), null, null),
        suspendForever: Boolean = false,
        suspendFromCall: Int = Int.MAX_VALUE,
        likeOutcome: LikeOutcome = LikeOutcome.Liked,
        loadMorePages: List<NearbyTimelineOutcome> = emptyList(),
        // mobile-nearby-radius-slider: the on-entry self-isPremium read seam. Default Free (so the radius
        // slider's snap-back + upsell path is the default); a test passes true to exercise free selection.
        isPremium: Boolean = false,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake =
            FakeNearbyTimelineFlow(
                outcome = outcome,
                suspendForever = suspendForever,
                suspendFromCall = suspendFromCall,
                loadMorePages = loadMorePages,
            )
        likeFake = FakeLikeFlow(likeOutcome)
        startKoin {
            modules(
                module {
                    single<NearbyTimelineFlow> { fake }
                    single<LikeFlow> { likeFake }
                    // timeline-card-report-kebab: the report seam (self id already registered above/below).
                    single<ReportSubmitter> { FakeReportSubmitter() }
                    single<BlockSubmitter> { FakeBlockSubmitter() }
                    single<LocationPermissionController> {
                        FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
                    }
                    single<ProfileFlow> {
                        FakeProfileFlow(profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(isPremium = isPremium)))
                    }
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
    // header (the inset-free screen has no "Post dari lokasi ini" TopAppBar); load fires once on entry.
    @Test
    fun initialRender_showsPostContent_andNoHeader() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "HALO_SEKITAR")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("HALO_SEKITAR").assertExists()
            onNodeWithText(REMOVED_HEADER).assertDoesNotExist() // the redundant header is gone
            assertEquals(1, fake.loadInvocationCount, "load fires exactly once on entry")
        }
    }

    // 10.2 — initial-load skeleton: a fetch that never returns keeps the screen on the initial load →
    // the loading copy shows and the content list is NOT yet shown (one indicator; the PTR spinner,
    // driven by the separate isRefreshing flag, is not active during the initial load — design D3).
    @Test
    fun initialLoadState_showsSkeletonCopy_noContent() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(LOADING).assertExists()
            // No post card is shown during the initial load.
            onAllNodesWithTag(NEARBY_POST_CARD_TAG).fetchSemanticsNodes().let {
                assertEquals(0, it.size, "no post card during the initial-load skeleton")
            }
        }
    }

    // 10.2 / design D3 — a refresh of loaded content KEEPS the content list mounted (it does NOT revert
    // to the loading skeleton). The first load returns content; the refresh (2nd call) suspends, so the
    // refresh is in flight while we assert: the post is still shown and the skeleton copy is NOT.
    @Test
    fun refreshOfLoadedContent_keepsTheListMounted_notTheSkeleton() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "STAYS_MOUNTED")), null, null), suspendFromCall = 2)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("STAYS_MOUNTED").fetchSemanticsNodes().isNotEmpty() }
            // Pull-to-refresh → the 2nd load (suspends mid-flight).
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            // The content stays mounted during the refresh; the skeleton does NOT replace it.
            onNodeWithText("STAYS_MOUNTED").assertExists()
            onNodeWithText(LOADING).assertDoesNotExist()
        }
    }

    // 10.2 / mobile-design-system § "Pull-to-refresh is available from a non-Content state" — the empty
    // state is rendered inside a scrollable, so a pull-down re-invokes the fetch and the state stays Empty.
    @Test
    fun pullToRefresh_worksFromTheEmptyState() {
        installKoin(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(EMPTY).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            // The empty state is retained during the refresh (it does not flip to the initial-load skeleton).
            onNodeWithText(EMPTY).assertExists()
        }
    }

    // empty area (Loaded, empty, no upsell) shows the sparse copy + the "lihat Global" CTA, NOT the
    // hard-limit copy.
    @Test
    fun emptyState_showsSparseCopyAndSeeGlobalCta_notHardLimit() {
        installKoin(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(EMPTY).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(EMPTY).assertExists()
            onNodeWithText(SEE_GLOBAL).assertExists()
            onNodeWithText(LIMIT_HARD).assertDoesNotExist()
        }
    }

    // mobile-nearby-timeline § "Empty-state CTA switches to the Global tab" — the empty-state CTA invokes
    // the hoisted onSeeGlobal lambda (asserted at the screen level via a recording callback; the
    // host-level tab switch is covered by HomeTabHostScreenTest).
    @Test
    fun emptyState_seeGlobalCta_invokesTheHoistedCallback() {
        installKoin(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        var seeGlobalCount = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen(onSeeGlobal = { seeGlobalCount++ }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(SEE_GLOBAL).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SEE_GLOBAL).performClick()
            waitForIdle()
            assertEquals(1, seeGlobalCount, "the empty-state CTA invokes the hoisted onSeeGlobal lambda")
        }
    }

    // hard cap (Loaded, empty, upsell.hard) shows the limit copy, NOT the sparse-area copy.
    @Test
    fun hardLimit_showsLimitCopy_notEmptyCopy() {
        installKoin(NearbyTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LIMIT_HARD).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(LIMIT_HARD).assertExists()
            onNodeWithText(EMPTY).assertDoesNotExist()
        }
    }

    // soft cap (Loaded, non-empty, upsell.soft) shows the posts AND the non-blocking banner.
    @Test
    fun softLimit_showsPostsAndBanner() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "SOFT_POST")), null, UpsellDto(soft = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("SOFT_POST").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("SOFT_POST").assertExists()
            onNodeWithText(LIMIT_SOFT).assertExists()
        }
    }

    // error state shows the network copy + a clickable retry that re-invokes the fetch.
    @Test
    fun errorState_showsNetworkCopyAndRetry_andRetryReInvokes() {
        installKoin(NearbyTimelineOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
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
        installKoin(NearbyTimelineOutcome.SessionExpired)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(SESSION_REDIRECT).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SESSION_REDIRECT).assertExists()
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
            onNodeWithText(RETRY).assertDoesNotExist()
        }
    }

    // the card consumes the shared DistanceRenderer (7600m → "8km"), asserted at card level.
    @Test
    fun distance_isRenderedThroughTheSharedRenderer() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "DIST_POST", distanceM = 7600.0)), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("8km").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("8km").assertExists()
        }
    }

    // empty city_name renders no city label and no literal `""`; the card still renders fine.
    @Test
    fun emptyCityName_rendersNoCityLabel() {
        val emptyCityPost = fakeNearbyPost(content = "EMPTY_CITY_POST", cityName = "", distanceM = 1234.5)
        installKoin(NearbyTimelineOutcome.Loaded(listOf(emptyCityPost), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("EMPTY_CITY_POST").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("EMPTY_CITY_POST").assertExists()
            onNodeWithText("5km").assertExists() // metadata row renders (distance) without a city label
            onNodeWithText("\"\"").assertDoesNotExist() // no literal empty-quotes leaked
        }
    }

    // author_user_id (a UUID) and raw coordinates are NEVER in the rendered tree (PII discipline) —
    // while the author DISPLAY identity (mobile-timeline-card-redesign) IS rendered by the shared card.
    @Test
    fun noAuthorIdNorRawCoordinates_inRenderedTree_whileDisplayIdentityIs() {
        installKoin(
            NearbyTimelineOutcome.Loaded(
                listOf(
                    fakeNearbyPost(
                        content = "PII_POST",
                        authorUserId = "11111111-1111-1111-1111-111111111111",
                        authorUsername = "raka.jkt",
                        authorDisplayName = "Raka Pratama",
                        latitude = -6.21,
                        longitude = 106.85,
                    ),
                ),
                null,
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PII_POST").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("PII_POST").assertExists()
            onNodeWithText("Raka Pratama").assertExists()
            onNodeWithText("@raka.jkt").assertExists()
            onNodeWithText("11111111-1111-1111-1111-111111111111", substring = true).assertDoesNotExist()
            onNodeWithText("-6.21", substring = true).assertDoesNotExist()
            onNodeWithText("106.85", substring = true).assertDoesNotExist()
        }
    }

    // Spec § "Pull-to-refresh re-invokes the fetch" — a pull-down on the content list re-fires the load.
    @Test
    fun pullToRefresh_reInvokesFetch() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "REFRESH_POST")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("REFRESH_POST").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
            assertEquals(2, fake.loadInvocationCount, "pull-to-refresh re-invokes the fetch")
        }
    }

    // HomeScreen hosts NearbyTimelineScreen (the Nearby pager page) and no longer renders the wizard
    // placeholder title; "which feed" is asserted via the feed list test tag (the header is gone).
    @Test
    fun homeScreen_hostsNearbyTimeline_notThePlaceholder() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "HOSTED_POST")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("HOSTED_POST").assertExists()
            onNodeWithText(HOME_PLACEHOLDER_TITLE).assertDoesNotExist()
        }
    }

    // mobile-nearby-timeline § "Nearby post card opens post detail via a hoisted onOpenPost lambda" —
    // tapping a card invokes the hoisted onOpenPost with the card's PII-free display fields (incl. the
    // author display identity as of mobile-timeline-card-redesign).
    @Test
    fun postCard_tap_invokesOnOpenPostWithDisplayFields() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(id = "p9", content = "TAP_POST", cityName = "Bandung")), null, null))
        var tapped: NearbyTimelinePost? = null
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen(onOpenPost = { tapped = it }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_POST_CARD_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(NEARBY_POST_CARD_TAG).performClick()
            waitForIdle()
            assertEquals("p9", tapped?.id)
            assertEquals("TAP_POST", tapped?.content)
            assertEquals("Bandung", tapped?.cityName)
            assertEquals("raka.jkt", tapped?.authorUsername)
            assertEquals("Raka Pratama", tapped?.authorDisplayName)
            // No coordinates / author UUID in the payload — NearbyTimelinePost drops the DTO's lat/long
            // (fakeNearbyPost defaults -6.21 / 106.85) and the UUID; asserted per the spec scenario.
            assertFalse(tapped.toString().contains("-6.21"), "no latitude in the onOpenPost payload")
            assertFalse(tapped.toString().contains("106.85"), "no longitude in the onOpenPost payload")
            assertFalse(tapped.toString().contains("11111111-1111"), "no author UUID in the onOpenPost payload")
        }
    }

    // ---- mobile-nearby-radius-slider: the 4-position radius control + the Premium upsell ----

    // The radius slider renders on the granted Nearby feed with the 20 km default label.
    @Test
    fun radiusSlider_rendersWithDefault20kmLabel() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "X")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_RADIUS_SLIDER_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(NEARBY_RADIUS_SLIDER_TAG).assertExists()
            onNodeWithText("20 km").assertExists() // radius_value_km at the default position
        }
    }

    // A Free viewer driving the slider to a Premium-only radius → the Premium upsell dialog appears
    // (the slider snaps back to 20 km). The gate logic itself is covered by RadiusSelectionTest /
    // NearbyTimelineViewModelTest; here we assert the screen wires the one-shot to the dialog. A
    // not-yet-resolved tier (Resolving) ALSO behaves as Free, so this is robust to the on-entry read timing.
    @Test
    fun radiusSlider_freeViewer_premiumRadius_showsUpsellDialog() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "X")), null, null), isPremium = false)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_RADIUS_SLIDER_TAG).fetchSemanticsNodes().isNotEmpty() }
            // Drive the slider to index 2 (50 km, Premium-only) via the SetProgress semantics action.
            onNodeWithTag(NEARBY_RADIUS_SLIDER_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(RADIUS_UPSELL_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(RADIUS_UPSELL_DIALOG_TAG).assertExists()
            onNodeWithTag(RADIUS_UPSELL_DIALOG_PREMIUM_TAG).assertExists() // the "Aktifkan Premium" CTA
        }
    }

    // ---- mobile-inline-post-actions: inline like + the Free like-cap dialog + the reply shortcut ----

    // The verbatim docs/03:187 modal body with the frame-18 "14 j 19 mnt" countdown (51 540 s).
    private val capDialogBody =
        "Kamu sudah menggunakan 10 like hari ini. " +
            "Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam 14 j 19 mnt."

    @Test
    fun likeTap_flipsTheCardTreatment_andStandsOnSuccess() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(likedByViewer = false)), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
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
    fun rateLimitedLike_reverts_andShowsTheCapDialog_withVerbatimCopy_tutupClears() {
        installKoin(
            NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(likedByViewer = false)), null, null),
            likeOutcome = LikeOutcome.RateLimited(retryAfterSeconds = 51_540),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_LIKE_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_LIKE_ACTION_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            // The verbatim docs/03:187 body + the frame-18 CTAs; the optimistic flip is reverted behind it.
            onNodeWithText(capDialogBody).assertExists()
            onNodeWithText("Batas harian tercapai").assertExists()
            onNodeWithTag(POST_CARD_LIKE_OUTLINED_TAG, useUnmergedTree = true).assertExists()
            // Tutup clears the one-shot state; the dialog does not re-show on recomposition.
            onNodeWithTag(DAILY_CAP_DIALOG_CLOSE_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun premiumCta_invokesOnActivatePremium_andDismissesTheDialog() {
        installKoin(
            NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(likedByViewer = false)), null, null),
            likeOutcome = LikeOutcome.RateLimited(retryAfterSeconds = 1_140),
        )
        runComposeUiTest {
            var activated = 0
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen(onActivatePremium = { activated++ }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_LIKE_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_LIKE_ACTION_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            // mobile-paywall-screen (#235): "Aktifkan Premium" now invokes the hoisted onActivatePremium AND
            // dismisses the dialog. The screen stays navigation-free (holds no back stack); the host
            // (entry<HomeRoute>, via HomeScreen/AppShellScreen) pushes PaywallRoute(LIKE_CAP).
            onNodeWithTag(DAILY_CAP_DIALOG_PREMIUM_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isEmpty() }
            assertEquals(1, activated, "the cap-dialog Premium CTA invokes the hoisted onActivatePremium exactly once")
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertExists()
        }
    }

    @Test
    fun networkErrorLike_revertsSilently_noErrorSurface() {
        installKoin(
            NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(likedByViewer = false)), null, null),
            likeOutcome = LikeOutcome.NetworkError,
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_LIKE_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_LIKE_ACTION_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { likeFake.invocationCount == 1 }
            waitForIdle()
            // Reverted with NO error node, dialog, or banner — the deliberate, spec-recorded v1 posture.
            onNodeWithTag(POST_CARD_LIKE_OUTLINED_TAG, useUnmergedTree = true).assertExists()
            onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).assertCountEquals(0)
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
        }
    }

    @Test
    fun replyAffordance_invokesOnOpenPostReply_notOnOpenPost() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(id = "p7")), null, null))
        var opened = 0
        var replied: NearbyTimelinePost? = null
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        NearbyTimelineScreen(
                            onOpenPost = { opened++ },
                            onOpenPostReply = { replied = it },
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_CARD_REPLY_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_CARD_REPLY_ACTION_TAG).performClick()
            waitForIdle()
            assertEquals("p7", replied?.id, "the reply shortcut carries the tapped post")
            assertEquals(0, opened, "the reply shortcut does NOT fire the whole-card onOpenPost")
        }
    }

    // ---- mobile-nearby-timeline-infinite-scroll: cursor load-more (screen integration) ----

    // A short page-1 list keeps the footer within the load-more threshold, so the scroll-end detector
    // fires load-more without an explicit gesture; the appended page-2 content appears AND the follow-up
    // reused the page-1 anchor's cursor.
    @Test
    fun loadMore_appendsTheNextPage_reusingTheCursor() {
        installKoin(
            outcome =
                NearbyTimelineOutcome.Loaded(
                    listOf(fakeNearbyPost(id = "p1", content = "PAGE1_POST")),
                    "c1",
                    null,
                    anchor = LatLng(lat = -6.2, lng = 106.8),
                ),
            loadMorePages =
                listOf(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(id = "p2", content = "PAGE2_POST")), null, null)),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PAGE2_POST").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("PAGE1_POST").assertExists()
            onNodeWithText("PAGE2_POST").assertExists()
            assertEquals(listOf("c1"), fake.loadMoreCalls.map { it.first }, "load-more fetched the retained cursor c1")
        }
    }

    // A failed load-more shows the non-destructive retry footer (page-1 retained); tapping retry recovers.
    @Test
    fun loadMoreError_showsRetryFooter_andRetryRecovers() {
        installKoin(
            outcome =
                NearbyTimelineOutcome.Loaded(
                    listOf(fakeNearbyPost(id = "p1", content = "PAGE1_POST")),
                    "c1",
                    null,
                    anchor = LatLng(lat = -6.2, lng = 106.8),
                ),
            loadMorePages =
                listOf(
                    NearbyTimelineOutcome.NetworkError,
                    NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(id = "p2", content = "PAGE2_POST")), null, null),
                ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
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
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag(LOAD_MORE_FOOTER_TAG).assertCountEquals(0)
            onAllNodesWithTag(LOAD_MORE_RETRY_TAG).assertCountEquals(0)
        }
    }
}
