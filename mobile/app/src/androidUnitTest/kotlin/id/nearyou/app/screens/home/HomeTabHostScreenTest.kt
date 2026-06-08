package id.nearyou.app.screens.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.FakeCreatePostFlow
import id.nearyou.app.post.FakePostDetailFlow
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.screens.routing.HomeRoute
import id.nearyou.app.screens.routing.PostCreationRoute
import id.nearyou.app.screens.routing.PostDetailRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.screens.timeline.GLOBAL_POST_CARD_TAG
import id.nearyou.app.screens.timeline.GLOBAL_TIMELINE_LIST_TAG
import id.nearyou.app.screens.timeline.NEARBY_POST_CARD_TAG
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
private const val TAB_NEARBY = "Sekitar" // tab_nearby
private const val TAB_FOLLOWING = "Mengikuti" // tab_following
private const val TAB_GLOBAL = "Global" // tab_global
private const val FOLLOWING_PLACEHOLDER = "Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu."
private const val SEE_GLOBAL = "Lihat Global" // cta_see_global
private const val FAB_POST = "Posting" // cta_post — the home-level icon-only composer FAB contentDescription
private const val COMPOSER_TITLE = "Buat postingan" // post_create_title

/**
 * Render + interaction coverage of the `HomeScreen` Nearby/Following/Global **tab host**
 * (mobile-home-shell-redesign tasks 10.1 + the `mobile-home-tab-host` spec): the three **text-only**
 * labelled tabs, default-tab = Nearby, tapping a tab animating the swipeable `HorizontalPager`, swiping
 * the pager changing the selected feed, the **icon-only** home-level FAB (asserted via its
 * `contentDescription`) present on every page + pushing `PostCreationRoute` onto the ROOT back stack, the
 * Following deferred placeholder, the Nearby empty-state "lihat Global" CTA animating to the Global page,
 * and the no-re-fetch invariant (tab-tap AND swipe) via the feed fakes' load counters. "Which feed is on
 * screen" is asserted via the feed list **test tags** ([NEARBY_TIMELINE_LIST_TAG] /
 * [GLOBAL_TIMELINE_LIST_TAG]) — the redundant headers are removed. In the Release-variant `*ScreenTest`
 * exclude (the `ui-test-manifest` host activity is debug-only).
 *
 * For the Nearby tab (gated on location), `waitUntil` polls the end state (the gate settles via the
 * GRANTED fake), per `feedback_robolectric_async_repo_screen_test_waituntil`.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class HomeTabHostScreenTest {
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
                    // The FAB appends PostCreationRoute, whose screen injects the CreatePostFlow seam.
                    single<CreatePostFlow> { FakeCreatePostFlow() }
                    // The TestNavHost(HomeRoute) cases compose the AppShellScreen section shell, whose unread
                    // badge injects a NotificationsFlow (empty/0 fake — the badge is exercised in AppShellScreenTest).
                    single<NotificationsFlow> { FakeNotificationsFlow() }
                    // A card tap appends PostDetailRoute, whose screen injects the PostDetailFlow seam.
                    single<PostDetailFlow> { FakePostDetailFlow() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun tabHost_rendersThreeLabelledTextTabs() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitForIdle()
            onNodeWithText(TAB_NEARBY).assertExists()
            onNodeWithText(TAB_FOLLOWING).assertExists()
            onNodeWithText(TAB_GLOBAL).assertExists()
        }
    }

    @Test
    fun defaultTab_isNearby() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            // Default authenticated tab = Nearby: the Nearby surface renders, Global does not.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(GLOBAL_TIMELINE_LIST_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun tappingGlobalTab_animatesPagerToGlobalSurface() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_GLOBAL).performClick()
            // Tapping the tab animates the pager to the Global page → the Global surface renders.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun swipingThePager_changesTheSelectedFeed() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            // Swipe-left advances Nearby (page 0) → Following (page 1): the Following placeholder shows.
            onNodeWithTag(HOME_FEED_PAGER_TAG).performTouchInput { swipeLeft() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(FOLLOWING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(FOLLOWING_PLACEHOLDER).assertExists()
            // Swipe-right returns to Nearby (page 0).
            onNodeWithTag(HOME_FEED_PAGER_TAG).performTouchInput { swipeRight() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(FOLLOWING_PLACEHOLDER).assertDoesNotExist()
        }
    }

    @Test
    fun followingTab_showsDeferredPlaceholder() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitForIdle()
            onNodeWithText(TAB_FOLLOWING).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(FOLLOWING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(FOLLOWING_PLACEHOLDER).assertExists()
        }
    }

    @Test
    fun nearbyEmptyState_seeGlobalCta_switchesToGlobalTab() {
        installKoin(nearbyOutcome = NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            // Nearby (default) empty state shows the "lihat Global" CTA…
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(SEE_GLOBAL).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SEE_GLOBAL).performClick()
            // …which animates the pager to the Global page (the body renders the Global surface).
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(GLOBAL_TIMELINE_LIST_TAG).assertExists()
        }
    }

    @Test
    fun fab_isIconOnly_presentOnEachTab_andPushesComposerOntoRootStack() {
        installKoin()
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(HomeRoute, onBackStack = { backStack = it }) } }
            waitForIdle()
            // Icon-only FAB present on the default (Nearby) tab — asserted via its contentDescription
            // (no visible text label)…
            onNodeWithContentDescription(FAB_POST).assertExists()
            // …and still present after switching to the Global tab (one shared affordance).
            onNodeWithText(TAB_GLOBAL).performClick()
            waitForIdle()
            onNodeWithContentDescription(FAB_POST).assertExists()
            // Activating it appends PostCreationRoute to the ROOT back stack (above HomeRoute).
            onNodeWithContentDescription(FAB_POST).performClick()
            waitForIdle()
            assertTrue(
                backStack.last() == PostCreationRoute,
                "the FAB appends PostCreationRoute to the root back stack (was: ${backStack.toList()})",
            )
            onNodeWithText(COMPOSER_TITLE).assertExists()
        }
    }

    // No re-fetch on tab TAP: each feed page composes directly under the tab host (NOT a per-tab
    // NavDisplay — design D1/D2), so each feed's viewModel { } binds to the host's persistent
    // ViewModelStore and survives the pager swapping the page away and back. The fakes' load counts stay 1.
    @Test
    fun switchingTabsAndReturning_doesNotReFetchEitherFeed() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, nearbyFake.loadInvocationCount, "Nearby loads once on first show")

            onNodeWithText(TAB_GLOBAL).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, globalFake.loadInvocationCount, "Global loads once on first show")

            onNodeWithText(TAB_NEARBY).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, nearbyFake.loadInvocationCount, "returning to Nearby must not re-fetch (VM retained)")

            onNodeWithText(TAB_GLOBAL).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, globalFake.loadInvocationCount, "returning to Global must not re-fetch (VM retained)")
        }
    }

    // mobile-home-tab-host § "Swiping between feeds does not re-fetch" — swiping the pager (the gesture
    // surface) Nearby↔Global and back does NOT re-fetch either feed (the HomeRoute-scoped VMs survive).
    @Test
    fun swipingBetweenFeeds_doesNotReFetch() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, nearbyFake.loadInvocationCount)
            // Nearby → Following → Global (two swipe-lefts): Global loads once.
            onNodeWithTag(HOME_FEED_PAGER_TAG).performTouchInput { swipeLeft() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(FOLLOWING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(HOME_FEED_PAGER_TAG).performTouchInput { swipeLeft() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, globalFake.loadInvocationCount, "Global loads once on first show")
            // Global → Following → Nearby (two swipe-rights): Nearby is NOT re-fetched.
            onNodeWithTag(HOME_FEED_PAGER_TAG).performTouchInput { swipeRight() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(FOLLOWING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(HOME_FEED_PAGER_TAG).performTouchInput { swipeRight() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, nearbyFake.loadInvocationCount, "swiping back to Nearby must not re-fetch (VM retained)")
            assertEquals(1, globalFake.loadInvocationCount, "the Global VM survived the swipe too (no re-fetch)")
        }
    }

    // mobile-global-timeline § "Global feed load state is scoped to the HomeRoute NavEntry and survives
    // tab switch and the composer round-trip" — under the REAL TestNavHost(HomeRoute), switching to Global
    // then round-tripping through the composer (PostCreationRoute pushed ABOVE HomeRoute) must NOT re-fetch.
    @Test
    fun globalFeedVm_survivesComposerRoundTrip_underHomeRouteScope() {
        installKoin()
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(HomeRoute, onBackStack = { backStack = it }) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_GLOBAL).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, globalFake.loadInvocationCount, "Global loads once on first show")

            onNodeWithContentDescription(FAB_POST).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(COMPOSER_TITLE).fetchSemanticsNodes().isNotEmpty() }

            // Pop back to Home — still on the Global tab (selectedTab is rememberSaveable) and the
            // HomeRoute-scoped Global VM survived.
            runOnIdle { backStack.removeLastOrNull() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(
                1,
                globalFake.loadInvocationCount,
                "returning from the composer must not re-fetch the Global feed (the HomeRoute-scoped VM is retained)",
            )
        }
    }

    // mobile-home-tab-host § "The tab host hoists onOpenPost, wired at the call site to a root-stack
    // PostDetailRoute push" — tapping a Nearby card under the REAL appEntryProvider appends a
    // PostDetailRoute (distanceM non-null, NO coordinates) ABOVE HomeRoute.
    @Test
    fun tappingNearbyCard_pushesPostDetailRouteOntoRootStack() {
        installKoin()
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(HomeRoute, onBackStack = { backStack = it }) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_POST_CARD_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(NEARBY_POST_CARD_TAG).performClick()
            waitForIdle()
            val top = backStack.last()
            assertTrue(top is PostDetailRoute, "tapping a Nearby card pushes PostDetailRoute (was: ${backStack.toList()})")
            assertEquals("NEARBY_POST", top.content)
            assertTrue(top.distanceM != null, "a Nearby-origin route carries the card's distance")
        }
    }

    // mobile-global-timeline card delta — a Global card tap pushes PostDetailRoute with distanceM = null
    // (Global has no spatial filter), proving the host maps each tab's projection correctly.
    @Test
    fun tappingGlobalCard_pushesPostDetailRouteWithNullDistance() {
        installKoin()
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(HomeRoute, onBackStack = { backStack = it }) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_GLOBAL).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(GLOBAL_POST_CARD_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(GLOBAL_POST_CARD_TAG).performClick()
            waitForIdle()
            val top = backStack.last()
            assertTrue(top is PostDetailRoute, "tapping a Global card pushes PostDetailRoute (was: ${backStack.toList()})")
            assertEquals("GLOBAL_POST", top.content)
            assertEquals(null, top.distanceM, "a Global-origin route carries no distance")
        }
    }
}
