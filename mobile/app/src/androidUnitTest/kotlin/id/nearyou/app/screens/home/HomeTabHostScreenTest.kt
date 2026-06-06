package id.nearyou.app.screens.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.FakeCreatePostFlow
import id.nearyou.app.screens.routing.HomeRoute
import id.nearyou.app.screens.routing.PostCreationRoute
import id.nearyou.app.screens.routing.TestNavHost
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
private const val TAB_NEARBY = "Nearby" // tab_nearby
private const val TAB_FOLLOWING = "Following" // tab_following
private const val TAB_GLOBAL = "Global" // tab_global
private const val NEARBY_TITLE = "Post dari lokasi ini" // timeline_nearby_title
private const val GLOBAL_TITLE = "Seluruh Indonesia" // timeline_global_title
private const val FOLLOWING_PLACEHOLDER = "Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu."
private const val SEE_GLOBAL = "Lihat Global" // cta_see_global
private const val FAB_POST = "Posting" // cta_post — the home-level composer FAB
private const val COMPOSER_TITLE = "Buat postingan" // post_create_title

/**
 * Render + interaction coverage of the `HomeScreen` Nearby/Following/Global **tab host** (task 9.2 +
 * `mobile-home-tab-host` spec): the three labelled tabs, default-tab = Nearby, tab selection swapping
 * the body, the home-level FAB present on every tab + pushing `PostCreationRoute` onto the ROOT back
 * stack, the Following deferred placeholder, the Nearby empty-state "lihat Global" CTA switching to the
 * Global tab, and the no-re-fetch-on-tab-switch invariant (8.5) via the feed fakes' load counters. In
 * the Release-variant `*ScreenTest` exclude (the `ui-test-manifest` host activity is debug-only).
 *
 * For the Nearby tab (gated on location), `waitUntil` polls the end state (the gate settles via the
 * GRANTED fake) rather than relying on `waitForIdle` alone, per `feedback_robolectric_async_repo_screen_test_waituntil`.
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
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun tabHost_rendersThreeLabelledTabs() {
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
            // Default authenticated tab = Nearby (design D5): the Nearby surface renders, Global does not.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NEARBY_TITLE).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(GLOBAL_TITLE).assertDoesNotExist()
        }
    }

    @Test
    fun selectingGlobalTab_swapsBodyToGlobalSurface() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NEARBY_TITLE).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_GLOBAL).performClick()
            waitForIdle()
            onNodeWithText(GLOBAL_TITLE).assertExists()
            onNodeWithText(NEARBY_TITLE).assertDoesNotExist()
        }
    }

    @Test
    fun followingTab_showsDeferredPlaceholder() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitForIdle()
            onNodeWithText(TAB_FOLLOWING).performClick()
            waitForIdle()
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
            waitForIdle()
            // …which selects the Global tab (the body renders the Global surface).
            onNodeWithText(GLOBAL_TITLE).assertExists()
        }
    }

    @Test
    fun fab_isPresentOnEachTab_andPushesComposerOntoRootStack() {
        installKoin()
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(HomeRoute, onBackStack = { backStack = it }) } }
            waitForIdle()
            // FAB present on the default (Nearby) tab…
            onNodeWithText(FAB_POST).assertExists()
            // …and still present after switching to the Global tab (one shared affordance).
            onNodeWithText(TAB_GLOBAL).performClick()
            waitForIdle()
            onNodeWithText(FAB_POST).assertExists()
            // Activating it appends PostCreationRoute to the ROOT back stack (above HomeRoute), so the
            // composer overlays the whole surface including the tab bar.
            onNodeWithText(FAB_POST).performClick()
            waitForIdle()
            assertTrue(
                backStack.last() == PostCreationRoute,
                "the FAB appends PostCreationRoute to the root back stack (was: ${backStack.toList()})",
            )
            onNodeWithText(COMPOSER_TITLE).assertExists()
        }
    }

    // 8.5 — no re-fetch on tab switch: each feed screen composes directly under the tab host (NOT in a
    // per-tab NavDisplay — design D1/D2), so each feed's viewModel { } binds to the host's persistent
    // ViewModelStore and survives the `when(selectedTab)` body swapping away and back. Leaving a feed tab
    // and returning reuses the loaded VM — the fakes' load counts stay 1 (mobile-home-tab-host §
    // "Returning to a feed tab does not re-fetch" + mobile-nearby-timeline § "Switching tabs and
    // returning to Nearby does not re-fetch"). Composed directly (not via TestNavHost) so the tab clicks
    // hit-test cleanly under the Robolectric runner; the HomeRoute-scoping across the composer
    // round-trip is covered by HomeScreenFabTest.fabRoundTripToComposer_doesNotReFetchTheNearbyFeed.
    @Test
    fun switchingTabsAndReturning_doesNotReFetchEitherFeed() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            // Nearby (default) renders (gate GRANTED → NearbyFeed) + loads once.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NEARBY_TITLE).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, nearbyFake.loadInvocationCount, "Nearby loads once on first show")

            // Switch to Global → it renders + loads once.
            onNodeWithText(TAB_GLOBAL).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(GLOBAL_TITLE).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, globalFake.loadInvocationCount, "Global loads once on first show")

            // Back to Nearby → re-renders; the retained VM did NOT re-fetch.
            onNodeWithText(TAB_NEARBY).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NEARBY_TITLE).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, nearbyFake.loadInvocationCount, "returning to Nearby must not re-fetch (VM retained)")

            // …and back to Global again → the Global VM survived too (no re-fetch).
            onNodeWithText(TAB_GLOBAL).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(GLOBAL_TITLE).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, globalFake.loadInvocationCount, "returning to Global must not re-fetch (VM retained)")
        }
    }

    // mobile-global-timeline § "Global feed load state is scoped to the HomeRoute NavEntry and survives
    // tab switch and the composer round-trip" — hosted under the REAL TestNavHost(HomeRoute), switching to
    // the Global tab then round-tripping through the composer (PostCreationRoute pushed ABOVE HomeRoute on
    // the root stack, so HomeRoute goes off-screen) must NOT re-fetch the Global feed: the
    // rememberViewModelStoreNavEntryDecorator retains the HomeRoute-scoped Global VM while HomeRoute is off
    // -screen. This is the Global analogue of HomeScreenFabTest.fabRoundTripToComposer (which proves the
    // same for Nearby), closing the Global side of the HomeRoute-scoping invariant under the real decorator.
    @Test
    fun globalFeedVm_survivesComposerRoundTrip_underHomeRouteScope() {
        installKoin()
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(HomeRoute, onBackStack = { backStack = it }) } }
            // Land on Nearby (default), then switch to the Global tab → it loads exactly once.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NEARBY_TITLE).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_GLOBAL).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(GLOBAL_TITLE).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, globalFake.loadInvocationCount, "Global loads once on first show")

            // Open the composer (FAB → append PostCreationRoute to the ROOT stack) — HomeRoute off-screen.
            onNodeWithText(FAB_POST).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(COMPOSER_TITLE).fetchSemanticsNodes().isNotEmpty() }

            // …then pop back to Home — still on the Global tab (selectedTab is rememberSaveable, preserved
            // by the SaveableStateHolder decorator) and the HomeRoute-scoped Global VM survived.
            runOnIdle { backStack.removeLastOrNull() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(GLOBAL_TITLE).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(
                1,
                globalFake.loadInvocationCount,
                "returning from the composer must not re-fetch the Global feed (the HomeRoute-scoped VM is retained)",
            )
        }
    }
}
