package id.nearyou.app.timeline

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

/** A 200 body whose post object uses the SHIPPED Global wire (camelCase authorUserId/createdAt/nextCursor;
 *  snake city_name/liked_by_viewer/reply_count) and — because Global has no spatial filter — carries NO
 *  `distanceM`. NOT the spec's snake_case example. */
private const val MIXED_CASE_BODY =
    """{"posts":[{"id":"p1","authorUserId":"a-1","content":"halo","latitude":-6.21,"longitude":106.85,""" +
        """"city_name":"Jakarta","createdAt":"2026-05-31T10:00:00Z","liked_by_viewer":true,""" +
        """"reply_count":3}],"nextCursor":"tok"}"""

/**
 * MockEngine-backed coverage of [GlobalTimelineApiClient] (8.2): the request path carries NO spatial
 * params / first-page cursor, the `X-Session-Id` header, parsing against the SHIPPED distance-less
 * mixed-case wire, optional `nextCursor`/`upsell` tolerance, the snake_case-only negative regression
 * (guards the stale-spec assumption), and the non-2xx → result mapping.
 */
class GlobalTimelineApiClientTest {
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
    fun `fetchGlobal targets the canonical path with no spatial params and omits the first-page cursor`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                GlobalTimelineApiClient(
                    client { request ->
                        captured = request
                        respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetchGlobal(sessionId = "sess-abc")

            val req = requireNotNull(captured)
            assertEquals("/api/v1/timeline/global", req.url.encodedPath)
            assertFalse(req.url.parameters.contains("lat"), "Global has no spatial filter: no lat")
            assertFalse(req.url.parameters.contains("lng"), "Global has no spatial filter: no lng")
            assertFalse(req.url.parameters.contains("radius_m"), "Global has no spatial filter: no radius_m")
            assertFalse(req.url.parameters.contains("cursor"), "first-page request must omit cursor")
        }

    @Test
    fun `X-Session-Id header is sent and equals the shared provider id and matches the well-formed regex`() =
        runTest {
            var sessionHeader: String? = null
            val id = SessionIdProvider().sessionId
            val api =
                GlobalTimelineApiClient(
                    client { request ->
                        sessionHeader = request.headers["X-Session-Id"]
                        respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetchGlobal(sessionId = id)

            assertEquals(id, sessionHeader)
            assertTrue(Regex("^[A-Za-z0-9-]{1,64}$").matches(requireNotNull(sessionHeader)))
        }

    @Test
    fun `a passed cursor is sent as the cursor param`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                GlobalTimelineApiClient(
                    client { request ->
                        captured = request
                        respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetchGlobal(sessionId = "s", cursor = "tok-2")

            assertEquals("tok-2", requireNotNull(captured).url.parameters["cursor"])
        }

    @Test
    fun `full post shape parses against the shipped distance-less mixed-case wire`() =
        runTest {
            val api = GlobalTimelineApiClient(client { respond(MIXED_CASE_BODY, HttpStatusCode.OK, JSON_HEADERS) })
            val result = api.fetchGlobal(sessionId = "s")

            val body = assertIsSuccess(result)
            assertEquals(1, body.posts.size)
            val post = body.posts.first()
            assertEquals("p1", post.id)
            assertEquals("a-1", post.authorUserId)
            assertEquals("halo", post.content)
            assertEquals(-6.21, post.latitude)
            assertEquals(106.85, post.longitude)
            assertEquals("Jakarta", post.cityName)
            assertEquals("2026-05-31T10:00:00Z", post.createdAt)
            assertTrue(post.likedByViewer)
            assertEquals(3, post.replyCount)
            assertEquals("tok", body.nextCursor)
            assertNull(body.upsell, "absent upsell tolerated → null")
        }

    @Test
    fun `upsell soft and hard parse`() =
        runTest {
            val softApi =
                GlobalTimelineApiClient(
                    client { respond("""{"posts":[],"upsell":{"soft":true}}""", HttpStatusCode.OK, JSON_HEADERS) },
                )
            assertEquals(true, assertIsSuccess(softApi.fetchGlobal(sessionId = "s")).upsell?.soft)

            val hardApi =
                GlobalTimelineApiClient(
                    client { respond("""{"posts":[],"nextCursor":null,"upsell":{"hard":true}}""", HttpStatusCode.OK, JSON_HEADERS) },
                )
            val hardBody = assertIsSuccess(hardApi.fetchGlobal(sessionId = "s"))
            assertTrue(hardBody.posts.isEmpty())
            assertEquals(true, hardBody.upsell?.hard)
        }

    @Test
    fun `empty city_name parses to empty string`() =
        runTest {
            val body =
                """{"posts":[{"id":"p1","authorUserId":"a","content":"c","latitude":-6.2,"longitude":106.8,""" +
                    """"city_name":"","createdAt":"t","liked_by_viewer":false,"reply_count":0}]}"""
            val api = GlobalTimelineApiClient(client { respond(body, HttpStatusCode.OK, JSON_HEADERS) })
            assertEquals("", assertIsSuccess(api.fetchGlobal(sessionId = "s")).posts.first().cityName)
        }

    // ---- Negative regression: the shipped wire is mixed-case, NOT the spec's snake_case example. ----

    @Test
    fun `snake_case-only post body fails to parse camelCase wire binding is required`() {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        // author_user_id / created_at are snake_case (the stale spec JSON example); the REQUIRED
        // camelCase fields are then absent → decoding throws. A fixture MUST use the shipped mixed-case
        // keys, so regenerating the DTO from the spec example cannot silently slip in.
        val snakePost =
            """{"id":"p1","author_user_id":"a","content":"c","latitude":-6.2,"longitude":106.8,""" +
                """"city_name":"X","created_at":"t","liked_by_viewer":false,"reply_count":0}"""
        assertFailsWithSerialization { json.decodeFromString(GlobalPostDto.serializer(), snakePost) }
    }

    @Test
    fun `snake_case next_cursor does NOT populate the camelCase nextCursor`() {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        val parsed = json.decodeFromString(GlobalResponseDto.serializer(), """{"posts":[],"next_cursor":"tok"}""")
        assertNull(parsed.nextCursor, "snake_case next_cursor must be ignored (shipped wire is camelCase nextCursor)")
    }

    // ---- Non-2xx / transport mapping (the repository keys outcome on these). ----

    @Test
    fun `non-2xx maps to HttpError carrying the status`() =
        runTest {
            val api400 =
                GlobalTimelineApiClient(
                    client { respond("""{"error":{"code":"invalid_cursor"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) },
                )
            assertEquals(400, assertIsHttpError(api400.fetchGlobal(sessionId = "s")).status)

            val api500 = GlobalTimelineApiClient(client { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) })
            assertEquals(500, assertIsHttpError(api500.fetchGlobal(sessionId = "s")).status)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = GlobalTimelineApiClient(client { throw RuntimeException("connection refused") })
            assertTrue(api.fetchGlobal(sessionId = "s") is GlobalApiResult.NetworkError)
        }
}

private fun assertIsSuccess(result: GlobalApiResult): GlobalResponseDto {
    assertTrue(result is GlobalApiResult.Success, "expected Success, was $result")
    return result.body
}

private fun assertIsHttpError(result: GlobalApiResult): GlobalApiResult.HttpError {
    assertTrue(result is GlobalApiResult.HttpError, "expected HttpError, was $result")
    return result
}

private inline fun assertFailsWithSerialization(block: () -> Unit) {
    try {
        block()
    } catch (_: SerializationException) {
        return
    }
    throw AssertionError("expected a SerializationException (snake_case body must not decode against the camelCase wire)")
}
