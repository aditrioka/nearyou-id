package id.nearyou.app.auth

import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 *    non-success refresh response (POST → 401 → invalidate); and
 *  - a cancellation of the LEADER's coroutine mid-POST does NOT poison a follower with a foreign
 *    `CancellationException`, and the in-flight slot is still cleared (audit 2026-06-10, finding 05-#16).
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
    fun leaderCancellation_doesNotPoisonAFollower_andClearsTheInFlightSlot() =
        runTest {
            // Audit 2026-06-10, finding 05-#16: when the LEADER's coroutine is cancelled mid-POST, the
            // shared deferred must NOT be completed with the leader's foreign CancellationException —
            // that would unwind a FOLLOWER whose own request was never cancelled (the original request
            // then failing without SessionInvalidator firing). The leader runs the POST under
            // NonCancellable, so the follower still observes the real rotated pair, and the in-flight
            // slot is cleared so the NEXT caller runs a fresh refresh (not a follow of a dead deferred).
            var refreshCalls = 0
            val store = InMemoryTokenStore(TokenPair("at-stale", "rt-Y", PAST_EPOCH))
            val invalidator = SessionInvalidator(store)
            val leaderEnteredPost = CompletableDeferred<Unit>()
            val httpClient =
                client(store, invalidator) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") {
                        refreshCalls++
                        leaderEnteredPost.complete(Unit) // the leader now holds the single-flight
                        delay(50) // widen the in-flight window so the follower queues + we can cancel
                        respond(REFRESH_OK_BODY, HttpStatusCode.OK, JSON_CT)
                    } else {
                        respond("[]", HttpStatusCode.OK, JSON_CT)
                    }
                }
            val refresher = TokenRefresher(store, invalidator) { 0L }

            val leader = async { refresher.refresh(httpClient) }
            leaderEnteredPost.await() // leader is mid-POST, owns the deferred
            val follower = async { refresher.refresh(httpClient) }
            runCurrent() // let the follower reach await() as a follower (no second POST)
            leader.cancel() // cancel the leader's coroutine while its refresh is in flight

            val expected = TokenPair("at-new", "rt-new", 900_000L)
            assertEquals(
                expected,
                follower.await(),
                "the follower observes the real rotated pair, NOT the leader's foreign CancellationException",
            )
            assertEquals(1, refreshCalls, "the cancelled leader still completes the single POST the follower reuses")

            // The in-flight slot was cleared despite the cancellation → a subsequent refresh re-POSTs.
            assertEquals(expected, refresher.refresh(httpClient), "a later caller runs a fresh refresh")
            assertEquals(2, refreshCalls, "the in-flight slot was cleared, so the later caller is a new leader")
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

    @Test
    fun rateLimitedRefresh_throwsTransient_keepsTokens_andDoesNotInvalidate() =
        runTest {
            // auth-endpoint-rate-limits: a 429 is NOT a rejected refresh token. It must throw a
            // transient signal (NOT return null) and must NOT invalidate — the token pair is kept.
            var refreshCalls = 0
            val original = TokenPair("at-stale", "rt-Y", PAST_EPOCH)
            val store = InMemoryTokenStore(original)
            val invalidator = SessionInvalidator(store)
            val httpClient =
                client(store, invalidator) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") {
                        refreshCalls++
                        respond(
                            """{"error":{"code":"rate_limited"}}""",
                            HttpStatusCode.TooManyRequests,
                            headersOf(
                                HttpHeaders.RetryAfter to listOf("90"),
                                HttpHeaders.ContentType to listOf("application/json"),
                            ),
                        )
                    } else {
                        respond("[]", HttpStatusCode.OK, JSON_CT)
                    }
                }
            val refresher = TokenRefresher(store, invalidator) { 0L }

            val ex = assertFailsWith<RefreshRateLimitedException> { refresher.refresh(httpClient) }

            assertEquals(90L, ex.retryAfterSeconds, "the Retry-After hint is carried")
            assertEquals(1, refreshCalls, "the refresh POST is attempted once")
            assertEquals(0, store.clearCount, "a 429 must NOT invalidate the session")
            assertEquals(original, store.read(), "the token pair is preserved on a 429")
        }

    @Test
    fun rateLimitedRefresh_propagatesToFollowers_withoutLoggingAnyoneOut() =
        runTest {
            // The single-flight follower must observe the SAME transient (a thrown exception), not a
            // null — a null would log the follower out, the precise CGNAT failure the change prevents.
            var refreshCalls = 0
            val original = TokenPair("at-stale", "rt-Y", PAST_EPOCH)
            val store = InMemoryTokenStore(original)
            val invalidator = SessionInvalidator(store)
            val httpClient =
                client(store, invalidator) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") {
                        refreshCalls++
                        delay(50) // widen the in-flight window so the second caller queues as a follower
                        respond(
                            """{"error":{"code":"rate_limited"}}""",
                            HttpStatusCode.TooManyRequests,
                            headersOf(
                                HttpHeaders.RetryAfter to listOf("30"),
                                HttpHeaders.ContentType to listOf("application/json"),
                            ),
                        )
                    } else {
                        respond("[]", HttpStatusCode.OK, JSON_CT)
                    }
                }
            val refresher = TokenRefresher(store, invalidator) { 0L }

            val results =
                listOf(
                    async { runCatching { refresher.refresh(httpClient) } },
                    async { runCatching { refresher.refresh(httpClient) } },
                ).awaitAll()

            assertEquals(1, refreshCalls, "two overlapping 429 refreshes ⇒ exactly ONE POST")
            assertTrue(
                results.all { it.exceptionOrNull() is RefreshRateLimitedException },
                "leader AND follower both observe the transient (not a null-driven logout): $results",
            )
            assertEquals(0, store.clearCount, "neither the leader nor the follower is logged out")
            assertEquals(original, store.read(), "the token pair is preserved for both")
        }
}
