package id.nearyou.app.screens.consent

import id.nearyou.app.consent.ConsentOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure coverage of [consentUiState] — exhaustive over [ConsentOutcome] × in-flight (no fallthrough). */
class ConsentUiStateTest {
    @Test
    fun initial_isContinueEnabledNoBannerNoSkip() {
        val s = consentUiState(outcome = null, inFlight = false)
        assertEquals(ConsentCtaLabel.CONTINUE, s.ctaLabel)
        assertTrue(s.ctaEnabled)
        assertNull(s.banner)
        assertFalse(s.showSkip, "happy path before any failure shows NO skip")
    }

    @Test
    fun inFlight_isLoadingDisabledNoBannerNoSkip() {
        val s = consentUiState(outcome = null, inFlight = true)
        assertEquals(ConsentCtaLabel.LOADING, s.ctaLabel)
        assertFalse(s.ctaEnabled)
        assertNull(s.banner)
        assertFalse(s.showSkip)
    }

    @Test
    fun retryable_isRetryBannerRetryableAndShowsSkip() {
        val s = consentUiState(ConsentOutcome.RetryableError, inFlight = false)
        assertEquals(ConsentCtaLabel.RETRY, s.ctaLabel)
        assertEquals(ConsentBanner.RETRYABLE, s.banner)
        assertTrue(s.ctaEnabled)
        assertTrue(s.showSkip, "skip appears ONLY after a retryable failure")
    }

    @Test
    fun tokenInvalid_isContinueBannerTokenInvalidNoSkip() {
        val s = consentUiState(ConsentOutcome.TokenInvalid, inFlight = false)
        assertEquals(ConsentCtaLabel.CONTINUE, s.ctaLabel)
        assertEquals(ConsentBanner.TOKEN_INVALID, s.banner)
        assertFalse(s.showSkip)
    }

    @Test
    fun successAndCancelledAndNull_haveNoBannerNoSkip() {
        for (outcome in listOf(ConsentOutcome.Success, ConsentOutcome.Cancelled, null)) {
            val s = consentUiState(outcome, inFlight = false)
            assertEquals(ConsentCtaLabel.CONTINUE, s.ctaLabel, "outcome $outcome")
            assertNull(s.banner, "outcome $outcome")
            assertFalse(s.showSkip, "outcome $outcome")
        }
    }
}
