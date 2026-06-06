package id.nearyou.app.screens.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.FakeCreatePostFlow
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeGlobalTimelineFlow
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.fakeGlobalPost
import id.nearyou.app.timeline.fakeNearbyPost
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val TAB_NEARBY = "Nearby" // tab_nearby
private const val TAB_FOLLOWING = "Following" // tab_following
private const val TAB_GLOBAL = "Global" // tab_global
private const val NEARBY_TITLE = "Post dari lokasi ini" // timeline_nearby_title
private const val GLOBAL_TITLE = "Seluruh Indonesia" // timeline_global_title
private const val FOLLOWING_PLACEHOLDER = "Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu."

/**
 * iOS counterpart to [HomeTabHostScreenTest] — the Nearby/Following/Global tab host run natively on the
 * iOS simulator (task 10.1). Covers the three labelled tabs + Nearby default, switching to the Global
 * tab and rendering the live Global feed, and the Following deferred placeholder. Reuses the commonTest
 * fakes. See `id.nearyou.app.screens.auth.SignInFlowIosTest` for the v1-API + iosTest-placement
 * rationale; uses kotlin.test `@Test` with K/N-legal fn names (no `,()#`).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class HomeTabHostFlowIosTest {
    private lateinit var nearbyFake: FakeNearbyTimelineFlow
    private lateinit var globalFake: FakeGlobalTimelineFlow

    private fun installKoin(
        nearbyOutcome: NearbyTimelineOutcome =
            NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "NEARBY_POST")), null, null),
        globalOutcome: GlobalTimelineOutcome =
            GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "GLOBAL_POST")), null, null),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        nearbyFake = FakeNearbyTimelineFlow(nearbyOutcome)
        globalFake = FakeGlobalTimelineFlow(globalOutcome)
        startKoin {
            modules(
                module {
                    single<NearbyTimelineFlow> { nearbyFake }
                    single<GlobalTimelineFlow> { globalFake }
                    single<LocationPermissionController> {
                        FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
                    }
                    single<CreatePostFlow> { FakeCreatePostFlow() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // Three labelled tabs render and the host defaults to Nearby.
    @Test
    fun tabHost_rendersThreeTabs_andDefaultsToNearby() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NEARBY_TITLE).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_NEARBY).assertExists()
            onNodeWithText(TAB_FOLLOWING).assertExists()
            onNodeWithText(TAB_GLOBAL).assertExists()
        }
    }

    // Selecting the Global tab swaps the body to the live Global feed (title + a post).
    @Test
    fun switchingToGlobalTab_rendersTheGlobalFeed() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NEARBY_TITLE).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_GLOBAL).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(GLOBAL_TITLE).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("GLOBAL_POST").assertExists()
            onNodeWithText(NEARBY_TITLE).assertDoesNotExist()
        }
    }

    // The Following tab renders the deferred placeholder (no fetch).
    @Test
    fun followingTab_showsDeferredPlaceholder() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(TAB_FOLLOWING).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_FOLLOWING).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(FOLLOWING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(FOLLOWING_PLACEHOLDER).assertExists()
        }
    }
}
