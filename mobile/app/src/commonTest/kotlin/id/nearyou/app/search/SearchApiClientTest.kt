package id.nearyou.app.search

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** A 200 body whose result object uses the SHIPPED search wire (snake post_id/author_id/author_username/
 *  author_display_name/created_at/next_offset; bare content/rank) — NOT the timelines' camelCase. */
private const val SNAKE_CASE_BODY =
    """{"results":[{"post_id":"p1","author_id":"a-1","author_username":"dewi.kuliner",""" +
        """"author_display_name":"Dewi Lestari","content":"halo jakarta","created_at":"2026-05-31T10:00:00Z",""" +
        """"rank":0.83}],"next_offset":20}"""

/**
 * MockEngine-backed coverage of [SearchApiClient]: the request path + q/offset params (no spatial
 * params), parsing against the SHIPPED snake_case wire, the camelCase negative regression (guards the
 * casing-drift trap), the next_offset parse, the Retry-After parse on 429, and the non-2xx → result
 * mapping (incl. the absent-Retry-After case).
 */
class SearchApiClientTest {
    private fun client(handler: MockRequestHandler): HttpClient =
        HttpClientFactory.create(
            installTimeouts = false,
            apiBaseUrl = "http://test.local",
            tokenStore = InMemoryTokenStore(),
            sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
            engine = MockEngine(handler),
            installLogging = false,
            nowMillis = { 0L },
        )

    @Test
    fun `search targets the canonical path with q and offset and no spatial params`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                SearchApiClient(
                    client { request ->
                        captured = request
                        respond("""{"results":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.search(query = "jakarta", offset = 0)

            val req = requireNotNull(captured)
            assertEquals("/api/v1/search", req.url.encodedPath)
            assertEquals("jakarta", req.url.parameters["q"])
            assertEquals("0", req.url.parameters["offset"])
            assertFalse(req.url.parameters.contains("lat"), "search has no spatial filter: no lat")
            assertFalse(req.url.parameters.contains("lng"), "search has no spatial filter: no lng")
            assertFalse(req.url.parameters.contains("radius_m"), "search has no spatial filter: no radius_m")
        }

    @Test
    fun `a load-more passes the retained offset`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                SearchApiClient(
                    client { request ->
                        captured = request
                        respond("""{"results":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.search(query = "jakarta", offset = 20)

            assertEquals("20", requireNotNull(captured).url.parameters["offset"])
        }

    @Test
    fun `full hit parses against the shipped snake_case wire`() =
        runTest {
            val api = SearchApiClient(client { respond(SNAKE_CASE_BODY, HttpStatusCode.OK, JSON_HEADERS) })
            val body = assertIsSuccess(api.search("jakarta", 0))

            assertEquals(1, body.results.size)
            val hit = body.results.first()
            assertEquals("p1", hit.postId)
            assertEquals("a-1", hit.authorId)
            assertEquals("dewi.kuliner", hit.authorUsername)
            assertEquals("Dewi Lestari", hit.authorDisplayName)
            assertEquals("halo jakarta", hit.content)
            assertEquals("2026-05-31T10:00:00Z", hit.createdAt)
            assertEquals(0.83f, hit.rank)
            assertEquals(20, body.nextOffset)
        }

    @Test
    fun `null next_offset tolerated`() =
        runTest {
            val api = SearchApiClient(client { respond("""{"results":[]}""", HttpStatusCode.OK, JSON_HEADERS) })
            assertNull(assertIsSuccess(api.search("jakarta", 0)).nextOffset)
        }

    // ---- Negative regression: the shipped wire is snake_case, NOT the timelines' camelCase. ----

    @Test
    fun `camelCase-only result body fails to bind the snake_case wire`() {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        // postId / authorUsername / authorDisplayName / createdAt are camelCase (the timeline shape);
        // the REQUIRED snake_case fields are then absent → decoding throws. A fixture MUST use the
        // shipped snake_case keys so a casing-drift cannot silently slip in.
        val camelHit =
            """{"postId":"p1","authorId":"a","authorUsername":"u","authorDisplayName":"D",""" +
                """"content":"c","createdAt":"t","rank":0.5}"""
        assertFailsWithSerialization { json.decodeFromString(SearchResultDto.serializer(), camelHit) }
    }

    @Test
    fun `camelCase nextOffset does NOT populate the snake_case next_offset`() {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        val parsed = json.decodeFromString(SearchResponseDto.serializer(), """{"results":[],"nextOffset":20}""")
        assertNull(parsed.nextOffset, "camelCase nextOffset must be ignored (shipped wire is snake next_offset)")
    }

    // ---- Non-2xx / transport mapping (the repository keys outcome on these). ----

    @Test
    fun `429 maps to HttpError carrying the Retry-After seconds`() =
        runTest {
            val api =
                SearchApiClient(
                    client {
                        respond(
                            """{"error":"rate_limited"}""",
                            HttpStatusCode.TooManyRequests,
                            headersOf("Retry-After", "1740"),
                        )
                    },
                )
            val err = assertIsHttpError(api.search("jakarta", 0))
            assertEquals(429, err.status)
            assertEquals(1740L, err.retryAfterSeconds)
        }

    @Test
    fun `429 with no Retry-After maps to null retryAfterSeconds`() =
        runTest {
            val api =
                SearchApiClient(
                    client { respond("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests, JSON_HEADERS) },
                )
            assertNull(assertIsHttpError(api.search("jakarta", 0)).retryAfterSeconds)
        }

    @Test
    fun `403 503 400 map to HttpError carrying the status`() =
        runTest {
            val api403 =
                SearchApiClient(
                    client { respond("""{"error":"premium_required","upsell":true}""", HttpStatusCode.Forbidden, JSON_HEADERS) },
                )
            assertEquals(403, assertIsHttpError(api403.search("jakarta", 0)).status)

            val api503 =
                SearchApiClient(
                    client { respond("""{"error":"search_disabled"}""", HttpStatusCode.ServiceUnavailable, JSON_HEADERS) },
                )
            assertEquals(503, assertIsHttpError(api503.search("jakarta", 0)).status)

            val api400 =
                SearchApiClient(
                    client { respond("""{"error":"invalid_query_length"}""", HttpStatusCode.BadRequest, JSON_HEADERS) },
                )
            assertEquals(400, assertIsHttpError(api400.search("jakarta", 0)).status)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = SearchApiClient(client { throw RuntimeException("connection refused") })
            assertTrue(api.search("jakarta", 0) is SearchApiResult.NetworkError)
        }
}

private fun assertIsSuccess(result: SearchApiResult): SearchResponseDto {
    assertTrue(result is SearchApiResult.Success, "expected Success, was $result")
    return result.body
}

private fun assertIsHttpError(result: SearchApiResult): SearchApiResult.HttpError {
    assertTrue(result is SearchApiResult.HttpError, "expected HttpError, was $result")
    return result
}

private inline fun assertFailsWithSerialization(block: () -> Unit) {
    try {
        block()
    } catch (_: SerializationException) {
        return
    }
    throw AssertionError("expected a SerializationException (camelCase body must not decode against the snake_case wire)")
}
