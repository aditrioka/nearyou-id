package id.nearyou.app.auth

import id.nearyou.app.infra.amplitude.AnalyticsTracker
import id.nearyou.app.infra.amplitude.NoOpAnalyticsTracker
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SIGNUP_CREATED = """{"access_token":"at-1","refresh_token":"rt-1","expires_in":900}"""
private val JSON_HEADERS = headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")

// The ACTUAL flat backend body for /signup 403 (no machine-readable `code` — that absence IS the
// privacy guarantee). Verified against backend SignupRoutes.kt SIGNUP_USER_BLOCKED_BODY.
private const val SIGNUP_403_FLAT = """{"error":"user_blocked","message":"Akun tidak dapat dibuat dengan data ini."}"""

private val DOB = LocalDate(1995, 3, 14)

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/**
 * Behavioral coverage of `AuthRepository.signUpWithGoogle` (Mobile #4, tasks 7.4–7.9 + 7.13–7.15).
 * Uses the real repository wired to a `FakeGoogleSignInGateway` + an `AuthApiClient` over a
 * `MockEngine`. The mapping is STATUS-driven (design D2/D8): the keystone test feeds the actual
 * FLAT `403` body and asserts it maps to `Blocked`, NOT the retryable fallthrough.
 */
class AuthRepositorySignUpTest {
    private fun repository(
        gateway: GoogleSignInGateway,
        tokenStore: InMemoryTokenStore = InMemoryTokenStore(),
        diagnosticLog: (String) -> Unit = {},
        analytics: AnalyticsTracker = NoOpAnalyticsTracker,
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
            analytics = analytics,
        )
    }

    // 7.4 + 7.15 — 201 persists tokens, returns Success, and reuses the carried id_token (the
    // Google ceremony is NOT re-invoked on the happy path — no second account sheet).
    @Test
    fun `valid DOB plus 201 persists tokens returns Success reusing the carried id_token`() =
        runTest {
            val store = InMemoryTokenStore()
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id-fresh", null, null))
            val repo = repository(gateway, store) { respond(SIGNUP_CREATED, HttpStatusCode.Created, JSON_HEADERS) }

            val outcome = repo.signUpWithGoogle("g-id", DOB)

            assertEquals(SignUpOutcome.Success, outcome)
            assertEquals(TokenPair("at-1", "rt-1", 900_000L), store.read())
            assertEquals(0, gateway.invocationCount, "201 path reuses the carried id_token — no ceremony")
        }

    // 7.5 (keystone) — the ACTUAL flat 403 body maps to Blocked on HTTP status, NOT the retryable
    // fallthrough (a code-based parse would yield null and misroute it). No token write, no nav.
    @Test
    fun `flat 403 user_blocked maps to Blocked not retryable with no token write`() =
        runTest {
            val store = InMemoryTokenStore()
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null)), store) {
                    respond(SIGNUP_403_FLAT, HttpStatusCode.Forbidden, JSON_HEADERS)
                }

            assertEquals(SignUpOutcome.Blocked, repo.signUpWithGoogle("g-id", DOB))
            assertNull(store.read(), "no token write on a blocked signup")
        }

    // 7.6 — 409 user_exists → AccountExists (routes to sign-in), no token write.
    @Test
    fun `409 user_exists maps to AccountExists with no token write`() =
        runTest {
            val store = InMemoryTokenStore()
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null)), store) {
                    respond("""{"error":{"code":"user_exists"}}""", HttpStatusCode.Conflict, JSON_HEADERS)
                }

            assertEquals(SignUpOutcome.AccountExists, repo.signUpWithGoogle("g-id", DOB))
            assertNull(store.read())
        }

    // 7.7a — first 401 refreshes the Google token ONCE and retries with the FRESH id_token → 201.
    @Test
    fun `first 401 refreshes the token once and retries signup with the fresh id_token`() =
        runTest {
            var signupCalls = 0
            val bodies = mutableListOf<String>()
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id-fresh", null, null))
            val store = InMemoryTokenStore()
            val repo =
                repository(gateway, store) { request ->
                    signupCalls++
                    bodies.add(request.body.bodyText())
                    if (signupCalls == 1) {
                        respond("""{"error":{"code":"invalid_id_token"}}""", HttpStatusCode.Unauthorized, JSON_HEADERS)
                    } else {
                        respond(SIGNUP_CREATED, HttpStatusCode.Created, JSON_HEADERS)
                    }
                }

            val outcome = repo.signUpWithGoogle("g-id", DOB)

            assertEquals(SignUpOutcome.Success, outcome)
            assertEquals(1, gateway.invocationCount, "ceremony re-invoked exactly once on the 401")
            assertEquals(2, signupCalls, "two /signup POSTs (original + retry)")
            assertTrue(bodies[0].contains("g-id") && !bodies[0].contains("g-id-fresh"), "first carried the carried token")
            assertTrue(bodies[1].contains("g-id-fresh"), "retry carried the FRESH token: ${bodies[1]}")
            assertEquals(TokenPair("at-1", "rt-1", 900_000L), store.read())
        }

    // 7.7b — a second consecutive 401 is terminal InvalidIdToken; the ceremony is NOT re-invoked a
    // (third time → second total) — retry budget exhausted at one.
    @Test
    fun `second consecutive 401 is terminal InvalidIdToken with the ceremony invoked once`() =
        runTest {
            var signupCalls = 0
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id-fresh", null, null))
            val store = InMemoryTokenStore()
            val repo =
                repository(gateway, store) {
                    signupCalls++
                    respond("""{"error":{"code":"invalid_id_token"}}""", HttpStatusCode.Unauthorized, JSON_HEADERS)
                }

            assertEquals(SignUpOutcome.InvalidIdToken, repo.signUpWithGoogle("g-id", DOB))
            assertEquals(1, gateway.invocationCount, "exactly one refresh ceremony — never a second")
            assertEquals(2, signupCalls, "original + one retry, then terminal")
            assertNull(store.read())
        }

    // 7.7 (refresh sub-branch) — if the user CANCELS the Google refresh sheet on the 401, the
    // carried token cannot be refreshed → terminal InvalidIdToken; no retry /signup is issued.
    @Test
    fun `401 then a cancelled refresh sheet is terminal InvalidIdToken with no retry signup`() =
        runTest {
            var signupCalls = 0
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.UserCancelled)
            val repo =
                repository(gateway) {
                    signupCalls++
                    respond("""{"error":{"code":"invalid_id_token"}}""", HttpStatusCode.Unauthorized, JSON_HEADERS)
                }

            assertEquals(SignUpOutcome.InvalidIdToken, repo.signUpWithGoogle("g-id", DOB))
            assertEquals(1, gateway.invocationCount, "the refresh ceremony was invoked once")
            assertEquals(1, signupCalls, "no retry /signup after the user cancelled the refresh")
        }

    // 7.7 (refresh sub-branch) — if the Google refresh ceremony FAILS on the 401, it is a
    // connectivity-class problem → RetryableError (mirroring sign-in's Failed→NetworkError), logged.
    @Test
    fun `401 then a failed refresh ceremony maps to RetryableError and logs a diagnostic`() =
        runTest {
            var signupCalls = 0
            val logged = mutableListOf<String>()
            val gateway = FakeGoogleSignInGateway(GoogleSignInResult.Failed("Credential Manager unavailable"))
            val repo =
                repository(gateway, diagnosticLog = { logged.add(it) }) {
                    signupCalls++
                    respond("""{"error":{"code":"invalid_id_token"}}""", HttpStatusCode.Unauthorized, JSON_HEADERS)
                }

            assertEquals(SignUpOutcome.RetryableError, repo.signUpWithGoogle("g-id", DOB))
            assertEquals(1, gateway.invocationCount)
            assertEquals(1, signupCalls, "no retry /signup after the refresh ceremony failed")
            assertTrue(logged.any { it.contains("signup_refresh_ceremony_failed") }, "failed refresh logs a diagnostic: $logged")
        }

    // 7.8 — 503 username_generation_failed (typed nested body) → RetryableError (status-driven), no write.
    @Test
    fun `typed 503 username_generation_failed maps to RetryableError`() =
        runTest {
            val store = InMemoryTokenStore()
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null)), store) {
                    respond(
                        """{"error":{"code":"username_generation_failed","message":"Try again in a moment."}}""",
                        HttpStatusCode.ServiceUnavailable,
                        JSON_HEADERS,
                    )
                }

            assertEquals(SignUpOutcome.RetryableError, repo.signUpWithGoogle("g-id", DOB))
            assertNull(store.read())
        }

    // 7.8 — bare 5xx and transport/IO failure both map to RetryableError.
    @Test
    fun `bare 5xx maps to RetryableError`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    respond("", HttpStatusCode.InternalServerError, JSON_HEADERS)
                }
            assertEquals(SignUpOutcome.RetryableError, repo.signUpWithGoogle("g-id", DOB))
        }

    @Test
    fun `transport failure maps to RetryableError`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    throw RuntimeException("connection refused")
                }
            assertEquals(SignUpOutcome.RetryableError, repo.signUpWithGoogle("g-id", DOB))
        }

    // 7.8 — 400 invalid_request → RetryableError WITH a logged diagnostic (not a silent no-op / crash).
    @Test
    fun `400 invalid_request maps to RetryableError and logs a diagnostic`() =
        runTest {
            val logged = mutableListOf<String>()
            val repo =
                repository(
                    FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null)),
                    diagnosticLog = { logged.add(it) },
                ) {
                    respond(
                        """{"error":{"code":"invalid_request","message":"Malformed signup payload."}}""",
                        HttpStatusCode.BadRequest,
                        JSON_HEADERS,
                    )
                }

            assertEquals(SignUpOutcome.RetryableError, repo.signUpWithGoogle("g-id", DOB))
            assertTrue(logged.any { it.contains("signup_invalid_request") }, "400 must log a diagnostic: $logged")
        }

    // 7.13 — a concurrent double-submit is rejected by the in-flight guard: exactly ONE /signup
    // call and at most ONE token write; the second invocation returns Cancelled.
    @Test
    fun `concurrent double-submit is rejected by the in-flight guard`() =
        runTest {
            var signupCalls = 0
            val store = InMemoryTokenStore()
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null)), store) {
                    signupCalls++
                    delay(1_000) // hold the first call in-flight while the second is attempted
                    respond(SIGNUP_CREATED, HttpStatusCode.Created, JSON_HEADERS)
                }

            val first = async { repo.signUpWithGoogle("g-id", DOB) }
            delay(50) // let the first call acquire the mutex + suspend on the delayed /signup
            val second = repo.signUpWithGoogle("g-id", DOB)

            assertEquals(SignUpOutcome.Cancelled, second, "the concurrent second submit is rejected")
            assertEquals(SignUpOutcome.Success, first.await())
            assertEquals(1, signupCalls, "exactly one /signup call")
            assertEquals(1, store.writeCount, "at most one token write")
        }

    // 7.14 — an interrupted signup (any non-201) leaves NO persisted TokenPair, so on relaunch
    // RootRouterScreen reads null → routes to SignInScreen (no stale token, no stuck splash). The
    // routing half is covered by RootRouterScreenTest.unauthenticated_routesToSignIn; here we pin
    // that the store stays empty ⇒ isAuthenticated() == false.
    @Test
    fun `interrupted signup leaves the store empty so relaunch is unauthenticated`() =
        runTest {
            val store = InMemoryTokenStore()
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null)), store) {
                    respond(SIGNUP_403_FLAT, HttpStatusCode.Forbidden, JSON_HEADERS)
                }

            repo.signUpWithGoogle("g-id", DOB)

            assertNull(store.read())
            assertEquals(false, repo.isAuthenticated())
        }

    // 7.9 — outcome coverage. The per-status tests above each pin one documented result to exactly
    // one SignUpOutcome (201→Success, 403→Blocked, 409→AccountExists, 401→refresh→terminal,
    // 503/5xx/IO/400→RetryableError). The mapping `when` over SignInApiResult + the inner status
    // `when` have NO `else` emitting a generic "signup failed" copy — the only `else` maps to the
    // DEFINED RetryableError (signin_error_network), and the SignUpOutcome sealed type is closed,
    // so exhaustiveness is compiler-enforced. This guard pins the unenumerated-status path.
    @Test
    fun `unenumerated 4xx maps to the defined RetryableError not a generic failure`() =
        runTest {
            val repo =
                repository(FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null))) {
                    respond("""{"error":{"code":"teapot"}}""", HttpStatusCode.fromValue(418), JSON_HEADERS)
                }
            assertEquals(SignUpOutcome.RetryableError, repo.signUpWithGoogle("g-id", DOB))
        }

    // mobile-amplitude-analytics — a successful signup emits exactly one signup_completed carrying the
    // just-issued token's `sub`. The 201 body's access_token is a real (sub-decodable) JWT so
    // decodeJwtSubject yields "u1"; the middle segment is base64url of {"sub":"u1"}.
    @Test
    fun `successful signup emits signup_completed with the token sub`() =
        runTest {
            val events = mutableListOf<Pair<String, String>>()
            val recording =
                object : AnalyticsTracker {
                    override fun track(
                        eventType: String,
                        userId: String,
                        eventProperties: Map<String, JsonElement>,
                    ) {
                        events.add(eventType to userId)
                    }

                    override fun identify(
                        userId: String,
                        userProperties: Map<String, JsonElement>,
                    ) = Unit

                    override fun flush() = Unit
                }
            val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig"
            val body = """{"access_token":"$jwt","refresh_token":"rt-1","expires_in":900}"""
            val repo =
                repository(
                    FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null)),
                    analytics = recording,
                ) { respond(body, HttpStatusCode.Created, JSON_HEADERS) }

            assertEquals(SignUpOutcome.Success, repo.signUpWithGoogle("g-id", DOB))
            assertEquals(listOf("signup_completed" to "u1"), events, "exactly one signup_completed with the token sub")
        }

    // mobile-amplitude-analytics — a non-201 signup emits NO analytics event.
    @Test
    fun `a blocked signup emits no analytics event`() =
        runTest {
            val events = mutableListOf<String>()
            val recording =
                object : AnalyticsTracker {
                    override fun track(
                        eventType: String,
                        userId: String,
                        eventProperties: Map<String, JsonElement>,
                    ) {
                        events.add(eventType)
                    }

                    override fun identify(
                        userId: String,
                        userProperties: Map<String, JsonElement>,
                    ) = Unit

                    override fun flush() = Unit
                }
            val repo =
                repository(
                    FakeGoogleSignInGateway(GoogleSignInResult.Success("g-id", null, null)),
                    analytics = recording,
                ) { respond(SIGNUP_403_FLAT, HttpStatusCode.Forbidden, JSON_HEADERS) }

            repo.signUpWithGoogle("g-id", DOB)
            assertTrue(events.isEmpty(), "no analytics event on a non-201 signup")
        }
}
