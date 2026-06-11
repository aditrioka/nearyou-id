package id.nearyou.app.auth

import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PAST_EPOCH = 1_000L
private const val REFRESH_OK_BODY =
    """{"access_token":"at-new","refresh_token":"rt-new","expires_in":900}"""
private val JSON_CT = headersOf(HttpHeaders.ContentType, "application/json")

/**
 * 9.2 — [TokenRefresher] single-flight (mobile-session-expiry-and-proactive-refresh, D2). Asserts:
 *  - two overlapping `refresh(client)` calls (the proactive + reactive cross-path) perform EXACTLY
 *    ONE `POST /api/v1/auth/refresh` — the second caller awaits-and-reuses the in-flight result; and
 *  - BOTH `invalidate()` call sites funnel through the refresher: a null refresh token (no POST) AND a
 *    non-success refresh response (POST → 401 → invalidate).
 *
 * The shipped `Auth`-plugin "Concurrent 401s … retry once" guarantee (now routed through the same
 * `TokenRefresher`) stays covered by `AuthApiClientTest`.
 */
class TokenRefresherTest {
    private fun client(
        tokenStore: TokenStore,
        sessionInvalidator: SessionInvalidator,
        handler: MockRequestHandler,
    ): HttpClient =
        HttpClientFactory.create(
            installTimeouts = false,
            apiBaseUrl = "http://test.local",
            tokenStore = tokenStore,
            sessionInvalidator = sessionInvalidator,
            engine = MockEngine(handler),
            installLogging = false,
            nowMillis = { 0L },
        )

    @Test
    fun overlappingRefreshes_issueExactlyOnePost_andBothReuseTheResult() =
        runTest {
            var refreshCalls = 0
            val store = InMemoryTokenStore(TokenPair("at-stale", "rt-Y", PAST_EPOCH))
            val invalidator = SessionInvalidator(store)
            val httpClient =
                client(store, invalidator) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") {
                        refreshCalls++
                        delay(50) // widen the in-flight window so the second caller queues behind it
                        respond(REFRESH_OK_BODY, HttpStatusCode.OK, JSON_CT)
                    } else {
                        respond("[]", HttpStatusCode.OK, JSON_CT)
                    }
                }
            val refresher = TokenRefresher(store, invalidator) { 0L }

            // A proactive refresh fired INTO the in-flight window of a (concurrent) reactive refresh.
            val results =
                listOf(
                    async { refresher.refresh(httpClient) },
                    async { refresher.refresh(httpClient) },
                ).awaitAll()

            assertEquals(1, refreshCalls, "two overlapping refreshes ⇒ exactly ONE POST /api/v1/auth/refresh")
            val expected = TokenPair("at-new", "rt-new", 900_000L)
            assertTrue(results.all { it == expected }, "both callers reuse the single refresh result: $results")
            assertEquals(expected, store.read(), "the new token pair is persisted once")
        }

    @Test
    fun nullRefreshToken_invalidatesWithoutPosting() =
        runTest {
            var refreshCalls = 0
            val store = InMemoryTokenStore() // empty → no refresh token
            val invalidator = SessionInvalidator(store)
            val httpClient =
                client(store, invalidator) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") refreshCalls++
                    respond("[]", HttpStatusCode.OK, JSON_CT)
                }
            val refresher = TokenRefresher(store, invalidator) { 0L }

            val result = refresher.refresh(httpClient)

            assertNull(result, "a null refresh token yields no tokens")
            assertEquals(0, refreshCalls, "no POST is issued when there is no refresh token")
            assertTrue(store.clearCount >= 1, "the null-refresh-token path funnels through invalidate()")
        }

    @Test
    fun nonSuccessRefresh_invalidatesAndClearsTheStore() =
        runTest {
            var refreshCalls = 0
            val store = InMemoryTokenStore(TokenPair("at-stale", "rt-revoked", PAST_EPOCH))
            val invalidator = SessionInvalidator(store)
            val httpClient =
                client(store, invalidator) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") {
                        refreshCalls++
                        respond(
                            """{"error":{"code":"token_reuse_detected"}}""",
                            HttpStatusCode.Unauthorized,
                            JSON_CT,
                        )
                    } else {
                        respond("[]", HttpStatusCode.OK, JSON_CT)
                    }
                }
            val refresher = TokenRefresher(store, invalidator) { 0L }

            val result = refresher.refresh(httpClient)

            assertNull(result, "a rejected refresh token yields no tokens")
            assertEquals(1, refreshCalls, "the refresh POST is attempted once")
            assertTrue(store.clearCount >= 1, "the non-success refresh funnels through invalidate()")
            assertNull(store.read(), "both access AND refresh tokens are cleared")
        }
}
