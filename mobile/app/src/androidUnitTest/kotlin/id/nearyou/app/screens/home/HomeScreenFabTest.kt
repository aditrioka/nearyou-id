package id.nearyou.app.screens.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.FakeCreatePostFlow
import id.nearyou.app.screens.routing.HomeRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
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
private const val FAB_AND_CTA = "Posting" // cta_post — the FAB label and the composer CTA
private const val NEARBY_TITLE = "Post dari lokasi ini" // timeline_nearby_title — HomeScreen hosts Nearby
private const val COMPOSER_TITLE = "Buat postingan" // post_create_title — the appended composer surface

/**
 * Render + navigation coverage of the `HomeScreen` compose FAB (task 7.3 / spec § "A home-surface FAB
 * opens the composer"), plus the **reload-on-return retention** behavior (§ 3.5 / `mobile-nearby-timeline`
 * § "Nearby feed load state is scoped to the Home NavEntry and survives the composer round-trip"). The
 * render case composes `HomeScreen(onOpenComposer)` directly; the navigation cases host the real
 * [TestNavHost] over `HomeRoute`. In the Release-variant `*ScreenTest` exclude (the `ui-test-manifest`
 * host activity is debug-only).
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for why this is retained for the
 * multi-test JVM startKoin/stopKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class HomeScreenFabTest {
    private lateinit var nearbyFake: FakeNearbyTimelineFlow

    private fun installKoin() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        nearbyFake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        startKoin {
            modules(
                module {
                    // HomeScreen hosts NearbyTimelineScreen (needs the Nearby flow + a GRANTED gate)…
                    single<NearbyTimelineFlow> { nearbyFake }
                    single<LocationPermissionController> { FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED) }
                    // …and the FAB appends PostCreationRoute, whose screen injects the CreatePostFlow seam.
                    single<CreatePostFlow> { FakeCreatePostFlow() }
                    // HomeRoute now maps to the AppShellScreen section shell, whose unread badge injects a
                    // NotificationsFlow (empty/0 fake — these tests exercise the Home section, not the badge).
                    single<NotificationsFlow> { FakeNotificationsFlow() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun homeScreen_rendersComposeFab_overTheNearbyFeed() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitForIdle()
            onNodeWithText(NEARBY_TITLE).assertExists() // the hosted Nearby feed
            onNodeWithText(FAB_AND_CTA).assertExists() // the compose FAB
            onNodeWithText(COMPOSER_TITLE).assertDoesNotExist() // composer not yet open
        }
    }

    @Test
    fun activatingTheFab_appendsTheComposer() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(HomeRoute) } }
            waitForIdle()
            onNodeWithText(FAB_AND_CTA).performClick()
            waitForIdle()
            // PostCreationScreen is now the current entry (its title renders); the Nearby feed is gone.
            onNodeWithText(COMPOSER_TITLE).assertExists()
            onNodeWithText(NEARBY_TITLE).assertDoesNotExist()
        }
    }

    // § 3.5 reload-on-return fix: with the Nearby feed's load state in a HomeRoute-scoped ViewModel
    // (rememberViewModelStoreNavEntryDecorator), pushing the composer over HomeRoute and popping back
    // MUST NOT re-fetch the feed — the VM survives the entry going off-screen and is cleared only when
    // HomeRoute is popped. Asserted deterministically via the fake's load count (1, never 2).
    @Test
    fun fabRoundTripToComposer_doesNotReFetchTheNearbyFeed() {
        installKoin()
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(HomeRoute, onBackStack = { backStack = it }) } }
            // Home → the Nearby feed loads exactly once (gate GRANTED → NearbyFeed → ViewModel init).
            waitUntil(timeoutMillis = 5_000) { nearbyFake.loadInvocationCount == 1 }
            onNodeWithText(NEARBY_TITLE).assertExists()

            // Open the composer (FAB → append PostCreationRoute) — HomeRoute goes off-screen…
            onNodeWithText(FAB_AND_CTA).performClick()
            waitForIdle()
            onNodeWithText(COMPOSER_TITLE).assertExists()

            // …then pop back to Home.
            runOnIdle { backStack.removeLastOrNull() }
            waitForIdle()
            onNodeWithText(NEARBY_TITLE).assertExists()

            // The HomeRoute-scoped ViewModel survived → the feed did NOT re-fetch on return.
            assertEquals(
                1,
                nearbyFake.loadInvocationCount,
                "returning from the composer must not re-fetch the Nearby feed (the HomeRoute ViewModel is retained)",
            )
        }
    }
}
