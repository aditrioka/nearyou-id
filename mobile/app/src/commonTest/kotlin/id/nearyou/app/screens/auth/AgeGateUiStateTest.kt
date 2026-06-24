package id.nearyou.app.screens.auth

import id.nearyou.app.auth.SignUpOutcome
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure coverage of the age-gate DOB validation ([isDobSubmittable] / [dobFromUtcMillis], design D1)
 * and the [ageGateUiState] projection (design D8) — the logic `AgeGateScreen` renders, exercised
 * without a Compose UI runner (mirroring `SignInUiStateTest`).
 *
 * PII-absence (spec § "No signup UI renders Google email or displayName") is structural here:
 * [AgeGateBanner] is an enum of static message keys, so a banner can never carry the Google
 * `email`/`displayName`. The render-level assertion lives in `AgeGateScreenTest`.
 */
class AgeGateUiStateTest {
    private val today = LocalDate(2026, 5, 31)

    // 7.3 — under-18 date is submittable (the client does NOT apply an 18+ gate).
    @Test
    fun `under-18 DOB is submittable`() {
        val under18 = today.minus(15, DateTimeUnit.YEAR) // age 15
        assertTrue(isDobSubmittable(under18, today))
    }

    // 7.3 — BOTH exactly-18 and one-day-under are client-submittable: the client never decides the
    // 18+ boundary (it delegates to the server), so a regression cannot re-introduce a local gate.
    @Test
    fun `exactly-18 and one-day-under DOBs are both submittable`() {
        val exactly18 = today.minus(18, DateTimeUnit.YEAR) // 2008-05-31
        val oneDayUnder18 = today.minus(18, DateTimeUnit.YEAR).plus(1, DateTimeUnit.DAY) // 2008-06-01
        assertTrue(isDobSubmittable(exactly18, today), "exactly-18 must be submittable")
        assertTrue(isDobSubmittable(oneDayUnder18, today), "one-day-under-18 must ALSO be submittable")
    }

    // 7.3 — a future date is rejected by format/sanity validation; null (nothing picked) is not submittable.
    @Test
    fun `future date and null are not submittable`() {
        val future = today.plus(1, DateTimeUnit.DAY)
        assertFalse(isDobSubmittable(future, today), "a future DOB is not submittable")
        assertFalse(isDobSubmittable(null, today), "no DOB picked ⇒ not submittable")
        // Today itself (age 0) is NOT in the future ⇒ submittable (the server is the age authority).
        assertTrue(isDobSubmittable(today, today))
    }

    @Test
    fun `dobFromUtcMillis reads the picker's UTC-midnight selection as that calendar date`() {
        val date = LocalDate(1995, 3, 14)
        val utcMidnightMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        assertEquals(date, dobFromUtcMillis(utcMidnightMillis))
    }

    // ----- ageGateUiState projection (exhaustive over SignUpOutcome) -----

    @Test
    fun `initial state with a submittable DOB enables the Create CTA no banner`() {
        val state = ageGateUiState(outcome = null, dobSubmittable = true, inFlight = false)
        assertEquals(AgeGateCtaLabel.CREATE, state.ctaLabel)
        assertTrue(state.ctaEnabled)
        assertNull(state.banner)
    }

    @Test
    fun `no DOB selected disables the CTA`() {
        val state = ageGateUiState(outcome = null, dobSubmittable = false, inFlight = false)
        assertFalse(state.ctaEnabled)
        assertNull(state.banner)
    }

    @Test
    fun `in-flight shows the loading label disabled no banner`() {
        val state = ageGateUiState(outcome = null, dobSubmittable = true, inFlight = true)
        assertEquals(AgeGateCtaLabel.LOADING, state.ctaLabel)
        assertFalse(state.ctaEnabled)
        assertNull(state.banner)
    }

    @Test
    fun `in-flight wins even if a prior outcome is set`() {
        val state = ageGateUiState(outcome = SignUpOutcome.RetryableError, dobSubmittable = true, inFlight = true)
        assertEquals(AgeGateCtaLabel.LOADING, state.ctaLabel)
        assertFalse(state.ctaEnabled)
    }

    @Test
    fun `Blocked shows the blocked banner Create label CTA still enabled no hard-block`() {
        val state = ageGateUiState(SignUpOutcome.Blocked, dobSubmittable = true, inFlight = false)
        assertEquals(AgeGateCtaLabel.CREATE, state.ctaLabel)
        assertTrue(state.ctaEnabled, "the client must not hard-block — the user may re-submit")
        assertEquals(AgeGateBanner.BLOCKED, state.banner)
    }

    @Test
    fun `AccountExists shows the account-exists banner`() {
        val state = ageGateUiState(SignUpOutcome.AccountExists, dobSubmittable = true, inFlight = false)
        assertEquals(AgeGateBanner.ACCOUNT_EXISTS, state.banner)
    }

    @Test
    fun `InvalidIdToken shows the token-invalid banner Create label`() {
        val state = ageGateUiState(SignUpOutcome.InvalidIdToken, dobSubmittable = true, inFlight = false)
        assertEquals(AgeGateCtaLabel.CREATE, state.ctaLabel)
        assertEquals(AgeGateBanner.TOKEN_INVALID, state.banner)
    }

    @Test
    fun `RetryableError shows the retry label and network banner`() {
        val state = ageGateUiState(SignUpOutcome.RetryableError, dobSubmittable = true, inFlight = false)
        assertEquals(AgeGateCtaLabel.RETRY, state.ctaLabel)
        assertEquals(AgeGateBanner.NETWORK, state.banner)
    }

    @Test
    fun `Success and Cancelled have no banner navigation or no-op handles them`() {
        assertNull(ageGateUiState(SignUpOutcome.Success, dobSubmittable = true, inFlight = false).banner)
        assertNull(ageGateUiState(SignUpOutcome.Cancelled, dobSubmittable = true, inFlight = false).banner)
    }

    @Test
    fun `RateLimited shows the retry label and the rate-limited banner`() {
        // auth-endpoint-rate-limits: a 429 is a defined retryable state with its own banner — not NETWORK.
        val state = ageGateUiState(SignUpOutcome.RateLimited(retryAfterSeconds = 60L), dobSubmittable = true, inFlight = false)
        assertEquals(AgeGateCtaLabel.RETRY, state.ctaLabel)
        assertEquals(AgeGateBanner.RATE_LIMITED, state.banner)
    }
}
