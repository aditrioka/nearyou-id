package id.nearyou.app.screens.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.auth.SignUpOutcome
import id.nearyou.app.screens.routing.AgeGateRoute
import id.nearyou.app.screens.routing.PendingReturnDestination
import id.nearyou.app.screens.routing.PendingSignupIdentity
import id.nearyou.app.screens.routing.RootRoute
import id.nearyou.app.screens.routing.SignInRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.theme.NearYouTheme
import kotlinx.datetime.LocalDate
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TITLE = "Verifikasi usia kamu"
private const val DOB_LABEL = "Tanggal lahir"
private const val CREATE_CTA = "Buat akun"
private const val LOGO_DESC = "NearYouID" // brand-logo contentDescription (app_name)
private const val SIGNIN_CTA = "Masuk dengan Google" // SignInScreen CTA — proves the absent-identity re-route landed

/**
 * iOS counterpart to the Robolectric [AgeGateScreenTest] — the Mobile #4 age-gate render + entry
 * invariants run natively on the iOS simulator, migrated to the Nav3 in-memory `PendingSignupIdentity`
 * holder (seeded via the test Koin module, NOT a constructor arg). Verifies the composable renders the
 * canonical strings, does not auto-fire signup on entry, leaks no PII / id_token, and — the
 * load-bearing iOS-serialization guard — re-routes to SignInRoute on a restored back stack with an
 * absent identity (the process-death contract: Nav3's saveable back stack CAN restore `AgeGateRoute`
 * on iOS, but the in-memory holder does not survive). See [SignInFlowIosTest] for the v1-API +
 * iosTest-placement rationale.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class AgeGateFlowIosTest {
    private lateinit var fake: FakeAuthFlow
    private val today = LocalDate(2026, 5, 31)

    private fun installKoin(
        signUpOutcome: SignUpOutcome = SignUpOutcome.Success,
        seedIdentity: String? = "g-id",
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeAuthFlow(signUpOutcome = signUpOutcome)
        startKoin {
            modules(
                module {
                    single<AuthFlow> { fake }
                    single { PendingSignupIdentity().apply { if (seedIdentity != null) set(seedIdentity) } }
                    // The absent-identity guard re-routes to SignInRoute, which koinInjects this (D5).
                    single { PendingReturnDestination() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // Initial render shows title + DOB label + create-account CTA + brand logo.
    @Test
    fun initialRender_showsTitleDobLabelCreateCtaAndLogo() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AgeGateScreen(onSignedUp = {}, onExitToSignIn = {}, today = today) } } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(DOB_LABEL).assertExists()
            onNodeWithText(CREATE_CTA).assertExists()
            onNodeWithContentDescription(LOGO_DESC).assertExists()
        }
    }

    // Merely composing the screen does NOT invoke signup (no second Google ceremony on entry).
    @Test
    fun composingScreen_doesNotAutoFireSignup() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AgeGateScreen(onSignedUp = {}, onExitToSignIn = {}, today = today) } } }
            waitForIdle()
            assertEquals(0, fake.signUpInvocationCount, "signup must not fire on screen entry")
        }
    }

    // The held id_token and any Google PII are never rendered into the tree.
    @Test
    fun screen_rendersNoGooglePiiNorIdToken() {
        installKoin(SignUpOutcome.Blocked, seedIdentity = "g-id-SENTINEL")
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AgeGateScreen(onSignedUp = {}, onExitToSignIn = {}, today = today) } } }
            waitForIdle()
            onNodeWithText("g-id-SENTINEL", substring = true).assertDoesNotExist()
            onNodeWithText("test@example.com", substring = true).assertDoesNotExist()
        }
    }

    // The load-bearing iOS-serialization guard (`mobile-age-gate` § "Restored back stack on
    // AgeGateRoute with absent identity"): a back stack restored to [RootRoute, SignInRoute,
    // AgeGateRoute] with the in-memory PendingSignupIdentity now empty (it did not survive process
    // death) re-routes to SignInRoute and does NOT render the signup form. Run natively on iOS — the
    // platform where Nav3's saveable back stack can actually restore AgeGateRoute.
    @Test
    fun restoredBackStackOnAgeGateRoute_absentIdentity_reRoutesToSignIn_andDoesNotRenderForm() {
        installKoin(seedIdentity = null)
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(RootRoute, SignInRoute, AgeGateRoute) } }
            waitForIdle()
            // The absent-identity guard re-routed to SignInRoute — the SignIn CTA is visible…
            onNodeWithText(SIGNIN_CTA).assertExists()
            // …and the signup form (its title) was NOT rendered.
            onNodeWithText(TITLE).assertDoesNotExist()
        }
    }
}
