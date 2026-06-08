package id.nearyou.app.screens.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.FakeCreatePostFlow
import id.nearyou.app.screens.timeline.GLOBAL_TIMELINE_LIST_TAG
import id.nearyou.app.screens.timeline.NEARBY_TIMELINE_LIST_TAG
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
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val TAB_NEARBY = "Sekitar" // tab_nearby
private const val TAB_FOLLOWING = "Mengikuti" // tab_following
private const val TAB_GLOBAL = "Global" // tab_global
private const val FOLLOWING_PLACEHOLDER = "Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu."

/**
 * iOS counterpart to [HomeTabHostScreenTest] — the Nearby/Following/Global tab host run natively on the
 * iOS simulator (task 10.4). Covers the three **text-only** labelled tabs + Nearby default, tapping the
 * Global tab animating the swipeable `HorizontalPager` to the live Global feed, the Following deferred
 * placeholder, a horizontal **swipe** changing the selected feed, and the no-re-fetch-on-swipe invariant
 * on Kotlin/Native (the `HomeRoute`-scoped VMs survive). "Which feed is on screen" is asserted via the
 * feed list test tags (the redundant headers are removed). The serializable `Tab`/`Section` saved-state
 * round-trip (which is what makes the durable selection survive process death on K/Native) is covered by
 * `TabSerializationTest` / `SectionSerializationTest` in commonTest. Reuses the commonTest fakes. See
 * `id.nearyou.app.screens.auth.SignInFlowIosTest` for the v1-API + iosTest-placement rationale; uses
 * kotlin.test `@Test` with K/N-legal fn names (no `,()#`).
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

    // Three text-only labelled tabs render and the host defaults to Nearby.
    @Test
    fun tabHost_rendersThreeTabs_andDefaultsToNearby() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_NEARBY).assertExists()
            onNodeWithText(TAB_FOLLOWING).assertExists()
            onNodeWithText(TAB_GLOBAL).assertExists()
        }
    }

    // Tapping the Global tab animates the pager to the live Global feed (the list tag + a post).
    @Test
    fun tappingGlobalTab_rendersTheGlobalFeed() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_GLOBAL).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("GLOBAL_POST").assertExists()
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertDoesNotExist()
        }
    }

    // Swiping the pager left changes Nearby → Following (placeholder), right returns to Nearby — on K/Native.
    @Test
    fun swipingThePager_changesTheSelectedFeed() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(HOME_FEED_PAGER_TAG).performTouchInput { swipeLeft() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(FOLLOWING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(FOLLOWING_PLACEHOLDER).assertExists()
            onNodeWithTag(HOME_FEED_PAGER_TAG).performTouchInput { swipeRight() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, nearbyFake.loadInvocationCount, "swiping away and back must not re-fetch Nearby on K/Native (VM retained)")
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
