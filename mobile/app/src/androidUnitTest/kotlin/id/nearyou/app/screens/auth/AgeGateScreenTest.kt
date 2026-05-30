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
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.age_gate_title
import id.nearyou.resources.generated.resources.age_gate_under18_blocked
import id.nearyou.resources.generated.resources.cta_create_account
import id.nearyou.resources.generated.resources.signup_error_account_exists
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
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

private const val TITLE = "Verifikasi usia kamu"
private const val DOB_LABEL = "Tanggal lahir"
private const val CREATE_CTA = "Buat akun"
private const val LOGO_DESC = "NearYouID" // brand-logo contentDescription (app_name)
private const val BLOCKED_COPY = "Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas."
private const val ACCOUNT_EXISTS_COPY = "Akun sudah terdaftar. Silakan masuk."

/**
 * Render coverage of `AgeGateScreen` via the Robolectric-backed CMP UI runner (Mobile #4, tasks
 * 7.1 / 7.10 / 7.12 / 7.15). The DOB-validation + outcome→state projection is covered purely by
 * `AgeGateUiStateTest`; this suite verifies the actual composable renders the canonical strings,
 * leaks no PII, and does not auto-fire signup on entry.
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for why this is retained for
 * the multi-test JVM startKoin/stopKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class AgeGateScreenTest {
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

    // 7.1 — initial render shows title + DOB label + create-account CTA + brand logo.
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

    // 7.15 — merely reaching/composing AgeGateScreen does NOT invoke the signup flow (and thus no
    // second Google ceremony): the carried id_token is used only on a CTA tap, not on entry.
    @Test
    fun composingScreen_doesNotAutoFireSignup() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(AgeGateScreen("g-id", today)) } } }
            waitForIdle()
            assertEquals(0, fake.signUpInvocationCount, "signup must not fire on screen entry")
        }
    }

    // 7.10 — no age-gate UI renders the Google PII. Structural: the screen receives ONLY the
    // id_token (never email/displayName), and every banner is a static Res.string key (proven by
    // AgeGateUiStateTest), so no state can surface the identity payload. The id_token itself is
    // also never rendered (used only in the request body).
    @Test
    fun screen_rendersNoGooglePiiNorIdToken() {
        installKoin(SignUpOutcome.Blocked)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { Navigator(AgeGateScreen("g-id-SENTINEL", today)) } } }
            waitForIdle()
            onNodeWithText("g-id-SENTINEL", substring = true).assertDoesNotExist()
            onNodeWithText("test@example.com", substring = true).assertDoesNotExist()
            onNodeWithText("Test User", substring = true).assertDoesNotExist()
        }
    }

    // 7.12 — the Mobile #4 strings carry their exact canonical Bahasa Indonesia copy. Captured via
    // `stringResource` (the same runtime-resolution path the render assertions rely on), so this
    // verifies values without the navigation race of the account-exists banner.
    @Test
    fun mobile4Strings_haveExactCanonicalText() {
        var blocked = ""
        var createCta = ""
        var accountExists = ""
        var title = ""
        runComposeUiTest {
            setContent {
                blocked = stringResource(Res.string.age_gate_under18_blocked)
                createCta = stringResource(Res.string.cta_create_account)
                accountExists = stringResource(Res.string.signup_error_account_exists)
                title = stringResource(Res.string.age_gate_title)
            }
            waitForIdle()
        }
        assertEquals(BLOCKED_COPY, blocked)
        assertEquals(CREATE_CTA, createCta)
        assertEquals(ACCOUNT_EXISTS_COPY, accountExists)
        assertEquals(TITLE, title)
    }
}
