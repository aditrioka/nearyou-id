package id.nearyou.app.screens.auth

import id.nearyou.app.auth.FakeAuthFlow
import id.nearyou.app.auth.SignUpOutcome
import id.nearyou.app.screens.routing.PendingSignupIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage of [AgeGateViewModel] (audit 05-#5 migration): the single-`stateIn` uiState delegates to
 * the pure [ageGateUiState]; the picked DOB AND an open picker survive a fresh `uiState` read (the
 * config-change proxy — D5); the terminal-exit seam clears the holder on Success/AccountExists but NOT on
 * a retryable error; the absent-identity guard resolves synchronously in the initial state; and the
 * in-flight guard launches exactly one signup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgeGateViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate(2026, 1, 1)

    // 2000-01-01T00:00:00Z — a valid, non-future DOB relative to `today` (submittable; never an 18+ gate).
    private val dobMillis = 946_684_800_000L
    private val dobDate = LocalDate(2000, 1, 1)

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun holderWith(token: String? = "g-id") = PendingSignupIdentity().apply { if (token != null) set(token) }

    private fun vm(
        authFlow: FakeAuthFlow = FakeAuthFlow(),
        holder: PendingSignupIdentity = holderWith(),
    ): AgeGateViewModel = AgeGateViewModel(authFlow, holder, today)

    @Test
    fun uiState_delegatesToAgeGateUiState_andSurfacesTheDob() =
        runTest(dispatcher) {
            val viewModel = vm()
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            // No DOB → CREATE label, disabled — matches ageGateUiState(null, dobSubmittable = false, false).
            assertEquals(AgeGateCtaLabel.CREATE, viewModel.uiState.value.ctaLabel)
            assertFalse(viewModel.uiState.value.ctaEnabled)
            viewModel.onDobPicked(dobMillis)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.ctaEnabled, "a submittable DOB enables the CTA")
            assertEquals(dobDate, viewModel.uiState.value.selectedDob)
        }

    @Test
    fun pickedDob_andOpenPicker_surviveAFreshUiStateRead() =
        runTest(dispatcher) {
            // Config-change proxy (D5): the picked DOB and an open picker dialog survive a recreated
            // composition because the VM retains them. A fresh collector still observes both.
            val viewModel = vm()
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onDobPicked(dobMillis) // sets the DOB, closes the picker
            viewModel.onShowPicker(true) // re-open the picker
            advanceUntilIdle()
            assertEquals(dobDate, viewModel.uiState.value.selectedDob)
            assertTrue(viewModel.uiState.value.showPicker)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(dobDate, viewModel.uiState.value.selectedDob, "the picked DOB survives a fresh read")
            assertTrue(viewModel.uiState.value.showPicker, "the open picker survives a fresh read (D5)")
        }

    @Test
    fun terminalSuccess_clearsHolder_andRaisesHomeOneShot() =
        runTest(dispatcher) {
            val holder = holderWith()
            val viewModel = vm(authFlow = FakeAuthFlow(signUpOutcome = SignUpOutcome.Success), holder = holder)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onDobPicked(dobMillis)
            advanceUntilIdle()
            viewModel.onCreateAccountClick()
            advanceUntilIdle()
            assertNull(holder.peek(), "Success is terminal — the holder is cleared via the reused seam")
            assertEquals(AgeGateNavTarget.Home, viewModel.uiState.value.navigation)
        }

    @Test
    fun accountExists_clearsHolder_andRaisesSignInOneShot() =
        runTest(dispatcher) {
            val holder = holderWith()
            val viewModel = vm(authFlow = FakeAuthFlow(signUpOutcome = SignUpOutcome.AccountExists), holder = holder)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onDobPicked(dobMillis)
            advanceUntilIdle()
            viewModel.onCreateAccountClick()
            advanceUntilIdle()
            assertNull(holder.peek(), "AccountExists is terminal — the holder is cleared")
            assertEquals(AgeGateNavTarget.SignIn, viewModel.uiState.value.navigation)
        }

    @Test
    fun retryableError_keepsHolder_andDoesNotNavigate() =
        runTest(dispatcher) {
            val holder = holderWith()
            val viewModel = vm(authFlow = FakeAuthFlow(signUpOutcome = SignUpOutcome.RetryableError), holder = holder)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onDobPicked(dobMillis)
            advanceUntilIdle()
            viewModel.onCreateAccountClick()
            advanceUntilIdle()
            assertEquals("g-id", holder.peek(), "a retryable error must NOT clear the holder (resubmit re-reads it)")
            assertNull(viewModel.uiState.value.navigation, "a retryable error does not navigate")
            assertEquals(AgeGateBanner.NETWORK, viewModel.uiState.value.banner)
            assertEquals(AgeGateCtaLabel.RETRY, viewModel.uiState.value.ctaLabel)
        }

    @Test
    fun absentIdentityOnInit_raisesSignInReRoute_clearsHolder_andFlagsAbsentInInitialState() =
        runTest(dispatcher) {
            val holder = holderWith(token = null) // restored back stack / process death — empty holder
            val viewModel = vm(holder = holder)
            // The guard runs in the initial state at construction (before any collection), so the form-suppress
            // flag + re-route are visible immediately — the screen never renders the signup form for a frame.
            assertTrue(viewModel.uiState.value.identityAbsent)
            assertEquals(AgeGateNavTarget.SignIn, viewModel.uiState.value.navigation)
            assertNull(holder.peek(), "the absent-identity guard clears the holder (idempotent)")
        }

    @Test
    fun emptyInviteCode_withSubmittableDob_keepsTheCtaEnabled() =
        runTest(dispatcher) {
            // mobile-referral D5 — the optional invite code lives in UI state and is NOT a CTA gate: a
            // submittable DOB with an empty/blank invite field leaves the create-account CTA enabled.
            val viewModel = vm()
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onDobPicked(dobMillis)
            advanceUntilIdle()
            assertEquals("", viewModel.uiState.value.inviteCode, "the field starts empty")
            assertTrue(viewModel.uiState.value.ctaEnabled, "an empty invite code does not disable the CTA")
            viewModel.onInviteCodeChange("   ")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.ctaEnabled, "a blank invite code still does not disable the CTA")
        }

    @Test
    fun enteredInviteCode_isHeldInUiState_andForwardedToSignUp() =
        runTest(dispatcher) {
            // mobile-referral — the typed code is held in UI state (survives the resubmit) and forwarded to
            // signUpWithGoogle as the optional invite code (trim+omit-when-blank happens in the API client).
            val fake = FakeAuthFlow(signUpOutcome = SignUpOutcome.Success)
            val viewModel = vm(authFlow = fake)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onInviteCodeChange("a3f7k2mq")
            viewModel.onDobPicked(dobMillis)
            advanceUntilIdle()
            assertEquals("a3f7k2mq", viewModel.uiState.value.inviteCode)
            viewModel.onCreateAccountClick()
            advanceUntilIdle()
            assertEquals("a3f7k2mq", fake.lastInviteCode, "the entered code reaches signUpWithGoogle")
        }

    @Test
    fun doubleTap_launchesExactlyOneSignup() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val fake = FakeAuthFlow(signUpOutcome = SignUpOutcome.Success, gate = gate)
            val viewModel = vm(authFlow = fake)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onDobPicked(dobMillis)
            advanceUntilIdle()
            viewModel.onCreateAccountClick() // suspends at the gate — inFlight = true
            advanceUntilIdle()
            assertEquals(AgeGateCtaLabel.LOADING, viewModel.uiState.value.ctaLabel)
            assertFalse(viewModel.uiState.value.ctaEnabled)
            viewModel.onCreateAccountClick() // guarded — no second signup
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, fake.signUpInvocationCount)
        }
}
