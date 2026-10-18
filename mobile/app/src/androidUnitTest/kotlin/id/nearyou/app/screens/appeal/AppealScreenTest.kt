package id.nearyou.app.screens.appeal

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import id.nearyou.app.appeal.AppealApiClient
import id.nearyou.app.appeal.AppealFlow
import id.nearyou.app.appeal.AppealRepository
import id.nearyou.app.appeal.AppealSession
import id.nearyou.app.appeal.AppealStatusOutcome
import id.nearyou.app.appeal.AppealSubmitOutcome
import id.nearyou.app.appeal.FakeAppealFlow
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.SignInOutcome
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.screens.auth.SIGNIN_APPEAL_TAG
import id.nearyou.app.screens.routing.AppealRoute
import id.nearyou.app.screens.routing.HomeRoute
import id.nearyou.app.screens.routing.PendingReturnDestination
import id.nearyou.app.screens.routing.PendingSignupIdentity
import id.nearyou.app.screens.routing.SessionExpiryEffect
import id.nearyou.app.screens.routing.SignInRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.screens.routing.navSavedStateConfiguration
import id.nearyou.app.theme.NearYouTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertTrue
import kotlin.time.Instant

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val INTRO = "Kamu bisa mengajukan banding atas tindakan moderasi pada akunmu. Tim kami akan meninjau dan memberi keputusan."
private const val SUBMIT = "Kirim banding"
private const val PENDING_TITLE = "Banding sedang ditinjau"
private const val REJECTED_TITLE = "Banding ditolak"
private const val REJECTED_REASON = "Alasan: Tidak cukup alasan."
private const val REJECTED_BODY = "Banding kamu ditolak. Keputusan moderasi atas akunmu tidak diubah." // appeal_rejected_body
private const val APPROVED_TITLE = "Banding diterima" // appeal_approved_title (also the re-sign-in button label)
private const val APPROVED_BODY_RESIGNIN = "Tindakan pada akunmu telah dicabut. Masuk lagi untuk melanjutkan." // appeal_approved_body
private const val APPROVED_BODY_ACTIVE = "Tindakan pada akunmu telah dicabut. Akunmu sudah aktif kembali." // appeal_approved_body_active
private const val RATE_LIMITED_2 = "Terlalu banyak permintaan. Coba lagi dalam 2 menit."
private const val COUNTER_FULL = "1000/1000"
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…" // timeline_session_redirect
private const val CTA_GOOGLE = "Masuk dengan Google" // cta_signin_google
private const val ERR_SUSPENDED = "Akun kamu sedang ditangguhkan sementara. Kamu bisa mengajukan banding." // signin_error_suspended

