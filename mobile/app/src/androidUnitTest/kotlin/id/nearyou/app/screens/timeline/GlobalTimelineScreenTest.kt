package id.nearyou.app.screens.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeGlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeGlobalPost
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
private const val TITLE = "Seluruh Indonesia" // timeline_global_title
private const val LOADING = "Sedang memuat postingan…" // timeline_loading (also the Empty skeleton copy)
private const val LIMIT_HARD = "Kamu sudah mencapai batas baca untuk jam ini. Coba lagi sebentar lagi ya."
private const val LIMIT_SOFT = "Kamu lagi aktif-aktifnya! Premium membuka akses baca tanpa batas."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val RETRY = "Coba lagi"

/**
 * Render coverage of `GlobalTimelineScreen` via the Robolectric-backed CMP UI runner (task 9.1). The
 * outcome→state projection is covered purely by `GlobalTimelineUiStateTest`; this suite verifies the
 * composable renders the canonical strings for each of the six states (Global has NO location gate),
 * renders no distance, tolerates an empty `city_name`, leaks no author id / coordinates / distance, and
 * re-invokes the fetch on retry + pull-to-refresh. The `FakeGlobalTimelineFlow` is synchronous, so
 * `waitForIdle` suffices for its assertions (no MockEngine here).
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for the multi-test startKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class GlobalTimelineScreenTest {
    private lateinit var fake: FakeGlobalTimelineFlow

    private fun installKoin(
        outcome: GlobalTimelineOutcome = GlobalTimelineOutcome.Loaded(emptyList(), null, null),
        suspendForever: Boolean = false,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeGlobalTimelineFlow(outcome = outcome, suspendForever = suspendForever)
        startKoin {
            modules(module { single<GlobalTimelineFlow> { fake } })
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // 9.1 — initial render shows the Global title + the loaded post content; load fires once on entry.
    @Test
    fun initialRender_showsGlobalTitleAndPostContent() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "HALO_GLOBAL")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText("HALO_GLOBAL").assertExists()
            assertEquals(1, fake.loadInvocationCount, "load fires exactly once on entry")
        }
    }

    // 9.1 — loading state: a fetch that never returns keeps the screen in-flight → loading copy shows.
    @Test
    fun loadingState_showsLoadingCopy() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            onNodeWithText(LOADING).assertExists()
        }
    }

    // 9.1 — Empty (Loaded, empty, no upsell) reuses the loading-skeleton copy (Global is never empty).
    @Test
    fun emptyState_reusesLoadingSkeletonCopy() {
        installKoin(GlobalTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            onNodeWithText(LOADING).assertExists()
        }
    }

    // 9.1 — hard cap (Loaded, empty, upsell.hard) shows the limit copy.
    @Test
    fun hardLimit_showsLimitCopy() {
        installKoin(GlobalTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            onNodeWithText(LIMIT_HARD).assertExists()
        }
    }

    // 9.1 — soft cap (Loaded, non-empty, upsell.soft) shows the posts AND the non-blocking banner.
    @Test
    fun softLimit_showsPostsAndBanner() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "SOFT_GLOBAL")), null, UpsellDto(soft = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            onNodeWithText("SOFT_GLOBAL").assertExists()
            onNodeWithText(LIMIT_SOFT).assertExists()
        }
    }

    // 9.1 — error state shows the network copy + a clickable retry that re-invokes the fetch.
    @Test
    fun errorState_showsNetworkCopyAndRetry_andRetryReInvokes() {
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

    // 9.1 — empty city_name renders no city label and no literal `""`; the card still renders fine.
    @Test
    fun emptyCityName_rendersNoCityLabelNorLiteralQuotes() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "EMPTY_CITY_GLOBAL", cityName = "")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            onNodeWithText("EMPTY_CITY_GLOBAL").assertExists()
            onNodeWithText("\"\"").assertDoesNotExist()
        }
    }

    // 9.1 — author_user_id (a UUID), raw coordinates, AND any distance string are NEVER rendered (Global
    // has no spatial filter → no distance; PII discipline).
    @Test
    fun noAuthorIdCoordinatesNorDistance_inRenderedTree() {
        installKoin(
            GlobalTimelineOutcome.Loaded(
                listOf(
                    fakeGlobalPost(
                        content = "PII_GLOBAL",
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
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            onNodeWithText("PII_GLOBAL").assertExists()
            onNodeWithText("11111111-1111-1111-1111-111111111111", substring = true).assertDoesNotExist()
            onNodeWithText("-6.21", substring = true).assertDoesNotExist()
            onNodeWithText("106.85", substring = true).assertDoesNotExist()
            // No distance is rendered (no "km"/"m" suffix); Global has no DistanceRenderer call.
            onNodeWithText("km", substring = true).assertDoesNotExist()
        }
    }

    // Spec § "Pull-to-refresh re-invokes the fetch" — a pull-down on the list re-fires the first-page load.
    @Test
    fun pullToRefresh_reInvokesFetch() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "REFRESH_GLOBAL")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen() } } }
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithTag(GLOBAL_TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
            waitForIdle()
            assertEquals(2, fake.loadInvocationCount, "pull-to-refresh re-invokes the fetch")
        }
    }

    // mobile-global-timeline § "Global post card opens post detail via a hoisted onOpenPost lambda" —
    // tapping a card invokes the hoisted onOpenPost with the card's PII-free fields (no distance on the
    // GlobalTimelinePost projection; the host maps it to distanceM = null on the route).
    @Test
    fun postCard_tap_invokesOnOpenPostWithDisplayFields() {
        installKoin(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "g9", content = "TAP_GLOBAL", cityName = "Medan")), null, null))
        var tapped: GlobalTimelinePost? = null
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { GlobalTimelineScreen(onOpenPost = { tapped = it }) } } }
            onNodeWithTag(GLOBAL_POST_CARD_TAG).performClick()
            waitForIdle()
            assertEquals("g9", tapped?.id)
            assertEquals("TAP_GLOBAL", tapped?.content)
            assertEquals("Medan", tapped?.cityName)
            // No coordinates in the payload — GlobalTimelinePost drops the DTO's lat/long (fakeGlobalPost
            // defaults -6.21 / 106.85); structurally absent, asserted explicitly per the spec scenario.
            assertFalse(tapped.toString().contains("-6.21"), "no latitude in the onOpenPost payload")
            assertFalse(tapped.toString().contains("106.85"), "no longitude in the onOpenPost payload")
        }
    }
}
