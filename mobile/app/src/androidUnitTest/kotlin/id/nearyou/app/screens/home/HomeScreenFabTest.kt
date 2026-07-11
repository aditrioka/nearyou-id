package id.nearyou.app.screens.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.block.FakeBlockSubmitter
import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.image.FakeImagePicker
import id.nearyou.app.image.FakeImageUploadRepository
import id.nearyou.app.image.ImagePicker
import id.nearyou.app.image.ImageUploader
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.FakeCreatePostFlow
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.push.fakeFcmTokenRegistrar
import id.nearyou.app.screens.routing.HomeRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.screens.timeline.FakeSelfUserId
import id.nearyou.app.screens.timeline.NEARBY_TIMELINE_LIST_TAG
import id.nearyou.app.screens.username.FakeSelfUserIdProvider
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
private const val FAB_CD = "Posting" // cta_post — the icon-only FAB contentDescription (no visible label)
private const val COMPOSER_TITLE = "Buat postingan" // post_create_title — the appended composer surface

/**
 * Render + navigation coverage of the `HomeScreen` **icon-only** compose FAB (mobile-home-shell-redesign
 * spec § "A home-surface FAB opens the composer"), plus the **reload-on-return retention** behavior
 * (`mobile-nearby-timeline` § "Nearby feed load state is scoped to the Home NavEntry and survives the
 * composer round-trip"). The FAB has NO visible text label — it is asserted via its `contentDescription`;
 * "the Nearby feed is on screen" is asserted via the feed list test tag ([NEARBY_TIMELINE_LIST_TAG], the
 * redundant header is removed). The render case composes `HomeScreen(onOpenComposer)` directly; the
 * navigation cases host the real [TestNavHost] over `HomeRoute`. In the Release-variant `*ScreenTest`
 * exclude (the `ui-test-manifest` host activity is debug-only).
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
                    single<LikeFlow> { FakeLikeFlow() }
                    single<ReportSubmitter> { FakeReportSubmitter() }
                    single<BlockSubmitter> { FakeBlockSubmitter() }
                    // mobile-nearby-radius-slider: NearbyTimelineScreen now resolves the self-profile read.
                    single<ProfileFlow> { FakeProfileFlow() }
                    single<SelfUserIdProvider> { FakeSelfUserId() }
                    single<LocationPermissionController> { FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED) }
                    // …and the FAB appends PostCreationRoute, whose screen injects the CreatePostFlow seam
                    // plus (image-attached-posts) the image-attach + Premium-gate seams.
                    single<CreatePostFlow> { FakeCreatePostFlow() }
                    single<ImagePicker> { FakeImagePicker() }
                    single<ImageUploader> { FakeImageUploadRepository() }
                    single<ProfileFlow> { FakeProfileFlow() }
                    single<SelfUserIdProvider> { FakeSelfUserIdProvider("self-id") }
                    // HomeRoute now maps to the AppShellScreen section shell, whose unread badge injects a
                    // NotificationsFlow (empty/0 fake — these tests exercise the Home section, not the badge).
                    single<NotificationsFlow> { FakeNotificationsFlow() }
                    single { fakeFcmTokenRegistrar() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun homeScreen_rendersIconOnlyComposeFab_overTheNearbyFeed() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { HomeScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertExists() // the hosted Nearby feed surface
            onNodeWithContentDescription(FAB_CD).assertExists() // the icon-only compose FAB (no text label)
            onNodeWithText(COMPOSER_TITLE).assertDoesNotExist() // composer not yet open
        }
    }

    @Test
    fun activatingTheFab_appendsTheComposer() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(HomeRoute) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription(FAB_CD).performClick()
            waitForIdle()
            // PostCreationScreen is now the current entry (its title renders); the Nearby feed is gone.
            onNodeWithText(COMPOSER_TITLE).assertExists()
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertDoesNotExist()
        }
    }

    // reload-on-return fix: with the Nearby feed's load state in a HomeRoute-scoped ViewModel
    // (rememberViewModelStoreNavEntryDecorator), pushing the composer over HomeRoute and popping back MUST
    // NOT re-fetch the feed — the VM survives the entry going off-screen. Asserted via the fake's load
    // count (1, never 2).
    @Test
    fun fabRoundTripToComposer_doesNotReFetchTheNearbyFeed() {
        installKoin()
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(HomeRoute, onBackStack = { backStack = it }) } }
            // Home → the Nearby feed loads exactly once (gate GRANTED → NearbyFeed → ViewModel init).
            waitUntil(timeoutMillis = 5_000) { nearbyFake.loadInvocationCount == 1 }
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertExists()

            // Open the composer (FAB → append PostCreationRoute) — HomeRoute goes off-screen…
            onNodeWithContentDescription(FAB_CD).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(COMPOSER_TITLE).fetchSemanticsNodes().isNotEmpty() }

            // …then pop back to Home.
            runOnIdle { backStack.removeLastOrNull() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }

            // The HomeRoute-scoped ViewModel survived → the feed did NOT re-fetch on return.
            assertEquals(
                1,
                nearbyFake.loadInvocationCount,
                "returning from the composer must not re-fetch the Nearby feed (the HomeRoute ViewModel is retained)",
            )
        }
    }
}
