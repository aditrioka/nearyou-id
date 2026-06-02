package id.nearyou.app.screens.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.SignUpOutcome
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

/**
 * iOS counterpart to the Robolectric [AgeGateScreenTest] — the Mobile #4 age-gate render + entry
 * invariants run natively on the iOS simulator. The DOB-validation + outcome→state projection is
 * covered by the (now iOS-running) `AgeGateUiStateTest`; this verifies the composable renders the
 * canonical strings, does not auto-fire signup on entry, and leaks no PII / id_token. The Material3
 * DatePicker interaction is intentionally not driven (the Android suite avoids it too). See
 * [SignInFlowIosTest] for the v1-API + iosTest-placement rationale.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class AgeGateFlowIosTest {
    private lateinit var fake: FakeAuthFlow
    private val today = LocalDate(2026, 5, 31)

    private fun installKoin(signUpOutcome: SignUpOutcome = SignUpOutcome.Success) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeAuthFlow(signUpOutcome = signUpOutcome)
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

    // Initial render shows title + DOB label + create-account CTA + brand logo.
    @Test
    fun initialRender_showsTitleDobLabelCreateCtaAndLogo() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(AgeGateScreen("g-id", today)) } } }
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
            setContent { KoinContext { NearYouTheme { Navigator(AgeGateScreen("g-id", today)) } } }
            waitForIdle()
            assertEquals(0, fake.signUpInvocationCount, "signup must not fire on screen entry")
        }
    }

    // The carried id_token and any Google PII are never rendered into the tree.
    @Test
    fun screen_rendersNoGooglePiiNorIdToken() {
        installKoin(SignUpOutcome.Blocked)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(AgeGateScreen("g-id-SENTINEL", today)) } } }
            waitForIdle()
            onNodeWithText("g-id-SENTINEL", substring = true).assertDoesNotExist()
            onNodeWithText("test@example.com", substring = true).assertDoesNotExist()
        }
    }
}
