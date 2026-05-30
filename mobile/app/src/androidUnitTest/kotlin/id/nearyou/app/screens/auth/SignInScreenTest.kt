package id.nearyou.app.screens.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.SignInOutcome
import id.nearyou.app.theme.NearYouTheme
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

private const val CTA_GOOGLE = "Masuk dengan Google"
private const val CTA_RETRY = "Coba lagi"
private const val TITLE = "Masuk ke NearYouID"
private const val DISCLOSURE = "Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID"
private const val ERR_NO_ACCOUNT = "Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya."
private const val ERR_BANNED = "Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru."
private const val ERR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val AGE_GATE_TITLE = "Verifikasi usia kamu" // AgeGateScreen title — proves the 404 navigation landed

/**
 * Render + interaction coverage of `SignInScreen` via the Robolectric-backed CMP UI runner
 * (§6.7 a/b/c/d/e/i). The platform-agnostic outcome→state mapping is also covered by the pure
 * `SignInUiStateTest`; this suite verifies the actual composable renders + reacts.
 *
 * Koin is started BEFORE `runComposeUiTest` (the composition's `koinInject` captures the scope
 * eagerly — starting it inside the test lambda races a closed scope). `sdk = 33` matches the
 * cached Robolectric android-all jar; `ui-test-manifest` (debug dep) supplies the host activity.
 *
 * `@Suppress("DEPRECATION")`: `KoinContext` is deprecated in koin-compose 4.1.0 ("setup with
 * startKoin()"), but that assumes a clean single-start. In this multi-test JVM the
 * global-context fallback caches a stale/closed scope across the per-test startKoin/stopKoin
 * cycle, so `koinInject` hits a closed scope without an explicit re-bind. `KoinContext` re-binds
 * the composition to the freshly-started test Koin; keep it until koin-compose offers a
 * non-deprecated test seam.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class SignInScreenTest {
    private lateinit var fake: FakeAuthFlow

    private fun installKoin(outcome: SignInOutcome) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeAuthFlow(outcome = outcome)
        startKoin {
            modules(
                module {
                    single<AuthFlow> { fake }
                    single { SessionInvalidator(InMemoryTokenStore()) }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // 6.7a — initial render shows CTA + title + disclosure.
    @Test
    fun initialRender_showsCtaTitleAndDisclosure() {
        installKoin(SignInOutcome.Cancelled)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(SignInScreen()) } } }
            onNodeWithText(CTA_GOOGLE).assertExists()
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(DISCLOSURE).assertExists()
        }
    }

    // 6.7b — tapping the CTA invokes AuthFlow.signInWithGoogle().
    @Test
    fun tappingCta_invokesSignIn() {
        installKoin(SignInOutcome.Cancelled)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(SignInScreen()) } } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()
            assertEquals(1, fake.signInInvocationCount)
        }
    }

    // 7.11 (Mobile #4 MODIFIED 404 routing) — NoAccount navigates to AgeGateScreen carrying the
    // id_token; NO signin_error_no_account banner is shown on SignInScreen.
    @Test
    fun noAccount_navigatesToAgeGate_withNoSignInBanner() {
        installKoin(SignInOutcome.NoAccount("g-id"))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(SignInScreen()) } } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()
            // Landed on AgeGateScreen (its title renders)…
            onNodeWithText(AGE_GATE_TITLE).assertExists()
            // …and the retired no-account banner is NOT shown anywhere.
            onNodeWithText(ERR_NO_ACCOUNT).assertDoesNotExist()
        }
    }

    // 6.7d — Banned renders the banned banner, disables the CTA, AND a second (raw-pointer) tap
    // on the disabled CTA does NOT re-invoke signInWithGoogle (visual-AND-tap-handler disable).
    @Test
    fun bannedOutcome_disablesCtaAndRejectsSecondTap() {
        installKoin(SignInOutcome.Banned)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(SignInScreen()) } } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()

            onNodeWithText(ERR_BANNED).assertExists()
            onNodeWithText(CTA_GOOGLE).assertIsNotEnabled()

            // Raw pointer tap on the now-disabled CTA (bypasses the absent OnClick semantics
            // action that performClick would require) must NOT re-enter the flow.
            onNodeWithText(CTA_GOOGLE).performTouchInput { click() }
            waitForIdle()
            assertEquals(1, fake.signInInvocationCount, "disabled CTA tap must be rejected")
        }
    }

    // 6.7e — NetworkError swaps the CTA label to the retry copy and shows the network banner.
    @Test
    fun networkErrorOutcome_showsRetryLabelAndNetworkBanner() {
        installKoin(SignInOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(SignInScreen()) } } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()
            onNodeWithText(ERR_NETWORK).assertExists()
            onNodeWithText(CTA_RETRY).assertExists()
        }
    }

    // 6.7i — no error-state UI renders Google email / displayName. Structural: the screen never
    // receives PII (AuthRepository consumes it only for the API body; SignInOutcome carries none).
    // Uses Banned (a state that REMAINS on SignInScreen) — 404 no longer produces a SignInScreen
    // error state (it navigates to AgeGateScreen, whose PII discipline is covered by AgeGateScreenTest).
    @Test
    fun errorState_rendersNoGooglePii() {
        installKoin(SignInOutcome.Banned)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(SignInScreen()) } } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()
            onNodeWithText("test@example.com", substring = true).assertDoesNotExist()
            onNodeWithText("Test User", substring = true).assertDoesNotExist()
        }
    }
}
