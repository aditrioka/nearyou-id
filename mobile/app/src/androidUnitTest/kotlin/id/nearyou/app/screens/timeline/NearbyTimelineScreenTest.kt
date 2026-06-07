package id.nearyou.app.screens.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.screens.home.HomeScreen
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeNearbyPost
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

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml) — captured here
// so the render assertions also pin the timeline copy.
private const val TITLE = "Post dari lokasi ini"
private const val LOADING = "Sedang memuat postingan…"
private const val EMPTY = "Area kamu belum ramai. Sementara lihat dari seluruh Indonesia dulu?"
private const val LIMIT_HARD = "Kamu sudah mencapai batas baca untuk jam ini. Coba lagi sebentar lagi ya."
private const val LIMIT_SOFT = "Kamu lagi aktif-aktifnya! Premium membuka akses baca tanpa batas."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val RETRY = "Coba lagi"
private const val SEE_GLOBAL = "Lihat Global" // cta_see_global — the empty-state tab-switch CTA
private const val HOME_PLACEHOLDER_TITLE = "NearYouID" // home_placeholder_title (no longer rendered)

/**
 * Render coverage of `NearbyTimelineScreen` via the Robolectric-backed CMP UI runner (tasks 8.3 / 8.7).
 * The outcome→state projection is covered purely by `NearbyTimelineUiStateTest`; this suite verifies the
 * composable renders the canonical strings for each of the six states, consumes the shared
 * `DistanceRenderer`, tolerates an empty `city_name`, leaks no author id / coordinates, re-invokes the
 * fetch on retry + pull-to-refresh, and that `HomeScreen` delegates to it.
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for why this is retained for the
 * multi-test JVM startKoin/stopKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class NearbyTimelineScreenTest {
    private lateinit var fake: FakeNearbyTimelineFlow

    // The Nearby surface is gated on location permission (mobile-location-permission-flow): bind a
    // GRANTED FakeLocationPermissionController so these six-state render assertions reach the fetch
    // path through the gate. The denied / rationale / granted-but-no-fix gate states are covered by
    // NearbyLocationGateScreenTest.
    private fun installKoin(
        outcome: NearbyTimelineOutcome = NearbyTimelineOutcome.Loaded(emptyList(), null, null),
        suspendForever: Boolean = false,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeNearbyTimelineFlow(outcome = outcome, suspendForever = suspendForever)
        startKoin {
            modules(
                module {
                    single<NearbyTimelineFlow> { fake }
                    single<LocationPermissionController> {
                        FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
                    }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // 8.3 — initial render shows the Nearby title + the loaded post content; load fires once on entry.
    @Test
    fun initialRender_showsTitleAndPostContent() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "HALO_SEKITAR")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText("HALO_SEKITAR").assertExists()
            assertEquals(1, fake.loadInvocationCount, "load fires exactly once on entry")
        }
    }

    // 8.3 — loading state: a fetch that never returns keeps the screen in-flight → loading copy shows.
    @Test
    fun loadingState_showsLoadingCopy() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            onNodeWithText(LOADING).assertExists()
        }
    }

    // 8.3 / 9.3 — empty area (Loaded, empty, no upsell) shows the sparse copy + the new "lihat Global"
    // CTA, NOT the hard-limit copy.
    @Test
    fun emptyState_showsSparseCopyAndSeeGlobalCta_notHardLimit() {
        installKoin(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            onNodeWithText(EMPTY).assertExists()
            onNodeWithText(SEE_GLOBAL).assertExists()
            onNodeWithText(LIMIT_HARD).assertDoesNotExist()
        }
    }

    // 9.3 / mobile-nearby-timeline § "Empty-state CTA switches to the Global tab" — the empty-state CTA
    // invokes the hoisted onSeeGlobal lambda (the tab host wires it to select the Global tab). Asserted
    // at the screen level via a recording callback; the host-level tab switch is covered by
    // HomeTabHostScreenTest.nearbyEmptyState_seeGlobalCta_switchesToGlobalTab.
    @Test
    fun emptyState_seeGlobalCta_invokesTheHoistedCallback() {
        installKoin(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        var seeGlobalCount = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen(onSeeGlobal = { seeGlobalCount++ }) } } }
            onNodeWithText(SEE_GLOBAL).assertExists()
            onNodeWithText(SEE_GLOBAL).performClick()
            waitForIdle()
            assertEquals(1, seeGlobalCount, "the empty-state CTA invokes the hoisted onSeeGlobal lambda")
        }
    }

    // 8.3 — hard cap (Loaded, empty, upsell.hard) shows the limit copy, NOT the sparse-area copy.
    @Test
    fun hardLimit_showsLimitCopy_notEmptyCopy() {
        installKoin(NearbyTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            onNodeWithText(LIMIT_HARD).assertExists()
            onNodeWithText(EMPTY).assertDoesNotExist()
        }
    }

    // 8.3 — soft cap (Loaded, non-empty, upsell.soft) shows the posts AND the non-blocking banner.
    @Test
    fun softLimit_showsPostsAndBanner() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "SOFT_POST")), null, UpsellDto(soft = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            onNodeWithText("SOFT_POST").assertExists()
            onNodeWithText(LIMIT_SOFT).assertExists()
        }
    }

    // 8.3 — error state shows the network copy + a clickable retry that re-invokes the fetch.
    @Test
    fun errorState_showsNetworkCopyAndRetry_andRetryReInvokes() {
        installKoin(NearbyTimelineOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            onNodeWithText(ERROR_NETWORK).assertExists()
            onNodeWithText(RETRY).assertExists()
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithText(RETRY).performClick()
            waitForIdle()
            assertEquals(2, fake.loadInvocationCount, "retry re-invokes the fetch")
        }
    }

    // 8.3 — the card consumes the shared DistanceRenderer (7600m → "8km"), asserted at card level.
    @Test
    fun distance_isRenderedThroughTheSharedRenderer() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "DIST_POST", distanceM = 7600.0)), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            onNodeWithText("8km").assertExists()
        }
    }

    // 8.3 — empty city_name renders no city label and no literal `""`; the card still renders fine.
    @Test
    fun emptyCityName_rendersNoCityLabel() {
        val emptyCityPost = fakeNearbyPost(content = "EMPTY_CITY_POST", cityName = "", distanceM = 1234.5)
        installKoin(NearbyTimelineOutcome.Loaded(listOf(emptyCityPost), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            onNodeWithText("EMPTY_CITY_POST").assertExists()
            onNodeWithText("5km").assertExists() // metadata row renders (distance) without a city label
            onNodeWithText("\"\"").assertDoesNotExist() // no literal empty-quotes leaked
        }
    }

    // 8.3 — author_user_id (a UUID) and raw coordinates are NEVER in the rendered tree (PII discipline).
    @Test
    fun noAuthorIdNorRawCoordinates_inRenderedTree() {
        installKoin(
            NearbyTimelineOutcome.Loaded(
                listOf(
                    fakeNearbyPost(
                        content = "PII_POST",
                        authorUserId = "11111111-1111-1111-1111-111111111111",
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
            onNodeWithText("PII_POST").assertExists()
            onNodeWithText("11111111-1111-1111-1111-111111111111", substring = true).assertDoesNotExist()
            onNodeWithText("-6.21", substring = true).assertDoesNotExist()
            onNodeWithText("106.85", substring = true).assertDoesNotExist()
        }
    }

    // Spec § "Pull-to-refresh re-invokes the fetch" — a pull-down on the list re-fires the first-page load.
    @Test
    fun pullToRefresh_reInvokesFetch() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "REFRESH_POST")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitForIdle()
            assertEquals(2, fake.loadInvocationCount, "pull-to-refresh re-invokes the fetch")
        }
    }

    // 8.7 — HomeScreen delegates to NearbyTimelineScreen (shows the Nearby title) and no longer renders
    // the wizard placeholder title.
    @Test
    fun homeScreen_hostsNearbyTimeline_notThePlaceholder() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "HOSTED_POST")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText("HOSTED_POST").assertExists()
            onNodeWithText(HOME_PLACEHOLDER_TITLE).assertDoesNotExist()
        }
    }

    // mobile-nearby-timeline § "Nearby post card opens post detail via a hoisted onOpenPost lambda" —
    // tapping a card invokes the hoisted onOpenPost with the card's PII-free display fields (the
    // NearbyTimelinePost projection structurally carries no author id / coordinates).
    @Test
    fun postCard_tap_invokesOnOpenPostWithDisplayFields() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(id = "p9", content = "TAP_POST", cityName = "Bandung")), null, null))
        var tapped: NearbyTimelinePost? = null
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen(onOpenPost = { tapped = it }) } } }
            onNodeWithTag(NEARBY_POST_CARD_TAG).performClick()
            waitForIdle()
            assertEquals("p9", tapped?.id)
            assertEquals("TAP_POST", tapped?.content)
            assertEquals("Bandung", tapped?.cityName)
        }
    }
}
