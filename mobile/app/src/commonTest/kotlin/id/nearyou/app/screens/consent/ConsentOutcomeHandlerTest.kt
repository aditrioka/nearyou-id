package id.nearyou.app.screens.consent

import id.nearyou.app.consent.ConsentOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure coverage of the [handleConsentTerminalOutcome] seam: only [ConsentOutcome.Success] navigates
 * (→ Home); every other outcome (incl. null) is a no-op so the user stays on the screen (retry / read
 * the banner / take the explicit post-failure skip).
 */
class ConsentOutcomeHandlerTest {
    @Test
    fun success_invokesOnDone() {
        var done = 0
        handleConsentTerminalOutcome(ConsentOutcome.Success) { done++ }
        assertEquals(1, done)
    }

    @Test
    fun nonSuccessOutcomes_doNotInvokeOnDone() {
        for (outcome in listOf(ConsentOutcome.RetryableError, ConsentOutcome.TokenInvalid, ConsentOutcome.Cancelled, null)) {
            var done = 0
            handleConsentTerminalOutcome(outcome) { done++ }
            assertEquals(0, done, "outcome $outcome must NOT auto-navigate")
        }
    }
}
