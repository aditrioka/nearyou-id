package id.nearyou.app.screens.auth

import id.nearyou.app.appeal.AppealSession
import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.auth.SignInOutcome
import id.nearyou.app.screens.routing.PendingReturnDestination
import id.nearyou.app.screens.routing.PendingSignupIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit coverage of [SignInViewModel] (audit 05-#5 migration): the single-`stateIn` uiState delegates to
 * the pure [signInUiState]; the resolved outcome survives a fresh `uiState` read (the config-change
 * proxy); the in-flight guard launches exactly one ceremony; and the no-account path stashes the
 * identity BEFORE the age-gate one-shot then clears the consumed outcome. Mirrors
 * `UsernameCustomizationViewModelTest`: an [UnconfinedTestDispatcher] installed as `Main` + a
 * `backgroundScope` collector to activate the `WhileSubscribed` share so `uiState.value` reflects state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun vm(
        authFlow: FakeAuthFlow = FakeAuthFlow(),
        pendingSignupIdentity: PendingSignupIdentity = PendingSignupIdentity(),
        pendingReturnDestination: PendingReturnDestination = PendingReturnDestination(),
        appealSession: AppealSession = AppealSession(),
    ): SignInViewModel = SignInViewModel(authFlow, pendingSignupIdentity, pendingReturnDestination, appealSession)

    @Test
    fun uiState_delegatesToSignInUiState() =
        runTest(dispatcher) {
            val viewModel = vm(authFlow = FakeAuthFlow(outcome = SignInOutcome.NetworkError))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(signInUiState(null, false).ctaLabel, viewModel.uiState.value.ctaLabel)
            assertEquals(signInUiState(null, false).errorBanner, viewModel.uiState.value.errorBanner)
            viewModel.onSignInClick()
            advanceUntilIdle()
            // NetworkError → RETRY label + NETWORK banner, exactly what signInUiState(NetworkError, false) maps.
            assertEquals(SignInCtaLabel.RETRY, viewModel.uiState.value.ctaLabel)
            assertEquals(SignInErrorBanner.NETWORK, viewModel.uiState.value.errorBanner)
        }

    @Test
    fun outcome_survivesAFreshUiStateRead() =
        runTest(dispatcher) {
            // Config-change proxy: the entry-scoped VM retains the resolved outcome; a fresh collector
            // (modeling a recreated composition) still observes the RETRY/NETWORK state, never a reset.
            val viewModel = vm(authFlow = FakeAuthFlow(outcome = SignInOutcome.NetworkError))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onSignInClick()
            advanceUntilIdle()
            assertEquals(SignInCtaLabel.RETRY, viewModel.uiState.value.ctaLabel)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(SignInCtaLabel.RETRY, viewModel.uiState.value.ctaLabel)
            assertEquals(SignInErrorBanner.NETWORK, viewModel.uiState.value.errorBanner)
        }

    @Test
    fun doubleTap_launchesExactlyOneCeremony() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val fake = FakeAuthFlow(outcome = SignInOutcome.Success, gate = gate)
            val viewModel = vm(authFlow = fake)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onSignInClick() // suspends at the gate — inFlight = true
            advanceUntilIdle()
            assertEquals(SignInCtaLabel.LOADING, viewModel.uiState.value.ctaLabel)
            assertFalse(viewModel.uiState.value.ctaEnabled)
            viewModel.onSignInClick() // guarded by the in-flight flag — no second ceremony
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, fake.signInInvocationCount)
        }

    @Test
    fun noAccount_stashesIdentityBeforeNav_andClearsConsumedOutcome() =
        runTest(dispatcher) {
            val holder = PendingSignupIdentity()
            val viewModel = vm(authFlow = FakeAuthFlow(outcome = SignInOutcome.NoAccount("g-id")), pendingSignupIdentity = holder)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onSignInClick()
            advanceUntilIdle()
            assertEquals("g-id", holder.peek(), "the verified id_token is stashed before navigation")
            assertEquals(SignInNavTarget.AgeGate, viewModel.uiState.value.navigation)
            viewModel.onNavigationHandled()
            assertNull(viewModel.uiState.value.navigation, "the one-shot clears")
            // The consumed outcome is cleared → a back-nav to a retained SignInRoute shows the clean initial
            // CTA and cannot re-raise the age-gate one-shot.
            assertEquals(SignInCtaLabel.GOOGLE, viewModel.uiState.value.ctaLabel)
        }

    @Test
    fun suspensionBanned_surfacesTheAppealEntry_withoutNavigating() =
        runTest(dispatcher) {
            // `showAppealEntry` is a VM-owned field (computed in toUiState, NOT in the pure signInUiState),
            // so it needs VM-level coverage. appeal-sign-in-ban-distinction: a SUSPENSION (non-null
            // suspended_until) surfaces the appeal entry, keeps the user on the screen (no navigation),
            // shows the SUSPENDED banner, and disables the CTA.
            val viewModel =
                vm(authFlow = FakeAuthFlow(outcome = SignInOutcome.Banned(Instant.parse("2026-07-03T00:00:00Z"))))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onSignInClick()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.showAppealEntry, "a suspension surfaces the appeal entry")
            assertNull(viewModel.uiState.value.navigation, "Banned does not navigate")
            assertEquals(SignInErrorBanner.SUSPENDED, viewModel.uiState.value.errorBanner)
            assertFalse(viewModel.uiState.value.ctaEnabled, "Banned disables the CTA")
        }

    /** A suspended sign-in whose 403 stashed [token] in [session] (what `AuthRepository` does), then opens the appeal. */
    private fun TestScope.suspendedThenAppealOpened(
        session: AppealSession,
        token: String?,
    ): SignInViewModel {
        val viewModel =
            vm(authFlow = FakeAuthFlow(outcome = SignInOutcome.Banned(Instant.parse("2026-07-03T00:00:00Z"))), appealSession = session)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        token?.let(session::set)
        viewModel.onSignInClick()
        advanceUntilIdle()
        viewModel.onAppealOpened()
        advanceUntilIdle()
        return viewModel
    }

    private fun SignInViewModel.assertUsableCleanSignIn() {
        assertTrue(uiState.value.ctaEnabled, "the returned-to CTA is usable")
        assertFalse(uiState.value.showAppealEntry, "no stale appeal entry")
        assertNull(uiState.value.errorBanner, "no stale suspension banner")
        assertNull(uiState.value.navigation)
    }

    @Test
    fun aBackFromTheAppeal_keepsTheSpecdDisabledBannedState() =
        runTest(dispatcher) {
            // mobile-auth-signin: both banned sub-states remain on SignInScreen with the CTA disabled. The token is
            // still held after a plain back, so the kept SignInRoute entry still shows the suspension + appeal entry.
            val viewModel = suspendedThenAppealOpened(AppealSession(), token = "appeal-tok")
            assertEquals(SignInErrorBanner.SUSPENDED, viewModel.uiState.value.errorBanner)
            assertFalse(viewModel.uiState.value.ctaEnabled, "Banned keeps the CTA disabled")
            assertTrue(viewModel.uiState.value.showAppealEntry, "the appeal entry is still offered")
        }

    @Test
    fun theAppealDroppingItsToken_consumesTheBannedOutcome_soTheReSignInShowsAUsableCta() =
        runTest(dispatcher) {
            // #581: the appeal's re-sign-in is replaceAll(SignInRoute), which keeps THIS entry's ViewModel (same
            // content key). Its exits drop the token (a rejected one, or an approved decision), which consumes
            // the banned outcome, so the re-sign-in lands on the clean initial CTA, not the stale ban.
            val session = AppealSession()
            val viewModel = suspendedThenAppealOpened(session, token = "appeal-tok")
            session.clear()
            advanceUntilIdle()
            viewModel.assertUsableCleanSignIn()
        }

    @Test
    fun openingTheAppealWithNoTokenHeld_consumesTheBannedOutcomeAtOnce() =
        runTest(dispatcher) {
            // A 403 without an appeal token: the appeal can only re-route to re-sign-in and no drop will follow.
            suspendedThenAppealOpened(AppealSession(), token = null).assertUsableCleanSignIn()
        }

    @Test
    fun permanentBanned_hidesTheAppealEntry_withoutNavigating() =
        runTest(dispatcher) {
            // appeal-sign-in-ban-distinction: a PERMANENT ban (null suspended_until) shows the BANNED
            // "Hubungi support" banner with NO appeal entry (the support path), still on the screen, CTA disabled.
            val viewModel = vm(authFlow = FakeAuthFlow(outcome = SignInOutcome.Banned(suspendedUntil = null)))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onSignInClick()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.showAppealEntry, "a permanent ban surfaces no appeal entry")
            assertNull(viewModel.uiState.value.navigation, "Banned does not navigate")
            assertEquals(SignInErrorBanner.BANNED, viewModel.uiState.value.errorBanner)
            assertFalse(viewModel.uiState.value.ctaEnabled, "Banned disables the CTA")
        }

    @Test
    fun success_raisesHomeOneShot() =
        runTest(dispatcher) {
            val viewModel = vm(authFlow = FakeAuthFlow(outcome = SignInOutcome.Success))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onSignInClick()
            advanceUntilIdle()
            assertEquals(SignInNavTarget.Home, viewModel.uiState.value.navigation)
            viewModel.onNavigationHandled()
            assertNull(viewModel.uiState.value.navigation)
        }

    @Test
    fun sessionExpired_capturedOnceFromInvoluntaryReturn() =
        runTest(dispatcher) {
            val involuntary = PendingReturnDestination().apply { capture(null) }
            val viewModel = vm(pendingReturnDestination = involuntary)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.sessionExpired, "an involuntary re-route shows the session-expired notice")
        }
}
