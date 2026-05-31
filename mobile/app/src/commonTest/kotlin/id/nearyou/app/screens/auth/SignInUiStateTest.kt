package id.nearyou.app.screens.auth

import id.nearyou.app.auth.SignInOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exhaustive coverage of [signInUiState] — the pure Decision-7 result→UI-state mapping that
 * `SignInScreen` renders. Covers the CTA-label / CTA-enabled / error-banner projection for
 * every outcome (§6.7 c/d-state/e/g) without a Compose UI runner.
 *
 * PII-absence (§6.7 i "No error-state UI renders Google email or displayName") is structural:
 * [SignInErrorBanner] is an enum of static message keys, so an error banner can never carry
 * the Google `email` / `displayName` from `GoogleSignInResult.Success`.
 */
class SignInUiStateTest {
    @Test
    fun `initial state shows the Google CTA, enabled, no banner`() {
        val state = signInUiState(outcome = null, inFlight = false)
        assertEquals(SignInCtaLabel.GOOGLE, state.ctaLabel)
        assertEquals(true, state.ctaEnabled)
        assertNull(state.errorBanner)
    }

    @Test
    fun `in-flight shows the loading label, disabled, no banner`() {
        val state = signInUiState(outcome = null, inFlight = true)
        assertEquals(SignInCtaLabel.LOADING, state.ctaLabel)
        assertEquals(false, state.ctaEnabled)
        assertNull(state.errorBanner)
    }

    @Test
    fun `in-flight wins even if a prior outcome is set`() {
        val state = signInUiState(outcome = SignInOutcome.NetworkError, inFlight = true)
        assertEquals(SignInCtaLabel.LOADING, state.ctaLabel)
        assertEquals(false, state.ctaEnabled)
    }

    @Test
    fun `NoAccount shows no banner (Mobile #4 navigates to AgeGateScreen), Google label, enabled`() {
        // Mobile #4: 404 navigates to AgeGateScreen instead of showing the retired
        // signin_error_no_account banner — like Success, it's a transient navigation trigger.
        val state = signInUiState(SignInOutcome.NoAccount("g-id"), inFlight = false)
        assertEquals(SignInCtaLabel.GOOGLE, state.ctaLabel)
        assertEquals(true, state.ctaEnabled)
        assertNull(state.errorBanner)
    }

    @Test
    fun `Banned shows the banned banner AND disables the CTA`() {
        val state = signInUiState(SignInOutcome.Banned, inFlight = false)
        assertEquals(SignInCtaLabel.GOOGLE, state.ctaLabel)
        assertEquals(false, state.ctaEnabled, "banned CTA must be disabled (tap-rejected)")
        assertEquals(SignInErrorBanner.BANNED, state.errorBanner)
    }

    @Test
    fun `NetworkError shows the retry label and network banner, enabled`() {
        val state = signInUiState(SignInOutcome.NetworkError, inFlight = false)
        assertEquals(SignInCtaLabel.RETRY, state.ctaLabel)
        assertEquals(true, state.ctaEnabled)
        assertEquals(SignInErrorBanner.NETWORK, state.errorBanner)
    }

    @Test
    fun `terminal InvalidIdToken keeps the initial Google label, enabled, with the token banner`() {
        val state = signInUiState(SignInOutcome.InvalidIdToken, inFlight = false)
        assertEquals(SignInCtaLabel.GOOGLE, state.ctaLabel, "CTA returns to initial label after terminal 401")
        assertEquals(true, state.ctaEnabled)
        assertEquals(SignInErrorBanner.TOKEN_INVALID, state.errorBanner)
    }

    @Test
    fun `Cancelled returns to the initial CTA-visible state with no banner`() {
        val state = signInUiState(SignInOutcome.Cancelled, inFlight = false)
        assertEquals(SignInCtaLabel.GOOGLE, state.ctaLabel)
        assertEquals(true, state.ctaEnabled)
        assertNull(state.errorBanner, "cancellation is not an error — no banner")
    }

    @Test
    fun `Success has no banner (navigation handles the transition)`() {
        val state = signInUiState(SignInOutcome.Success, inFlight = false)
        assertNull(state.errorBanner)
    }
}
