package id.nearyou.app.screens.routing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test

// HomeScreen hosts NearbyTimelineScreen, whose top-bar title is the unique-to-Home marker;
// SignInScreen's Google CTA is the unique-to-SignIn marker.
private const val HOME_MARKER = "Post dari lokasi ini"
private const val SIGNIN_MARKER = "Masuk dengan Google"

/**
 * iOS counterpart to the Robolectric [RootRouterScreenTest] — the auth-gated start-destination
 * decision run natively on the iOS simulator, migrated to the Nav3 [TestNavHost] (the real
 * [appEntryProvider] over a `rememberNavBackStack` seeded with `RootRoute`). `waitUntil` is used (not
 * `waitForIdle`) because the splash `CircularProgressIndicator` is an infinite animation that never
 * reaches global idle. See [id.nearyou.app.screens.auth.SignInFlowIosTest] for the v1-API +
 * iosTest-placement rationale.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class RootRouterFlowIosTest {
    private fun installKoin(authFlow: AuthFlow) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    single { authFlow }
                    // The unauthenticated route lands on SignInScreen, which koinInjects a
                    // PendingSignupIdentity (the in-memory id_token holder).
                    single { PendingSignupIdentity() }
                    // Authenticated route lands on Home → NearbyTimelineScreen, which koinInjects a
                    // NearbyTimelineFlow + a LocationPermissionController; a fast GRANTED fake lets the
                    // route reach the feed (its top-bar title is the HOME_MARKER).
                    single<NearbyTimelineFlow> {
                        FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
                    }
                    single<LocationPermissionController> {
                        FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
                    }
                    // The authenticated route lands on HomeRoute → AppShellScreen, whose unread badge
                    // injects a NotificationsFlow (empty/0 fake — the route just needs to reach the Home
                    // section, whose marker is the HOME_MARKER).
                    single<NotificationsFlow> { FakeNotificationsFlow() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // Authenticated state routes to HomeScreen (Nearby timeline).
    @Test
    fun authenticated_routesToHome() {
        installKoin(FakeAuthFlow(authenticated = true))
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(RootRoute) } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(HOME_MARKER).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(HOME_MARKER).assertExists()
            onNodeWithText(SIGNIN_MARKER).assertDoesNotExist()
        }
    }

    // Unauthenticated state routes to SignInScreen.
    @Test
    fun unauthenticated_routesToSignIn() {
        installKoin(FakeAuthFlow(authenticated = false))
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(RootRoute) } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(SIGNIN_MARKER).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(SIGNIN_MARKER).assertExists()
            onNodeWithText(HOME_MARKER).assertDoesNotExist()
        }
    }
}
