package id.nearyou.app.screens.routing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.auth.SignInOutcome
import id.nearyou.app.auth.SignUpOutcome
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDate
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

// HomeScreen now hosts NearbyTimelineScreen (mobile-nearby-timeline-screen): its top-bar title
// `timeline_nearby_title` is the unique-to-Home marker (the old "Versi 1.0" placeholder is gone).
// The authenticated home marker is the shell's always-present "Beranda" bottom-nav label (section_home)
// — robust across the feed's gate/loading/empty states (the redundant Nearby header is removed).
private const val HOME_MARKER = "Beranda"
private const val SIGNIN_MARKER = "Masuk dengan Google" // SignInScreen CTA — unique to SignIn
private const val LOGO_DESC = "NearYouID" // brand-logo contentDescription (app_name)

/**
 * Routing coverage of `RootRouterScreen` via the Robolectric CMP UI runner (§6.8 a/b/c/f), migrated
 * from the Voyager `Navigator(Screen())` harness to the Nav3 [TestNavHost] (the real
 * [appEntryProvider] over a `rememberNavBackStack` seeded with `RootRoute`): the router replaces
 * itself at launch via `backStack.replaceAll(HomeRoute/SignInRoute)`, and the visible destination
 * post-route is asserted (`mobile-auth-signin` § "RootRouterScreen routes based on token presence").
 * Uses `waitUntil` (not `waitForIdle`) because the splash `CircularProgressIndicator` is an infinite
 * animation that never reaches global idle.
 *
 * Koin is started BEFORE `runComposeUiTest` (the composition's `koinInject` captures the scope
 * eagerly). `@Suppress("DEPRECATION")`: see `SignInScreenTest` for why `KoinContext` is retained.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class RootRouterScreenTest {
    private fun installKoin(authFlow: AuthFlow) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    single { authFlow }
                    // The unauthenticated route lands on SignInScreen, which koinInjects a
                    // PendingSignupIdentity (the in-memory id_token holder) + a PendingReturnDestination
                    // (the involuntary-entry flag / return-destination holder, D5).
                    single { PendingSignupIdentity() }
                    single { PendingReturnDestination() }
                    // The authenticated route lands on Home → NearbyTimelineScreen, which koinInjects a
                    // NearbyTimelineFlow and loads on entry — provide a fast fake so the route completes.
                    single<NearbyTimelineFlow> { FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null)) }
                    // mobile-location-permission-flow: the Nearby surface is gated on a
                    // LocationPermissionController. Bind a GRANTED fake (not strictly required now that the
                    // marker is the shell's Beranda label, but keeps the Home section fully composable).
                    single<LocationPermissionController> {
                        FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
                    }
                    // The authenticated route lands on HomeRoute → AppShellScreen, whose unread badge
                    // injects a NotificationsFlow (empty/0 fake) and whose bottom-nav renders the
                    // HOME_MARKER ("Beranda") section label the route assertions key on.
                    single<NotificationsFlow> { FakeNotificationsFlow() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // 6.8a (+ d/e) — authenticated state routes to HomeScreen.
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

    // 6.8b + 6.8f — unauthenticated (incl. post-Banned restart: store cleared ⇒ read()==null
    // ⇒ isAuthenticated()==false) routes to SignInScreen.
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

    // 6.8c — splash (brand logo + spinner) renders while the token read is in flight; no
    // routing decision is made before it completes.
    @Test
    fun inFlightCheck_rendersSplashNotEitherDestination() {
        // isAuthenticated never completes → the LaunchedEffect stays suspended → splash holds.
        val neverCompletes =
            object : AuthFlow {
                private val gate = CompletableDeferred<Boolean>()

                override suspend fun signInWithGoogle() = SignInOutcome.Cancelled

                override suspend fun signUpWithGoogle(
                    idToken: String,
                    dateOfBirth: LocalDate,
                ) = SignUpOutcome.Cancelled

                override suspend fun isAuthenticated(): Boolean = gate.await()

                override suspend fun handleTerminal401() = Unit
            }
        installKoin(neverCompletes)
        runComposeUiTest {
            // Freeze the clock so the infinite spinner animation does not advance.
            mainClock.autoAdvance = false
            setContent { KoinContext { TestNavHost(RootRoute) } }

            onNodeWithContentDescription(LOGO_DESC).assertExists()
            onNodeWithText(HOME_MARKER).assertDoesNotExist()
            onNodeWithText(SIGNIN_MARKER).assertDoesNotExist()
        }
    }
}
