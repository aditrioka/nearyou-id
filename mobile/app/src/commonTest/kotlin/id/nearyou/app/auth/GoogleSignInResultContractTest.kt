package id.nearyou.app.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies the [GoogleSignInResult] sealed contract + the [GoogleSignInGateway] fake's
 * queue semantics (the substitution point `AuthRepository` consumes). The real platform
 * ceremonies are NOT exercised here (see [FakeGoogleSignInGateway] KDoc + `tasks.md` 4.5).
 *
 * Maps the spec scenario "User-cancellation produces UserCancelled, not Failed".
 */
class GoogleSignInResultContractTest {
    @Test
    fun `UserCancelled is the singleton data object distinct from Failed`() =
        runTest {
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.UserCancelled)
            val result = gateway.signIn()

            // data-object identity — equality to the singleton confirms it is NOT a
            // Failed(...) instance (cancellation must not be modeled as an error).
            assertEquals(GoogleSignInResult.UserCancelled, result)
        }

    @Test
    fun `Success carries idToken plus optional displayName and email`() =
        runTest {
            val gateway =
                FakeGoogleSignInGateway(
                    GoogleSignInResult.Success(
                        idToken = "g-id-token",
                        displayName = "Test User",
                        email = "test@example.com",
                    ),
                )
            val result = gateway.signIn()

            assertIs<GoogleSignInResult.Success>(result)
            assertEquals("g-id-token", result.idToken)
            assertEquals("Test User", result.displayName)
            assertEquals("test@example.com", result.email)
        }

    @Test
    fun `Success tolerates null displayName and email`() =
        runTest {
            val gateway =
                FakeGoogleSignInGateway(
                    GoogleSignInResult.Success(idToken = "g-id-token", displayName = null, email = null),
                )
            val result = gateway.signIn()

            assertIs<GoogleSignInResult.Success>(result)
            assertEquals(null, result.displayName)
            assertEquals(null, result.email)
        }

    @Test
    fun `Failed carries the diagnostic message`() =
        runTest {
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.Failed("Credential Manager unavailable"))
            val result = gateway.signIn()

            assertIs<GoogleSignInResult.Failed>(result)
            assertEquals("Credential Manager unavailable", result.message)
        }

    @Test
    fun `fake gateway advances through its queued results on successive calls`() =
        runTest {
            val gateway =
                FakeGoogleSignInGateway(
                    GoogleSignInResult.Success("first", null, null),
                    GoogleSignInResult.UserCancelled,
                )

            val first = gateway.signIn()
            val second = gateway.signIn()
            // Queue exhausted ⇒ repeats the last entry (so a third call is deterministic).
            val third = gateway.signIn()

            assertIs<GoogleSignInResult.Success>(first)
            assertIs<GoogleSignInResult.UserCancelled>(second)
            assertIs<GoogleSignInResult.UserCancelled>(third)
            assertEquals(3, gateway.invocationCount)
        }
}
