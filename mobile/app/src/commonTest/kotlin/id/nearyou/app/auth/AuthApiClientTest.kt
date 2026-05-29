package id.nearyou.app.auth

import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FUTURE_EPOCH = 9_999_999_999_000L
private const val PAST_EPOCH = 1_000L
private const val SIGNIN_OK_BODY =
    """{"access_token":"at-new","refresh_token":"rt-new","expires_in":900}"""

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

private fun bearerOf(request: HttpRequestData): String? = request.headers[HttpHeaders.Authorization]

/**
 * Captures every log line so the Authorization-sanitization scenario (5.8g) can assert
 * against emitted output.
 */
private class CapturingLogger : Logger {
    val messages = mutableListOf<String>()

    override fun log(message: String) {
        messages.add(message)
    }
}

private object NoopLogger : Logger {
    override fun log(message: String) = Unit
}

/**
 * MockEngine-backed coverage of the shared `HttpClient` + `AuthApiClient`, mapping the spec
 * scenarios under `openspec/specs/mobile-auth-signin/spec.md` § "Ktor HTTP client attaches
 * Bearer token and handles 401 refresh".
 */
class AuthApiClientTest {
    private fun client(
        tokenStore: TokenStore,
        installLogging: Boolean = false,
        logger: Logger = NoopLogger,
        handler: MockRequestHandler,
    ): HttpClient =
        HttpClientFactory.create(
            apiBaseUrl = "http://test.local",
            tokenStore = tokenStore,
            sessionInvalidator = SessionInvalidator(tokenStore),
            engine = MockEngine(handler),
            installLogging = installLogging,
            logger = logger,
            nowMillis = { 0L },
        )

    // (a) signin request body shape — {provider, id_token} with NO device_fingerprint_hash.
    @Test
    fun `signin request body carries provider and id_token but not device_fingerprint_hash`() =
        runTest {
            var capturedBody = ""
            var capturedPath = ""
            val store = InMemoryTokenStore()
            val httpClient =
                client(store) { request ->
                    capturedPath = request.url.encodedPath
                    capturedBody = request.body.bodyText()
                    respond(
                        content = SIGNIN_OK_BODY,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val result = AuthApiClient(httpClient) { 0L }.signIn("test-google-id-token")

            assertTrue(result is SignInApiResult.Success)
            // Canonical unified-provider endpoint — NOT the menu's `/signin/google` shorthand
            // (spec scenario "Sign-in API call targets the canonical endpoint path").
            assertEquals("/api/v1/auth/signin", capturedPath)
            val parsed = Json.parseToJsonElement(capturedBody)
            assertTrue(capturedBody.contains("\"provider\""), "body: $capturedBody")
            assertTrue(capturedBody.contains("google"))
            assertTrue(capturedBody.contains("test-google-id-token"))
            assertFalse(capturedBody.contains("device_fingerprint_hash"), "body must omit fingerprint: $capturedBody")
            // sanity: it parses as a JSON object
            assertTrue(parsed.toString().isNotEmpty())
        }

    // (b) bearer-token attachment on subsequent (authenticated) requests.
    @Test
    fun `bearer token from store is attached to outbound requests`() =
        runTest {
            var observedAuth: String? = null
            val store = InMemoryTokenStore(TokenPair("at-X", "rt-Y", FUTURE_EPOCH))
            val httpClient =
                client(store) { request ->
                    observedAuth = bearerOf(request)
                    respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            httpClient.get("/api/v1/posts")

            assertEquals("Bearer at-X", observedAuth)
        }

    // (c) 401 → refresh → retry happy path.
    @Test
    fun `401 triggers single refresh then retries original request with new token`() =
        runTest {
            var refreshCalls = 0
            val authHeadersSeen = mutableListOf<String?>()
            val store = InMemoryTokenStore(TokenPair("at-stale", "rt-Y", PAST_EPOCH))
            val httpClient =
                client(store) { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/auth/refresh" -> {
                            refreshCalls++
                            respond(SIGNIN_OK_BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        else -> {
                            val auth = bearerOf(request)
                            authHeadersSeen.add(auth)
                            if (auth == "Bearer at-stale") {
                                respond(
                                    "",
                                    HttpStatusCode.Unauthorized,
                                    headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"nearyou\""),
                                )
                            } else {
                                respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                            }
                        }
                    }
                }
            val response: HttpResponse = httpClient.get("/api/v1/posts")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, refreshCalls)
            assertTrue(authHeadersSeen.contains("Bearer at-stale"), "stale token sent first")
            assertTrue(authHeadersSeen.contains("Bearer at-new"), "fresh token sent on retry")
            assertEquals(TokenPair("at-new", "rt-new", 900_000L), store.read())
        }

    // (d) 401 → refresh-fails (refresh 401) → terminal 401 + store cleared (both tokens).
    @Test
    fun `refresh failure surfaces terminal 401 and clears the whole token pair`() =
        runTest {
            val store = InMemoryTokenStore(TokenPair("at-stale", "rt-revoked", PAST_EPOCH))
            val httpClient =
                client(store) { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/auth/refresh" ->
                            respond(
                                """{"error":{"code":"token_reuse_detected"}}""",
                                HttpStatusCode.Unauthorized,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else ->
                            respond(
                                "",
                                HttpStatusCode.Unauthorized,
                                headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"nearyou\""),
                            )
                    }
                }
            val response: HttpResponse = httpClient.get("/api/v1/posts")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(store.clearCount >= 1, "SessionInvalidator must clear the store on refresh failure")
            assertNull(store.read(), "both access AND refresh tokens cleared")
        }

