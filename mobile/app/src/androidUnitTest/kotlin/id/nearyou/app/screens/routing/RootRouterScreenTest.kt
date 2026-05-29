package id.nearyou.app.screens.routing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.SignInOutcome
import id.nearyou.app.theme.NearYouTheme
import kotlinx.coroutines.CompletableDeferred
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

private const val HOME_MARKER = "Versi 1.0" // HomeScreen's version line — unique to Home
private const val SIGNIN_MARKER = "Masuk dengan Google" // SignInScreen CTA — unique to SignIn
private const val LOGO_DESC = "NearYouID" // brand-logo contentDescription (app_name)

/**
 * Routing coverage of `RootRouterScreen` via the Robolectric CMP UI runner (§6.8 a/b/c/f).
 * Uses `waitUntil` (not `waitForIdle`) because the splash `CircularProgressIndicator` is an
 * infinite animation that never reaches global idle. The token-expiry BOUNDARY logic
 * (now+1 / exactly-now) is covered by `AuthRepositoryTest.isAuthenticated`; at the screen
 * level every authenticated state routes to Home, so 6.8d/e collapse into 6.8a here.
 *
 * Koin is started BEFORE `runComposeUiTest` (the composition's `koinInject` captures the scope
 * eagerly — starting it inside the test lambda races a closed scope).
 */
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
                    single { SessionInvalidator(InMemoryTokenStore()) }
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
            setContent { KoinContext { NearYouTheme { Navigator(RootRouterScreen()) } } }
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
            setContent { KoinContext { NearYouTheme { Navigator(RootRouterScreen()) } } }
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

                override suspend fun isAuthenticated(): Boolean = gate.await()

                override suspend fun handleTerminal401() = Unit
            }
        installKoin(neverCompletes)
        runComposeUiTest {
            // Freeze the clock so the infinite spinner animation does not advance.
            mainClock.autoAdvance = false
            setContent { KoinContext { NearYouTheme { Navigator(RootRouterScreen()) } } }

            onNodeWithContentDescription(LOGO_DESC).assertExists()
            onNodeWithText(HOME_MARKER).assertDoesNotExist()
            onNodeWithText(SIGNIN_MARKER).assertDoesNotExist()
        }
    }
}
