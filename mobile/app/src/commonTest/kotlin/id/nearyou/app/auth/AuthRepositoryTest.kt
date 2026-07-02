package id.nearyou.app.auth

import id.nearyou.app.appeal.AppealSession
import id.nearyou.app.diagnostics.FakeCrashReporter
import id.nearyou.app.infra.sentry.CrashReporter
import id.nearyou.app.infra.sentry.NoOpCrashReporter
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
import kotlin.time.Instant

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
        crashReporter: CrashReporter = NoOpCrashReporter,
        appealSession: AppealSession = AppealSession(),
        handler: MockRequestHandler,
    ): AuthRepository {
        val sessionInvalidator = SessionInvalidator(tokenStore)
        val client =
            HttpClientFactory.create(
                installTimeouts = false,
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
            crashReporter = crashReporter,
            appealSession = appealSession,
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

    // auth-endpoint-rate-limits: a 429 maps to RateLimited (carrying the Retry-After hint), NOT the
    // generic NetworkError — and writes no token.
    @Test
    fun `signin 429 maps to RateLimited carrying the Retry-After seconds with no token write`() =
        runTest {
            val store = InMemoryTokenStore()
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", "Test User", "test@example.com")), store) {
                    respond(
                        """{"error":{"code":"rate_limited"}}""",
                        HttpStatusCode.TooManyRequests,
                        headersOf(
                            io.ktor.http.HttpHeaders.RetryAfter to listOf("45"),
                            io.ktor.http.HttpHeaders.ContentType to listOf("application/json"),
                        ),
                    )
                }

            assertEquals(SignInOutcome.RateLimited(45L), repo.signInWithGoogle())
            assertNull(store.read(), "no token write on a rate-limited signin")
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
    fun `404 maps to NoAccount carrying the verified id_token`() =
        runTest {
            // Mobile #4: 404 user_not_found is NOT an error — it carries the verified Google
            // id_token forward so SignInScreen can navigate to AgeGateScreen (no second ceremony).
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    respond("""{"error":{"code":"user_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS)
                }
            assertEquals(SignInOutcome.NoAccount("g-id"), repo.signInWithGoogle())
        }

    // appeal-sign-in-ban-distinction (5.2): a 403 with a null suspended_until ⇒ the PERMANENT
    // sub-state (Banned(null)) → support copy, no appeal entry downstream.
    @Test
    fun `403 with null suspended_until maps to permanent Banned`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    respond(
                        """{"error":{"code":"account_banned"},"suspended_until":null}""",
                        HttpStatusCode.Forbidden,
                        JSON_HEADERS,
                    )
                }
            assertEquals(SignInOutcome.Banned(suspendedUntil = null), repo.signInWithGoogle())
        }

    // appeal-sign-in-ban-distinction (5.1): a 403 with a non-null future suspended_until ⇒ the
    // SUSPENSION sub-state (Banned carrying the parsed expiry) AND the appeal token is captured.
    @Test
    fun `403 with a non-null suspended_until maps to suspension Banned and stashes the appeal token`() =
        runTest {
            val session = AppealSession()
            val repo =
                repository(
                    FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null)),
                    appealSession = session,
                ) {
                    respond(
                        """{"error":{"code":"account_banned"},"appeal_token":"appeal-tok-1","suspended_until":"2026-07-03T00:00:00Z"}""",
                        HttpStatusCode.Forbidden,
                        JSON_HEADERS,
                    )
                }
            assertEquals(SignInOutcome.Banned(Instant.parse("2026-07-03T00:00:00Z")), repo.signInWithGoogle())
            assertEquals("appeal-tok-1", session.peek(), "the limited appeal token is stashed for the appeal screen")
        }

    @Test
    fun `403 with an appeal_token but no suspended_until stashes the token and maps to permanent Banned`() =
        runTest {
            // content-moderation-appeal: the banned sign-in 403 carries the limited appeal token; the
            // repository stashes it (capture is sub-state-independent) and, with the field absent,
            // safe-degrades to the PERMANENT sub-state (appeal-sign-in-ban-distinction).
            val session = AppealSession()
            val repo =
                repository(
                    FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null)),
                    appealSession = session,
                ) {
                    respond(
                        """{"error":{"code":"account_banned"},"appeal_token":"appeal-tok-1"}""",
                        HttpStatusCode.Forbidden,
                        JSON_HEADERS,
                    )
                }
            assertEquals(SignInOutcome.Banned(suspendedUntil = null), repo.signInWithGoogle())
            assertEquals("appeal-tok-1", session.peek(), "the limited appeal token is stashed for the appeal screen")
        }

    // appeal-sign-in-ban-distinction (5.5a): a non-null PAST suspended_until still parses to a
    // non-null Instant ⇒ the suspension sub-state (null-ness, not future-ness, is the discriminator).
    @Test
    fun `403 with a past suspended_until still maps to suspension Banned`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    respond(
                        """{"error":{"code":"account_banned"},"suspended_until":"2020-01-01T00:00:00Z"}""",
                        HttpStatusCode.Forbidden,
                        JSON_HEADERS,
                    )
                }
            assertEquals(SignInOutcome.Banned(Instant.parse("2020-01-01T00:00:00Z")), repo.signInWithGoogle())
        }

    // appeal-sign-in-ban-distinction (5.5b): an UNPARSEABLE suspended_until safe-degrades to the
    // permanent sub-state (Banned(null)) — never throws, never misroutes to suspension.
    @Test
    fun `403 with an unparseable suspended_until safe-degrades to permanent Banned`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    respond(
                        """{"error":{"code":"account_banned"},"suspended_until":"not-a-timestamp"}""",
                        HttpStatusCode.Forbidden,
                        JSON_HEADERS,
                    )
                }
            assertEquals(SignInOutcome.Banned(suspendedUntil = null), repo.signInWithGoogle())
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
    // The gate is PRESENCE-only (`read() != null`) — it does NOT compare
    // `accessExpiresAtEpochMillis`. These tests assert exactly that: a persisted TokenPair is
    // "authenticated" regardless of its access-token freshness (the Ktor Auth plugin handles
    // staleness lazily). They are NOT boundary-comparison tests (there is no comparison).

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
    fun `isAuthenticated is true for a persisted TokenPair with a still-fresh access token`() =
        runTest {
            // Fresh access (expiry far in the future) — present ⇒ authenticated.
            val store = InMemoryTokenStore(TokenPair("at", "rt", accessExpiresAtEpochMillis = Long.MAX_VALUE))
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.UserCancelled), store) {
                    respond("", HttpStatusCode.OK, JSON_HEADERS)
                }
            assertEquals(true, repo.isAuthenticated())
        }

    @Test
    fun `isAuthenticated is true even when the access token is already expired presence-only gate`() =
        runTest {
            // Access expired (epoch 0) but a TokenPair exists ⇒ still authenticated → routes to
            // Home; the Auth plugin refreshes lazily on the first authenticated call. This is the
            // key proof that the gate ignores the expiry value, not a boundary comparison.
            val store = InMemoryTokenStore(TokenPair("at", "rt", accessExpiresAtEpochMillis = 0L))
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.UserCancelled), store) {
                    respond("", HttpStatusCode.OK, JSON_HEADERS)
                }
            assertEquals(true, repo.isAuthenticated())
        }

    // Renamed from `handleTerminal401 …` — that AuthFlow member was production-dead and
    // removed (2026-06-10 audit, 05-#8); SessionInvalidator.invalidate IS the funnel.
    @Test
    fun `sessionInvalidator invalidate clears the store and emits a session-expired signal`() =
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

    // ----- mobile-crash-reporting: opaque-sub user correlation -----

    @Test
    fun `successful sign-in sets the crash-reporter user to the access-token sub`() =
        runTest {
            // Access token whose base64url payload is {"sub":"user-123"} — never username/email.
            val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEyMyJ9.sig"
            val crash = FakeCrashReporter()
            val repo =
                repository(
                    FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", "Test User", "test@example.com")),
                    crashReporter = crash,
                ) { respond("""{"access_token":"$jwt","refresh_token":"rt-1","expires_in":900}""", HttpStatusCode.OK, JSON_HEADERS) }

            assertEquals(SignInOutcome.Success, repo.signInWithGoogle())
            assertEquals("user-123", crash.lastUserId)
        }

    @Test
    fun `session invalidation clears the crash-reporter user`() =
        runTest {
            val crash = FakeCrashReporter()
            val store = InMemoryTokenStore(TokenPair("at", "rt", 1L))
            SessionInvalidator(store, crashReporter = crash).invalidate()
            assertEquals(1, crash.clearUserCount)
        }
}