    // (e) network failure surfaces as SignInApiResult.NetworkError (no token write).
    @Test
    fun `network failure during signin maps to NetworkError`() =
        runTest {
            val store = InMemoryTokenStore()
            val httpClient =
                client(store) { throw RuntimeException("connection refused") }
            val result = AuthApiClient(httpClient) { 0L }.signIn("g-id")

            assertTrue(result is SignInApiResult.NetworkError)
            assertNull(store.read())
        }

    // (f) 3 concurrent 401s during in-flight refresh → exactly ONE refresh, all retry 200.
    @Test
    fun `concurrent 401s queue behind a single refresh`() =
        runTest {
            var refreshCalls = 0
            val store = InMemoryTokenStore(TokenPair("at-stale", "rt-Y", PAST_EPOCH))
            val httpClient =
                client(store) { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/auth/refresh" -> {
                            refreshCalls++
                            delay(10) // widen the in-flight window so the other two queue
                            respond(SIGNIN_OK_BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        else ->
                            if (bearerOf(request) == "Bearer at-stale") {
                                respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Bearer"))
                            } else {
                                respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                            }
                    }
                }
            val responses =
                listOf("/api/v1/posts", "/api/v1/timeline", "/api/v1/profile")
                    .map { path -> async { httpClient.get(path) } }
                    .awaitAll()

            assertEquals(1, refreshCalls, "exactly one refresh for three concurrent 401s")
            assertTrue(responses.all { it.status == HttpStatusCode.OK }, "all three retried to 200")
        }

    // (g) Logging plugin sanitizes the Authorization header.
    @Test
    fun `logging plugin masks the Authorization header value`() =
        runTest {
            val capturing = CapturingLogger()
            val store = InMemoryTokenStore(TokenPair("at-loggable-SENTINEL", "rt-Y", FUTURE_EPOCH))
            val httpClient =
                client(store, installLogging = true, logger = capturing) {
                    respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            httpClient.get("/api/v1/posts")

            val joined = capturing.messages.joinToString("\n")
            assertFalse(joined.contains("at-loggable-SENTINEL"), "raw token must never be logged:\n$joined")
            assertTrue(joined.contains("***"), "Authorization value must be masked to ***:\n$joined")
        }

    // (h) app-backgrounded mid-/signin → cancellation propagates (NOT swallowed as NetworkError),
    //     so the caller is never stuck on a permanent "Sedang masuk…" splash.
    @Test
    fun `cancellation mid-signin propagates rather than mapping to NetworkError`() =
        runTest {
            val store = InMemoryTokenStore()
            val httpClient =
                client(store) {
                    delay(60_000) // request never completes within the job's lifetime
                    respond(SIGNIN_OK_BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val api = AuthApiClient(httpClient) { 0L }

            var completed = false
            val job =
                launch {
                    api.signIn("g-id")
                    completed = true
                }
            // Let signIn dispatch + suspend on the delayed response, then cancel (background-out).
            delay(100)
            job.cancel()
            job.join()

            assertTrue(job.isCancelled, "the in-flight signIn job is cancelled, not hung")
            assertFalse(completed, "signIn did not silently complete with a NetworkError after cancellation")
        }
}
