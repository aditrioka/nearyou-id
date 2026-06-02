package id.nearyou.app.screens.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.SignInOutcome
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
private const val ERR_NO_ACCOUNT = "Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya."
private const val AGE_GATE_TITLE = "Verifikasi usia kamu" // AgeGateScreen title — proves the 404 navigation landed

/**
 * iOS counterpart to the Robolectric [SignInScreenTest] — the SAME render + sign-in→age-gate
 * navigation flow, executed natively on the iOS simulator via `:mobile:app:iosSimulatorArm64Test`
 * + the Compose Multiplatform `runComposeUiTest` runner. This is the coverage the Android-only
 * Robolectric suite cannot give: it proves the shared Compose UI + Voyager navigation + Koin DI +
 * compose-resource loading all work on the real iOS Kotlin/Native target. Fakes are reused from
 * `commonTest` (`FakeAuthFlow`, `InMemoryTokenStore`).
 *
 * Placed in `iosTest` (not `commonTest`) deliberately: the Android UI tests need Robolectric's
 * `@RunWith`/`@Config` (JVM-only), so a shared `commonTest` UI test would fail to find a host on
 * the Android unit-test target. `iosTest` keeps the Android build untouched.
 *
 * Uses the v1 `runComposeUiTest` (deprecated in CMP 1.11 but functional; UnconfinedTestDispatcher)
 * to mirror the proven Android patterns 1:1 and de-risk the first iOS UI test. Migrating both
 * suites to the v2 API (`androidx.compose.ui.test.v2`, StandardTestDispatcher) is a clean follow-up.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class SignInFlowIosTest {
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

    // Initial render shows CTA + title + disclosure (and, implicitly, that resources resolve on iOS).
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

    // Tapping the CTA invokes AuthFlow.signInWithGoogle().
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

    // NoAccount (404) navigates to AgeGateScreen carrying the id_token; NO no-account banner shown.
    @Test
    fun noAccount_navigatesToAgeGate_withNoSignInBanner() {
        installKoin(SignInOutcome.NoAccount("g-id"))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(SignInScreen()) } } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()
            onNodeWithText(AGE_GATE_TITLE).assertExists()
            onNodeWithText(ERR_NO_ACCOUNT).assertDoesNotExist()
        }
    }

    // NetworkError swaps the CTA label to retry copy and shows the network banner.
    @Test
    fun networkError_showsRetryLabelAndNetworkBanner() {
        installKoin(SignInOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(SignInScreen()) } } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()
            onNodeWithText(ERR_NETWORK).assertExists()
            onNodeWithText(CTA_RETRY).assertExists()
        }
    }
}
