package id.nearyou.app.screens.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
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

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val FAB_AND_CTA = "Posting" // cta_post — the FAB label and the composer CTA
private const val NEARBY_TITLE = "Post dari lokasi ini" // timeline_nearby_title — HomeScreen hosts Nearby
private const val COMPOSER_TITLE = "Buat postingan" // post_create_title — the appended composer surface

/**
 * Render + navigation coverage of the `HomeScreen` compose FAB (task 7.3 / spec § "A home-surface FAB
 * opens the composer"), migrated off Voyager. The render case composes `HomeScreen(onOpenComposer)`
 * directly; the FAB-opens-composer case hosts the real [TestNavHost] over `HomeRoute` and asserts that
 * activating the FAB appends `PostCreationRoute` (the composer surface becomes the current entry).
 * In the Release-variant `*ScreenTest` exclude (the `ui-test-manifest` host activity is debug-only).
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for why this is retained for the
 * multi-test JVM startKoin/stopKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class HomeScreenFabTest {
    private fun installKoin() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    // HomeScreen hosts NearbyTimelineScreen (needs the Nearby flow + a GRANTED gate)…
                    single<NearbyTimelineFlow> { FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null)) }
                    single<LocationPermissionController> { FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED) }
                    // …and the FAB appends PostCreationRoute, whose screen injects the CreatePostFlow seam.
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
}
