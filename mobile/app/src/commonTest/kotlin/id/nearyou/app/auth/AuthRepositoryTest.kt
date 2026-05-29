package id.nearyou.app.auth

import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SIGNIN_OK = """{"access_token":"at-1","refresh_token":"rt-1","expires_in":900}"""
private val JSON_HEADERS = headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")

/**
 * Behavioral coverage of `AuthRepository` — the orchestration heart of the SignInScreen
 * scenarios (§6.7 b/f/g/h/j) + the `isAuthenticated` routing gate (§6.8). Uses the real
 * repository wired to a `FakeGoogleSignInGateway` + an `AuthApiClient` over a `MockEngine`,
 * so no Compose UI runner is needed (see IMPLEMENTATION_NOTES.md § Compose UI test runner gap).
 */
class AuthRepositoryTest {
    private fun repository(
        gateway: GoogleSignInGateway,
        tokenStore: InMemoryTokenStore = InMemoryTokenStore(),
        diagnosticLog: (String) -> Unit = {},
        handler: MockRequestHandler,
    ): AuthRepository {
        val sessionInvalidator = SessionInvalidator(tokenStore)
        val client =
            HttpClientFactory.create(
                apiBaseUrl = "http://test.local",
                tokenStore = tokenStore,
                sessionInvalidator = sessionInvalidator,
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return AuthRepository(
            googleSignIn = gateway,
            authApiClient = AuthApiClient(client) { 0L },
            tokenStore = tokenStore,
            sessionInvalidator = sessionInvalidator,
            diagnosticLog = diagnosticLog,
        )
    }

    @Test
    fun `successful ceremony plus 200 persists tokens and returns Success`() =
        runTest {
            val store = InMemoryTokenStore()
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", "Test User", "test@example.com")), store) {
                    respond(SIGNIN_OK, HttpStatusCode.OK, JSON_HEADERS)
                }

            val outcome = repo.signInWithGoogle()

            assertEquals(SignInOutcome.Success, outcome)
            assertEquals(TokenPair("at-1", "rt-1", 900_000L), store.read())
        }

