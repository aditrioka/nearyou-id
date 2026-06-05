package id.nearyou.app.screens.auth

import id.nearyou.app.auth.SignUpOutcome
import id.nearyou.app.screens.routing.PendingSignupIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage of [handleAgeGateTerminalOutcome] — the pure terminal-exit decision `AgeGateScreen`
 * delegates to (`mobile-age-gate` § "The pending identity is cleared on every terminal exit but
 * survives a retryable error" + § "Account-exists collision routes to sign-in" + design Decision 4).
 * Verifies the holder is cleared on the terminal outcomes (Success / AccountExists) and the matching
 * navigation fires, while a retryable error (and every other non-terminal outcome) leaves the holder
 * intact and navigates nowhere — deterministically, without driving the Material 3 DOB picker.
 */
class AgeGateOutcomeHandlerTest {
    private class NavRecorder {
        var signedUp = 0
        var exitToSignIn = 0
    }

    private fun run(
        outcome: SignUpOutcome?,
        seed: String? = "g-id",
    ): Pair<PendingSignupIdentity, NavRecorder> {
        val holder = PendingSignupIdentity().apply { if (seed != null) set(seed) }
        val rec = NavRecorder()
        handleAgeGateTerminalOutcome(
            outcome = outcome,
            pendingSignupIdentity = holder,
            onSignedUp = { rec.signedUp++ },
            onExitToSignIn = { rec.exitToSignIn++ },
        )
        return holder to rec
    }

    @Test
    fun success_clearsHolder_andRoutesToHome() {
        val (holder, rec) = run(SignUpOutcome.Success)
        assertNull(holder.peek(), "Success is terminal — the holder must be cleared")
        assertEquals(1, rec.signedUp, "Success routes to Home (onSignedUp)")
        assertEquals(0, rec.exitToSignIn)
    }

    @Test
    fun accountExists_clearsHolder_andRoutesToSignIn() {
        val (holder, rec) = run(SignUpOutcome.AccountExists)
        assertNull(holder.peek(), "AccountExists is terminal — the holder must be cleared")
        assertEquals(1, rec.exitToSignIn, "409 user_exists routes back to SignIn (onExitToSignIn)")
        assertEquals(0, rec.signedUp)
    }

    @Test
    fun retryableError_keepsHolder_andDoesNotNavigate() {
        val (holder, rec) = run(SignUpOutcome.RetryableError)
        assertEquals("g-id", holder.peek(), "a retryable error must NOT clear the holder (resubmit re-reads it)")
        assertEquals(0, rec.signedUp)
        assertEquals(0, rec.exitToSignIn)
    }

    @Test
    fun nonTerminalOutcomes_keepHolder_andDoNotNavigate() {
        // Blocked / InvalidIdToken / Cancelled keep the user on-screen; null = fresh/never-submitted.
        for (outcome in listOf(SignUpOutcome.Blocked, SignUpOutcome.InvalidIdToken, SignUpOutcome.Cancelled, null)) {
            val (holder, rec) = run(outcome)
            assertEquals("g-id", holder.peek(), "$outcome must NOT clear the holder")
            assertEquals(0, rec.signedUp, "$outcome must not route to Home")
            assertEquals(0, rec.exitToSignIn, "$outcome must not route to SignIn")
        }
    }
}
