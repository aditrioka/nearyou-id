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
import kotlin.test.assertNull

private const val TITLE = "Verifikasi usia kamu"
private const val DOB_LABEL = "Tanggal lahir"
private const val CREATE_CTA = "Buat akun"
private const val LOGO_DESC = "NearYouID" // brand-logo contentDescription (app_name)
private const val SIGNIN_CTA = "Masuk dengan Google" // SignInScreen CTA — proves the absent-identity re-route landed
private const val BLOCKED_COPY = "Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas."
private const val ACCOUNT_EXISTS_COPY = "Akun sudah terdaftar. Silakan masuk."

/**
 * Render coverage of `AgeGateScreen` via the Robolectric-backed CMP UI runner (Mobile #4 +
 * `mobile-nav-swap-to-navigation3` tasks 7.1 / 7.4 / 7.10 / 7.12 / 7.15), migrated off Voyager. The
 * verified identity is read from the in-memory `PendingSignupIdentity` holder (seeded via the test
 * Koin module, NOT a constructor arg). The DOB-validation + outcome→state projection is covered by
 * `AgeGateUiStateTest`; the holder primitive by `PendingSignupIdentityTest`; and the terminal-exit
 * clear/navigate decision (Success / AccountExists clear + route; retryable survives) by the pure
 * `AgeGateOutcomeHandlerTest` (which tests the production `handleAgeGateTerminalOutcome` seam without
 * driving the Material 3 DOB picker — whose day cells expose only a locale-formatted
 * `contentDescription`, not addressable text). This suite verifies the composable renders the
 * canonical strings, leaks no PII, does not auto-fire signup on entry, and — the process-death guard
 * — re-routes to SignIn (and leaves the holder cleared) on a restored back stack with an absent
 * identity.
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
    private lateinit var holder: PendingSignupIdentity
    private val today = LocalDate(2026, 5, 31)

    private fun installKoin(
        signUpOutcome: SignUpOutcome = SignUpOutcome.Success,
        seedIdentity: String? = "g-id",
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeAuthFlow(signUpOutcome = signUpOutcome)
        // Capture the holder instance so the process-death guard test can assert it ends up cleared.
        holder = PendingSignupIdentity().apply { if (seedIdentity != null) set(seedIdentity) }
        startKoin {
            modules(
                module {
                    single<AuthFlow> { fake }
                    single { holder }
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

    // 7.1 — initial render shows title + DOB label + create-account CTA + brand logo.
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

    // 7.15 — merely reaching/composing AgeGateScreen does NOT invoke the signup flow (and thus no
    // second Google ceremony): the held id_token is used only on a CTA tap, not on entry.
    @Test
    fun composingScreen_doesNotAutoFireSignup() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AgeGateScreen(onSignedUp = {}, onExitToSignIn = {}, today = today) } } }
            waitForIdle()
            assertEquals(0, fake.signUpInvocationCount, "signup must not fire on screen entry")
        }
    }

    // 7.10 — no age-gate UI renders the Google PII nor the held id_token. Structural: the screen reads
    // ONLY the id_token from the holder (never email/displayName), every banner is a static Res.string
    // key (proven by AgeGateUiStateTest), and the id_token is used only in the request body.
    @Test
    fun screen_rendersNoGooglePiiNorIdToken() {
        installKoin(SignUpOutcome.Blocked, seedIdentity = "g-id-SENTINEL")
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AgeGateScreen(onSignedUp = {}, onExitToSignIn = {}, today = today) } } }
            waitForIdle()
            onNodeWithText("g-id-SENTINEL", substring = true).assertDoesNotExist()
            onNodeWithText("test@example.com", substring = true).assertDoesNotExist()
            onNodeWithText("Test User", substring = true).assertDoesNotExist()
        }
    }

    // 7.4 — restored back stack on AgeGateRoute with an EMPTY PendingSignupIdentity (it did not survive
    // process death) re-routes to SignInRoute, does NOT render the signup form, and leaves the holder
    // cleared (mobile-age-gate § "Restored back stack on AgeGateRoute with absent identity ..."). Hosted
    // in the real TestNavHost so the one-shot re-route (replaceAll(SignInRoute)) is exercised end-to-end.
    @Test
    fun restoredBackStackOnAgeGateRoute_absentIdentity_reRoutesToSignIn_andDoesNotRenderForm() {
        installKoin(seedIdentity = null)
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(RootRoute, SignInRoute, AgeGateRoute) } }
            waitForIdle()
            onNodeWithText(SIGNIN_CTA).assertExists() // the absent-identity guard re-routed to SignInRoute…
            onNodeWithText(TITLE).assertDoesNotExist() // …and the signup form was NOT rendered.
            assertNull(holder.peek(), "the absent-identity guard leaves the holder cleared")
        }
    }

    // 7.12 — the Mobile #4 strings carry their exact canonical Bahasa Indonesia copy. Captured via
    // `stringResource` (the same runtime-resolution path the render assertions rely on).
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