    @Test
    fun `user cancellation maps to Cancelled with no token write`() =
        runTest {
            val store = InMemoryTokenStore()
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.UserCancelled), store) {
                    respond(SIGNIN_OK, HttpStatusCode.OK, JSON_HEADERS)
                }

            assertEquals(SignInOutcome.Cancelled, repo.signInWithGoogle())
            assertNull(store.read())
        }

    @Test
    fun `GoogleSignInResult Failed maps to NetworkError and is sent to the diagnostic log`() =
        runTest {
            val logged = mutableListOf<String>()
            val repo =
                repository(
                    FakeGoogleSignInGateway(GoogleSignInResult.Failed("Credential Manager unavailable")),
                    diagnosticLog = { logged.add(it) },
                ) { respond(SIGNIN_OK, HttpStatusCode.OK, JSON_HEADERS) }

            assertEquals(SignInOutcome.NetworkError, repo.signInWithGoogle())
            assertTrue(logged.any { it.contains("Credential Manager unavailable") }, "diagnostic message logged: $logged")
        }

    @Test
    fun `404 maps to NoAccount`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    respond("""{"error":{"code":"user_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS)
                }
            assertEquals(SignInOutcome.NoAccount, repo.signInWithGoogle())
        }

    @Test
    fun `403 maps to Banned`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    respond("""{"error":{"code":"account_banned"}}""", HttpStatusCode.Forbidden, JSON_HEADERS)
                }
            assertEquals(SignInOutcome.Banned, repo.signInWithGoogle())
        }

    @Test
    fun `401 invalid_id_token auto-retries the ceremony once then succeeds`() =
        runTest {
            var signinCalls = 0
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))
            val store = InMemoryTokenStore()
            val repo =
                repository(gateway, store) {
                    signinCalls++
                    if (signinCalls == 1) {
                        respond("""{"error":{"code":"invalid_id_token"}}""", HttpStatusCode.Unauthorized, JSON_HEADERS)
                    } else {
                        respond(SIGNIN_OK, HttpStatusCode.OK, JSON_HEADERS)
                    }
                }

            val outcome = repo.signInWithGoogle()

            assertEquals(SignInOutcome.Success, outcome)
            assertEquals(2, gateway.invocationCount, "ceremony re-invoked exactly once on the 401")
            assertEquals(2, signinCalls, "two /signin POSTs (original + retry)")
            assertNotNull(store.read())
        }

    @Test
    fun `401 invalid_id_token twice is terminal InvalidIdToken with retry budget exhausted at one`() =
        runTest {
            var signinCalls = 0
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))
            val repo =
                repository(gateway) {
                    signinCalls++
                    respond("""{"error":{"code":"invalid_id_token"}}""", HttpStatusCode.Unauthorized, JSON_HEADERS)
                }

            val outcome = repo.signInWithGoogle()

            assertEquals(SignInOutcome.InvalidIdToken, outcome)
            assertEquals(2, gateway.invocationCount, "ceremony NOT invoked a third time")
            assertEquals(2, signinCalls)
        }

    @Test
    fun `auto-retry budget is per-call so a fresh signInWithGoogle gets a fresh retry`() =
        runTest {
            var signinCalls = 0
            // 401 on calls 1+2 (first flow exhausts), 401 on 3, 200 on 4 (second flow retries to success).
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))
            val store = InMemoryTokenStore()
            val repo =
                repository(gateway, store) {
                    signinCalls++
                    if (signinCalls <= 3) {
                        respond("""{"error":{"code":"invalid_id_token"}}""", HttpStatusCode.Unauthorized, JSON_HEADERS)
                    } else {
                        respond(SIGNIN_OK, HttpStatusCode.OK, JSON_HEADERS)
                    }
                }

            assertEquals(SignInOutcome.InvalidIdToken, repo.signInWithGoogle())
            // Second call gets a fresh retry budget: 401 (call 3) → retry → 200 (call 4) → Success.
            assertEquals(SignInOutcome.Success, repo.signInWithGoogle())
            assertNotNull(store.read())
        }

    @Test
    fun `5xx maps to NetworkError`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    respond("", HttpStatusCode.InternalServerError, JSON_HEADERS)
                }
            assertEquals(SignInOutcome.NetworkError, repo.signInWithGoogle())
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    throw RuntimeException("connection refused")
                }
            assertEquals(SignInOutcome.NetworkError, repo.signInWithGoogle())
        }

    @Test
    fun `concurrent double-invocation is rejected by the in-flight guard`() =
        runTest {
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))
            val store = InMemoryTokenStore()
            val repo =
                repository(gateway, store) {
                    delay(1_000) // hold the first call in-flight while the second is attempted
                    respond(SIGNIN_OK, HttpStatusCode.OK, JSON_HEADERS)
                }

            val first = async { repo.signInWithGoogle() }
            // Let the first call acquire the mutex + suspend on the delayed /signin.
            delay(50)
            val second = repo.signInWithGoogle()

            assertEquals(SignInOutcome.Cancelled, second, "the concurrent second call is rejected")
            assertEquals(SignInOutcome.Success, first.await())
            // The rejected call never re-ran the ceremony — only the first flow's single invocation.
            assertEquals(1, gateway.invocationCount)
        }

    // ----- isAuthenticated (§6.8 routing gate) -----

    @Test
    fun `isAuthenticated is false when the store is empty`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.UserCancelled), InMemoryTokenStore()) {
                    respond("", HttpStatusCode.OK, JSON_HEADERS)
                }
            assertEquals(false, repo.isAuthenticated())
        }

    @Test
    fun `isAuthenticated is true for a strictly-future access expiry`() =
        runTest {
            val store = InMemoryTokenStore(TokenPair("at", "rt", accessExpiresAtEpochMillis = 1L))
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.UserCancelled), store) {
                    respond("", HttpStatusCode.OK, JSON_HEADERS)
                }
            assertEquals(true, repo.isAuthenticated())
        }

    @Test
    fun `isAuthenticated is true for an already-expired access token when a refresh token is present`() =
        runTest {
            // Access expired (epoch 0) but a TokenPair exists → routes to Home; the Auth plugin
            // refreshes lazily on the first authenticated call.
            val store = InMemoryTokenStore(TokenPair("at", "rt", accessExpiresAtEpochMillis = 0L))
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.UserCancelled), store) {
                    respond("", HttpStatusCode.OK, JSON_HEADERS)
                }
            assertEquals(true, repo.isAuthenticated())
        }

    @Test
    fun `handleTerminal401 clears the store and emits a session-expired signal`() =
        runTest {
            val store = InMemoryTokenStore(TokenPair("at", "rt", 1L))
            val invalidator = SessionInvalidator(store)
            var signalled = false
            val collector = launch { invalidator.sessionExpired.collect { signalled = true } }
            // SessionInvalidator's SharedFlow has replay=0; let the collector subscribe before
            // emitting (in production App.kt subscribes at launch, well before any expiry).
            delay(10)

            invalidator.invalidate()
            delay(10)

            assertNull(store.read())
            assertTrue(signalled)
            collector.cancel()
        }
}
