package id.nearyou.app.search

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/**
 * MockEngine-backed coverage of [SearchRepository]: every HTTP status maps to exactly one
 * [SearchOutcome] (200 → Results; 403 → PremiumGate; 429 → RateLimited(Retry-After); 503 → Disabled;
 * 400 → Error; terminal 401 → SessionExpired; 5xx/IO → NetworkError; unenumerated → NetworkError
 * fallback) — the status-keyed mapping with no generic fallthrough (design D4).
 */
class SearchRepositoryTest {
    private fun repository(
        tokenStore: TokenStore = InMemoryTokenStore(),
        handler: MockRequestHandler,
    ): SearchRepository {
        val client: HttpClient =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = tokenStore,
                // Same store so a refresh-failure invalidate() clears the very tokens loadTokens reads.
                sessionInvalidator = SessionInvalidator(tokenStore),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return SearchRepository(SearchApiClient(client))
    }

    @Test
    fun `200 maps to Results carrying hits and nextOffset`() =
        runTest {
            val repo =
                repository {
                    respond(
                        """{"results":[{"post_id":"p1","author_id":"a","author_username":"u","author_display_name":"D",""" +
                            """"content":"c","created_at":"t","rank":0.5}],"next_offset":20}""",
                        HttpStatusCode.OK,
                        JSON_HEADERS,
                    )
                }
            val outcome = repo.search("jakarta", 0)
            assertTrue(outcome is SearchOutcome.Results)
            assertEquals(1, outcome.hits.size)
            assertEquals(20, outcome.nextOffset)
        }

    @Test
    fun `403 maps to PremiumGate`() =
        runTest {
            val repo =
                repository { respond("""{"error":"premium_required","upsell":true}""", HttpStatusCode.Forbidden, JSON_HEADERS) }
            assertEquals(SearchOutcome.PremiumGate, repo.search("jakarta", 0))
        }

    @Test
    fun `429 maps to RateLimited carrying the Retry-After seconds`() =
        runTest {
            val repo =
                repository {
                    respond("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1740"))
                }
            assertEquals(SearchOutcome.RateLimited(1740L), repo.search("jakarta", 0))
        }

    @Test
    fun `429 with absent Retry-After floors to RateLimited zero`() =
        runTest {
            val repo = repository { respond("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests, JSON_HEADERS) }
            assertEquals(SearchOutcome.RateLimited(0L), repo.search("jakarta", 0))
        }

    @Test
    fun `503 maps to Disabled`() =
        runTest {
            val repo = repository { respond("""{"error":"search_disabled"}""", HttpStatusCode.ServiceUnavailable, JSON_HEADERS) }
            assertEquals(SearchOutcome.Disabled, repo.search("jakarta", 0))
        }

    @Test
    fun `400 maps to Error`() =
        runTest {
            val repo = repository { respond("""{"error":"invalid_query_length"}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
            assertEquals(SearchOutcome.Error, repo.search("jakarta", 0))
        }

    @Test
    fun `terminal 401 maps to SessionExpired not NetworkError`() =
        runTest {
            // A token is present so the Auth plugin attempts a refresh; both the fetch AND the refresh
            // POST return 401 → terminal 401 → SessionExpired (NOT NetworkError, NOT Error).
            val store = InMemoryTokenStore(TokenPair("at-stale", "rt-Y", 1L))
            val repo =
                repository(tokenStore = store) { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/auth/refresh" -> respond("", HttpStatusCode.Unauthorized, JSON_HEADERS)
                        else ->
                            respond(
                                "",
                                HttpStatusCode.Unauthorized,
                                headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"nearyou\""),
                            )
                    }
                }
            assertEquals(SearchOutcome.SessionExpired, repo.search("jakarta", 0))
        }

    @Test
    fun `5xx maps to NetworkError`() =
        runTest {
            val repo = repository { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(SearchOutcome.NetworkError, repo.search("jakarta", 0))
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val repo = repository { throw RuntimeException("connection refused") }
            assertEquals(SearchOutcome.NetworkError, repo.search("jakarta", 0))
        }

    @Test
    fun `unenumerated non-2xx falls to the defined NetworkError fallback`() =
        runTest {
            val repo = repository { respond("", HttpStatusCode.NotFound, JSON_HEADERS) }
            assertEquals(SearchOutcome.NetworkError, repo.search("jakarta", 0))
        }
}