/**
 * Render + behavior coverage of `AppealScreen` via the Robolectric-backed CMP UI runner (task 5.5). Drives
 * each state through a `FakeAppealFlow` (the outcome→state projection is covered purely by
 * `AppealViewModelTest`): the editor form, submit→Pending, an on-entry already-pending status, a submit
 * rate-limit, a decided-rejected status + reason, and the 1000-char editor cap — plus the signed-in read
 * (no appeal token, opened from an `appeal_decided` notification): an approval renders no re-sign-in action.
 * Every session-redirect producer off the session (appeal-token `401`, no token + no session, submit `401`,
 * no-token submit) routes to sign-in; a session-read `401` leaves it to `SessionExpiryEffect`. The `bannedPath_*`
 * flows host the real [TestNavHost] from a suspended sign-in and assert where the user actually lands. The
 * `KoinContext` + multi-test startKoin cycle mirrors `SearchScreenTest`.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class AppealScreenTest {
    private lateinit var appealSession: AppealSession
    private lateinit var authFlow: FakeAuthFlow

    /** The sign-in bindings ([signIn] = what "Masuk dengan Google" resolves to) back the [TestNavHost] flows. */
    private fun installKoin(
        status: AppealStatusOutcome = AppealStatusOutcome.None,
        submit: AppealSubmitOutcome = AppealSubmitOutcome.Submitted("a1"),
        token: String? = "tok-1",
        signIn: SignInOutcome = SignInOutcome.Banned(Instant.parse("2026-07-03T00:00:00Z")),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        appealSession = AppealSession().apply { token?.let { set(it) } }
        authFlow = FakeAuthFlow(outcome = signIn)
        startKoin {
            modules(
                module {
                    single<AppealFlow> { FakeAppealFlow(statusOutcome = status, submitOutcome = submit) }
                    single { appealSession }
                    single<AuthFlow> { authFlow }
                    single { PendingSignupIdentity() }
                    single { PendingReturnDestination() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private fun ComposeUiTest.content() {
        setContent { KoinContext { NearYouTheme { AppealScreen(onBack = {}, onReSignIn = {}) } } }
    }

    @Test
    fun onEntryNoAppeal_showsTheEditorForm() {
        installKoin(status = AppealStatusOutcome.None)
        runComposeUiTest {
            content()
            onNodeWithText(INTRO).assertExists()
            onNodeWithTag(APPEAL_FIELD_TAG).assertExists()
            // A blank editor cannot submit.
            onNodeWithTag(APPEAL_SUBMIT_TAG).assertIsNotEnabled()
        }
    }

    @Test
    fun submit_transitionsToPending() {
        installKoin(status = AppealStatusOutcome.None, submit = AppealSubmitOutcome.Submitted("a1"))
        runComposeUiTest {
            content()
            onNodeWithTag(APPEAL_FIELD_TAG).performTextInput("mohon ditinjau")
            waitForIdle()
            onNodeWithTag(APPEAL_SUBMIT_TAG).performClick()
            waitForIdle()
            onNodeWithText(PENDING_TITLE).assertExists()
        }
    }

    @Test
    fun onEntryPending_showsThePendingStatus() {
        installKoin(status = AppealStatusOutcome.Pending(actionType = "suspension", createdAt = null))
        runComposeUiTest {
            content()
            onNodeWithText(PENDING_TITLE).assertExists()
        }
    }

    @Test
    fun submitRateLimited_showsTheTryLaterCopy() {
        installKoin(status = AppealStatusOutcome.None, submit = AppealSubmitOutcome.RateLimited(retryAfterSeconds = 120))
        runComposeUiTest {
            content()
            onNodeWithTag(APPEAL_FIELD_TAG).performTextInput("x")
            waitForIdle()
            onNodeWithTag(APPEAL_SUBMIT_TAG).performClick()
            waitForIdle()
            onNodeWithText(RATE_LIMITED_2).assertExists()
        }
    }

    @Test
    fun onEntryRejected_showsTheTitleAndReason() {
        installKoin(
            status =
                AppealStatusOutcome.Decided(
                    approved = false,
                    actionType = "suspension",
                    decisionReason = "Tidak cukup alasan.",
                    reviewedAt = null,
                ),
        )
        runComposeUiTest {
            content()
            onNodeWithText(REJECTED_TITLE).assertExists()
            onNodeWithText(REJECTED_REASON, substring = true).assertExists()
        }
    }

    @Test
    fun approvedViaSession_showsActiveCopy_withNoReSignInAction() {
        installKoin(status = AppealStatusOutcome.Decided(true, "suspension", null, null), token = null)
        runComposeUiTest {
            var reSignIns = 0
            setContent { KoinContext { NearYouTheme { AppealScreen(onBack = {}, onReSignIn = { reSignIns++ }) } } }
            waitForIdle()
            onNodeWithText(APPROVED_TITLE).assertExists()
            onNodeWithText(APPROVED_BODY_ACTIVE).assertExists()
            onNodeWithTag(APPEAL_RESIGNIN_TAG).assertDoesNotExist()
            onNodeWithText(APPROVED_BODY_RESIGNIN).assertDoesNotExist()
            assertEquals(0, reSignIns)
        }
    }

    @Test
    fun approvedViaAppealToken_keepsTheReSignInAction() {
        installKoin(status = AppealStatusOutcome.Decided(true, "suspension", null, null), token = "tok-1")
        runComposeUiTest {
            var reSignIns = 0
            setContent { KoinContext { NearYouTheme { AppealScreen(onBack = {}, onReSignIn = { reSignIns++ }) } } }
            waitForIdle()
            onNodeWithText(APPROVED_BODY_RESIGNIN).assertExists()
            onNodeWithTag(APPEAL_RESIGNIN_TAG).performClick()
            assertEquals(1, reSignIns)
        }
    }

    @Test
    fun rejectedViaSession_showsTitleBodyAndReason_withNoReSignInAction() {
        installKoin(status = AppealStatusOutcome.Decided(false, "suspension", "Tidak cukup alasan.", null), token = null)
        runComposeUiTest {
            content()
            onNodeWithText(REJECTED_TITLE).assertExists()
            onNodeWithText(REJECTED_BODY, substring = true).assertExists()
            onNodeWithText(REJECTED_REASON, substring = true).assertExists()
            onNodeWithTag(APPEAL_RESIGNIN_TAG).assertDoesNotExist()
        }
    }

    /** Renders the screen counting [AppealScreen]'s `onReSignIn` calls; [submitText] non-null types + submits. */
    private fun ComposeUiTest.reSignInsAfter(submitText: String? = null): Int {
        var reSignIns = 0
        setContent { KoinContext { NearYouTheme { AppealScreen(onBack = {}, onReSignIn = { reSignIns++ }) } } }
        waitForIdle()
        if (submitText != null) {
            onNodeWithTag(APPEAL_FIELD_TAG).performTextInput(submitText)
            waitForIdle()
            onNodeWithTag(APPEAL_SUBMIT_TAG).performClick()
            waitForIdle()
        }
        return reSignIns
    }

    @Test
    fun appealToken401OnEntry_routesToSignIn() {
        installKoin(status = AppealStatusOutcome.SessionExpired(viaSession = false), token = "tok-1")
        runComposeUiTest { assertEquals(1, reSignInsAfter(), "a stale appeal token has no session to invalidate") }
    }

    @Test
    fun noTokenAndNoSessionOnEntry_routesToSignIn() {
        installKoin(status = AppealStatusOutcome.SessionExpired(viaSession = false), token = null)
        runComposeUiTest { assertEquals(1, reSignInsAfter(), "a restored back stack after process death ends at sign-in") }
    }

    @Test
    fun session401OnEntry_leavesTheReRouteToSessionExpiryEffect() {
        installKoin(status = AppealStatusOutcome.SessionExpired(viaSession = true), token = null)
        runComposeUiTest {
            assertEquals(0, reSignInsAfter(), "SessionExpiryEffect already re-routes an invalidated session")
            onNodeWithText(SESSION_REDIRECT).assertExists()
        }
    }

    @Test
    fun session401AfterASuccessfulRefresh_invalidates_soSessionExpiryEffectReRoutesWithTheReturnDestination() {
        // The signed-in read (appeal_decided notification): the refresh succeeds but the retry still 401s, which
        // the shared client hands back without invalidating (#595). The appeal goes through the canonical
        // SessionInvalidator path, so the app-wide effect re-routes, keeps the return to this screen and marks
        // the sign-in involuntary (its session-expired notice); the screen itself never navigates.
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        val store = InMemoryTokenStore(TokenPair("at-stale", "rt-1", Long.MAX_VALUE))
        val invalidator = SessionInvalidator(store)
        val returnDestination = PendingReturnDestination()
        val sessionClient =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = store,
                sessionInvalidator = invalidator,
                engine =
                    MockEngine { request ->
                        if (request.url.encodedPath == "/api/v1/auth/refresh") {
                            respond(
                                """{"access_token":"at-new","refresh_token":"rt-new","expires_in":900}""",
                                HttpStatusCode.OK,
                                headersOf("Content-Type", "application/json"),
                            )
                        } else {
                            respond("", HttpStatusCode.Unauthorized, headersOf("WWW-Authenticate", "Bearer realm=\"nearyou\""))
                        }
                    },
                installLogging = false,
                nowMillis = { 0L },
            )
        val flow =
            AppealRepository(
                AppealApiClient(HttpClient(MockEngine { error("no appeal token is held") }), sessionClient = sessionClient),
                tokenStore = store,
                sessionInvalidator = invalidator,
            )
        startKoin {
            modules(
                module {
                    single<AppealFlow> { flow }
                    single { AppealSession() }
                    single { invalidator }
                    single { returnDestination }
                },
            )
        }
        runComposeUiTest {
            var reSignIns = 0
            lateinit var backStack: NavBackStack<NavKey>
            setContent {
                KoinContext {
                    backStack = rememberNavBackStack(navSavedStateConfiguration, HomeRoute, AppealRoute)
                    SessionExpiryEffect(backStack)
                    NearYouTheme { AppealScreen(onBack = {}, onReSignIn = { reSignIns++ }) }
                }
            }
            // The re-route waits on real coroutines + MockEngine (401 → refresh → retry 401 → invalidate →
            // SessionExpiryEffect), async w.r.t. compose idle. Generous ceiling for batch Robolectric CPU
            // contention (#228): 5 s timed out once on a loaded CI runner while passing locally.
            waitUntil(timeoutMillis = 30_000) { backStack.lastOrNull() == SignInRoute }
            assertEquals(listOf<NavKey>(SignInRoute), backStack.toList())
            assertNull(store.read(), "the fresh-but-rejected session is cleared")
            assertEquals(AppealRoute, returnDestination.peek(), "re-auth returns to the appeal")
            assertTrue(returnDestination.wasInvoluntary(), "the sign-in shows the session-expired notice")
            assertEquals(0, reSignIns, "the app-wide path routes, not the screen")
        }
    }

    @Test
    fun submit401_routesToSignIn() {
        installKoin(status = AppealStatusOutcome.None, submit = AppealSubmitOutcome.SessionExpired, token = "tok-1")
        runComposeUiTest { assertEquals(1, reSignInsAfter(submitText = "mohon ditinjau")) }
    }

    @Test
    fun submitWithoutAppealToken_routesToSignIn() {
        installKoin(status = AppealStatusOutcome.None, token = null)
        runComposeUiTest { assertEquals(1, reSignInsAfter(submitText = "mohon ditinjau")) }
    }

    /**
     * The real banned path through the real [TestNavHost] back stack (`[SignInRoute, AppealRoute]`): a suspended
     * sign-in → "Ajukan banding" → [act] re-routes to sign-in. The kept `SignInRoute` entry (same content key, so
     * its ViewModel survives the `replaceAll`) must show a fresh, actionable sign-in — enabled CTA, no stale
     * suspension banner or appeal entry — and the dead appeal token must be gone (#581).
     */
    private fun ComposeUiTest.assertBannedPathReSignInLandsOnAFreshSignIn(act: ComposeUiTest.() -> Unit) {
        setContent { KoinContext { TestNavHost(SignInRoute) } }
        onNodeWithText(CTA_GOOGLE).performClick()
        waitForIdle()
        onNodeWithText(ERR_SUSPENDED).assertExists()
        onNodeWithTag(SIGNIN_APPEAL_TAG).performClick()
        waitForIdle()
        act()
        waitForIdle()
        onNodeWithText(ERR_SUSPENDED).assertDoesNotExist()
        onNodeWithTag(SIGNIN_APPEAL_TAG).assertDoesNotExist()
        onNodeWithText(CTA_GOOGLE).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(2, authFlow.signInInvocationCount, "the re-route lands on a sign-in the user can actually use")
    }

    @Test
    fun bannedPath_appealToken401OnEntry_landsOnAFreshSignIn_andDropsTheToken() {
        installKoin(status = AppealStatusOutcome.SessionExpired(viaSession = false), token = "tok-stale")
        runComposeUiTest {
            assertBannedPathReSignInLandsOnAFreshSignIn {}
            assertNull(appealSession.peek(), "a rejected appeal token is dropped, never reused")
        }
    }

    @Test
    fun bannedPath_submit401_landsOnAFreshSignIn_andDropsTheToken() {
        installKoin(status = AppealStatusOutcome.None, submit = AppealSubmitOutcome.SessionExpired, token = "tok-stale")
        runComposeUiTest {
            assertBannedPathReSignInLandsOnAFreshSignIn {
                onNodeWithTag(APPEAL_FIELD_TAG).performTextInput("mohon ditinjau")
                waitForIdle()
                onNodeWithTag(APPEAL_SUBMIT_TAG).performClick()
            }
            assertNull(appealSession.peek(), "a rejected appeal token is dropped, never reused")
        }
    }

    @Test
    fun bannedPath_approvedDecisionReSignIn_landsOnAFreshSignIn() {
        installKoin(status = AppealStatusOutcome.Decided(true, "suspension", null, null), token = "tok-1")
        runComposeUiTest {
            assertBannedPathReSignInLandsOnAFreshSignIn { onNodeWithTag(APPEAL_RESIGNIN_TAG).performClick() }
        }
    }

    @Test
    fun bannedPath_backFromTheAppeal_keepsTheSuspensionAndTheDisabledCta() {
        // mobile-auth-signin: both banned sub-states remain on SignInScreen with the CTA disabled. A plain back
        // still holds the appeal token, so the kept SignInRoute shows the suspension state, not a fresh sign-in.
        installKoin(status = AppealStatusOutcome.None, token = "tok-1")
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(SignInRoute) } }
            onNodeWithText(CTA_GOOGLE).performClick()
            waitForIdle()
            onNodeWithTag(SIGNIN_APPEAL_TAG).performClick()
            waitForIdle()
            onNodeWithText(INTRO).assertExists()
            onNodeWithTag(APPEAL_BACK_TAG).performClick()
            waitForIdle()
            onNodeWithText(ERR_SUSPENDED).assertExists()
            onNodeWithTag(SIGNIN_APPEAL_TAG).assertExists()
            onNodeWithText(CTA_GOOGLE).assertIsNotEnabled()
            assertEquals("tok-1", appealSession.peek(), "a back keeps the still-valid token for a re-open")
        }
    }

    @Test
    fun editor_capsAtTheThousandCharLimit() {
        installKoin(status = AppealStatusOutcome.None)
        runComposeUiTest {
            content()
            onNodeWithTag(APPEAL_FIELD_TAG).performTextInput("a".repeat(1050))
            waitForIdle()
            onNodeWithText(COUNTER_FULL).assertExists()
        }
    }
}
