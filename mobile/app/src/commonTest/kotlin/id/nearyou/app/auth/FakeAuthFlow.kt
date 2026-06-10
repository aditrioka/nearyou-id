package id.nearyou.app.auth

import kotlinx.datetime.LocalDate

/**
 * Test-only [AuthFlow] for screen tests. Returns a pre-programmed [SignInOutcome] /
 * [SignUpOutcome] and tracks invocation counts so screen tests can assert (non-)entry: the
 * Banned-CTA-tap-rejection scenario asserts the sign-in ceremony is NOT entered, and the Mobile #4
 * age-gate tests assert `signUpWithGoogle` is NOT auto-fired on screen entry (spec § "Entering
 * AgeGateScreen does not re-invoke the Google ceremony"). `isAuthenticated()` returns a fixed
 * value for `RootRouterScreen` routing tests.
 */
class FakeAuthFlow(
    private val outcome: SignInOutcome = SignInOutcome.Success,
    private val signUpOutcome: SignUpOutcome = SignUpOutcome.Success,
    private val authenticated: Boolean = false,
) : AuthFlow {
    var signInInvocationCount: Int = 0
        private set

    var signUpInvocationCount: Int = 0
        private set

    override suspend fun signInWithGoogle(): SignInOutcome {
        signInInvocationCount++
        return outcome
    }

    override suspend fun signUpWithGoogle(
        idToken: String,
        dateOfBirth: LocalDate,
    ): SignUpOutcome {
        signUpInvocationCount++
        return signUpOutcome
    }

    override suspend fun isAuthenticated(): Boolean = authenticated
}
