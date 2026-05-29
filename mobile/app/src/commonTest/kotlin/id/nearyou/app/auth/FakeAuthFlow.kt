package id.nearyou.app.auth

/**
 * Test-only [AuthFlow] for screen tests. Returns a pre-programmed [SignInOutcome] from
 * `signInWithGoogle()` and tracks the invocation count so the Banned-CTA-tap-rejection
 * scenario (§6.7d) can assert the ceremony is NOT entered. `isAuthenticated()` returns a
 * fixed value for `RootRouterScreen` routing tests (§6.8).
 */
class FakeAuthFlow(
    private val outcome: SignInOutcome = SignInOutcome.Success,
    private val authenticated: Boolean = false,
) : AuthFlow {
    var signInInvocationCount: Int = 0
        private set

    override suspend fun signInWithGoogle(): SignInOutcome {
        signInInvocationCount++
        return outcome
    }

    override suspend fun isAuthenticated(): Boolean = authenticated

    override suspend fun handleTerminal401() = Unit
}
