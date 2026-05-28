package id.nearyou.app.auth

/**
 * Test-only [GoogleSignInGateway] implementation. NOT an `expect`/`actual` — a plain
 * commonTest fake that returns a pre-programmed queue of [GoogleSignInResult]s so
 * `AuthRepository` tests (§6) can drive the auto-retry path (first 401 → re-invoke ceremony)
 * without a real Credential Manager / GoogleSignIn SDK.
 *
 * Per `tasks.md` 4.5: commonTest cannot exercise the real platform ceremonies — the Android
 * Credential Manager and the iOS GoogleSignIn SDK both require a live platform context. The
 * platform actuals are exercised by the §10 device smoke. This fake covers the
 * sealed-result-contract mapping that `AuthRepository` depends on.
 */
class FakeGoogleSignInGateway(
    private vararg val results: GoogleSignInResult,
) : GoogleSignInGateway {
    var invocationCount: Int = 0
        private set

    override suspend fun signIn(): GoogleSignInResult {
        val result = results.getOrElse(invocationCount) { results.last() }
        invocationCount++
        return result
    }
}
