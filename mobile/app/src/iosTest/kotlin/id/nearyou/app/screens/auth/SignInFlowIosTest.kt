package id.nearyou.app.screens.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.auth.SignInOutcome
import id.nearyou.app.screens.routing.PendingReturnDestination
import id.nearyou.app.screens.routing.PendingSignupIdentity
import id.nearyou.app.screens.routing.SignInRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.theme.NearYouTheme
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (shared/resources strings.xml) — asserting these proves compose
// resources actually load in the iOS Kotlin/Native test binary, the surface that has historically
// crashed (MissingResourceException) when resources are not bundled.
private const val CTA_GOOGLE = "Masuk dengan Google"
private const val CTA_RETRY = "Coba lagi"
private const val TITLE = "Masuk ke NearYouID"
private const val DISCLOSURE = "Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID"
private const val ERR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val SESSION_EXPIRED = "Sesi kamu berakhir. Masuk lagi untuk lanjut." // signin_session_expired
private const val ERR_NO_ACCOUNT = "Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya."
private const val AGE_GATE_TITLE = "Verifikasi usia kamu" // AgeGateScreen title — proves the 404 navigation landed

/**
 * iOS counterpart to the Robolectric [SignInScreenTest] — the SAME render + sign-in→age-gate
 * navigation flow, executed natively on the iOS simulator via `:mobile:app:iosSimulatorArm64Test`
 * + the Compose Multiplatform `runComposeUiTest` runner. This is the coverage the Android-only
 * Robolectric suite cannot give: it proves the shared Compose UI + Navigation 3 + Koin DI +
 * compose-resource loading all work on the real iOS Kotlin/Native target. Fakes are reused from
 * `commonTest` (`FakeAuthFlow`). On-screen states compose the `SignInScreen(...)` composable directly;
 * the 404→age-gate navigation is asserted by hosting the real [TestNavHost] over `SignInRoute`.
 *
 * Placed in `iosTest` (not `commonTest`) deliberately: the Android UI tests need Robolectric's
 * `@RunWith`/`@Config` (JVM-only), so a shared `commonTest` UI test would fail to find a host on
 * the Android unit-test target. `iosTest` keeps the Android build untouched.
 *
 * Uses the v1 `runComposeUiTest` (deprecated in CMP 1.11 but functional; UnconfinedTestDispatcher)
 * to mirror the proven Android patterns 1:1. Migrating both suites to the v2 API
 * (`androidx.compose.ui.test.v2`, StandardTestDispatcher) is a clean follow-up.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class SignInFlowIosTest {
    private lateinit var fake: FakeAuthFlow

    private fun installKoin(
        outcome: SignInOutcome,
        involuntary: Boolean = false,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeAuthFlow(outcome = outcome)
        val returnDestination = PendingReturnDestination().apply { if (involuntary) capture(null) }
        startKoin {
            modules(
                module {
                    single<AuthFlow> { fake }
                    single { PendingSignupIdentity() }
                    single { returnDestination }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // Initial render shows CTA + title + disclosure (and, implicitly, that resources resolve on iOS).
    @Test
    fun initialRender_showsCtaTitleAndDisclosure() {
        installKoin(SignInOutcome.Cancelled)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SignInScreen(onSignedIn = {}, onNoAccount = {}) } } }
            onNodeWithText(CTA_GOOGLE).assertExists()
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(DISCLOSURE).assertExists()
        }
    }

    // Tapping the CTA invokes AuthFlow.signInWithGoogle().
    @Test
    fun tappingCta_invokesSignIn() {
        installKoin(SignInOutcome.Cancelled)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SignInScreen(onSignedIn = {}, onNoAccount = {}) } } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()
            assertEquals(1, fake.signInInvocationCount)
        }
    }

    // NoAccount (404) sets the in-memory identity + navigates to AgeGateScreen; NO no-account banner shown.
    @Test
    fun noAccount_navigatesToAgeGate_withNoSignInBanner() {
        installKoin(SignInOutcome.NoAccount("g-id"))
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(SignInRoute) } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()
            onNodeWithText(AGE_GATE_TITLE).assertExists()
            onNodeWithText(ERR_NO_ACCOUNT).assertDoesNotExist()
        }
    }

    // Involuntary terminal-401 re-route renders the session-expired notice (D5 — iOS parity), DISTINCT
    // from the connectivity banner; a fresh launch shows neither.
    @Test
    fun involuntaryEntry_rendersSessionExpiredNotice_notNetworkCopy() {
        installKoin(SignInOutcome.Cancelled, involuntary = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SignInScreen(onSignedIn = {}, onNoAccount = {}) } } }
            onNodeWithText(SESSION_EXPIRED).assertExists()
            onNodeWithText(ERR_NETWORK).assertDoesNotExist()
        }
    }

    @Test
    fun freshLaunch_rendersNoSessionExpiredNotice() {
        installKoin(SignInOutcome.Cancelled, involuntary = false)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SignInScreen(onSignedIn = {}, onNoAccount = {}) } } }
            onNodeWithText(SESSION_EXPIRED).assertDoesNotExist()
        }
    }

    // NetworkError swaps the CTA label to retry copy and shows the network banner.
    @Test
    fun networkError_showsRetryLabelAndNetworkBanner() {
        installKoin(SignInOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SignInScreen(onSignedIn = {}, onNoAccount = {}) } } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()
            onNodeWithText(ERR_NETWORK).assertExists()
            onNodeWithText(CTA_RETRY).assertExists()
        }
    }
}
