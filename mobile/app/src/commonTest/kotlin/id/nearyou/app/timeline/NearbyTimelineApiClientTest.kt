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

/** A 200 body whose post object uses the SHIPPED mixed-case wire (camelCase authorUserId/authorUsername/
 *  authorDisplayName/distanceM/createdAt/nextCursor; snake city_name/liked_by_viewer/reply_count) — NOT
 *  the spec's stale snake_case example. */
private const val MIXED_CASE_BODY =
    """{"posts":[{"id":"p1","authorUserId":"a-1","authorUsername":"raka.jkt","authorDisplayName":"Raka Pratama",""" +
        """"content":"halo","latitude":-6.21,"longitude":106.85,""" +
        """"distanceM":1234.5,"city_name":"Jakarta","createdAt":"2026-05-31T10:00:00Z","liked_by_viewer":true,""" +
        """"reply_count":3}],"nextCursor":"tok"}"""

/**
 * MockEngine-backed coverage of [NearbyTimelineApiClient] (8.2): the request path / params / session
 * header, parsing against the SHIPPED mixed-case wire, optional `nextCursor`/`upsell` tolerance, the
 * snake_case-only negative regression (guards the stale-spec assumption), and the non-2xx → result
 * mapping.
 */
class NearbyTimelineApiClientTest {
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
    fun `fetchNearby targets the canonical path with lat lng radius_m and omits the first-page cursor`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                NearbyTimelineApiClient(
                    client { request ->
                        captured = request
                        respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetchNearby(lat = -6.2, lng = 106.8, radiusM = 20000, sessionId = "sess-abc")

            val req = requireNotNull(captured)
            assertEquals("/api/v1/timeline/nearby", req.url.encodedPath)
            assertEquals("-6.2", req.url.parameters["lat"])
            assertEquals("106.8", req.url.parameters["lng"])
            assertEquals("20000", req.url.parameters["radius_m"])
            assertFalse(req.url.parameters.contains("cursor"), "first-page request must omit cursor")
        }

    @Test
    fun `X-Session-Id header is sent and matches the well-formed regex`() =
        runTest {
            var sessionHeader: String? = null
            val id = SessionIdProvider().sessionId
            val api =
                NearbyTimelineApiClient(
                    client { request ->
                        sessionHeader = request.headers["X-Session-Id"]
                        respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetchNearby(lat = -6.2, lng = 106.8, radiusM = 20000, sessionId = id)

            assertEquals(id, sessionHeader)
            assertTrue(Regex("^[A-Za-z0-9-]{1,64}$").matches(requireNotNull(sessionHeader)))
        }

    @Test
    fun `a passed cursor is sent as the cursor param`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                NearbyTimelineApiClient(
                    client { request ->
                        captured = request
                        respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetchNearby(lat = -6.2, lng = 106.8, radiusM = 20000, sessionId = "s", cursor = "tok-2")

            assertEquals("tok-2", requireNotNull(captured).url.parameters["cursor"])
        }

    @Test
    fun `full post shape parses against the shipped mixed-case wire`() =
        runTest {
            val api = NearbyTimelineApiClient(client { respond(MIXED_CASE_BODY, HttpStatusCode.OK, JSON_HEADERS) })
            val result = api.fetchNearby(-6.2, 106.8, 20000, "s")

            val body = assertIsSuccess(result)
            assertEquals(1, body.posts.size)
            val post = body.posts.first()
            assertEquals("p1", post.id)
            assertEquals("a-1", post.authorUserId)
            assertEquals("raka.jkt", post.authorUsername)
            assertEquals("Raka Pratama", post.authorDisplayName)
            assertEquals("halo", post.content)
            assertEquals(-6.21, post.latitude)
            assertEquals(106.85, post.longitude)
            assertEquals(1234.5, post.distanceM)
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
                NearbyTimelineApiClient(
                    client { respond("""{"posts":[],"upsell":{"soft":true}}""", HttpStatusCode.OK, JSON_HEADERS) },
                )
            assertEquals(true, assertIsSuccess(softApi.fetchNearby(-6.2, 106.8, 20000, "s")).upsell?.soft)

            val hardApi =
                NearbyTimelineApiClient(
                    client { respond("""{"posts":[],"nextCursor":null,"upsell":{"hard":true}}""", HttpStatusCode.OK, JSON_HEADERS) },
                )
            val hardBody = assertIsSuccess(hardApi.fetchNearby(-6.2, 106.8, 20000, "s"))
            assertTrue(hardBody.posts.isEmpty())
            assertEquals(true, hardBody.upsell?.hard)
        }

    @Test
    fun `empty city_name parses to empty string`() =
        runTest {
            val body =
                """{"posts":[{"id":"p1","authorUserId":"a","authorUsername":"u","authorDisplayName":"D",""" +
                    """"content":"c","latitude":-6.2,"longitude":106.8,""" +
                    """"distanceM":10.0,"city_name":"","createdAt":"t","liked_by_viewer":false,"reply_count":0}]}"""
            val api = NearbyTimelineApiClient(client { respond(body, HttpStatusCode.OK, JSON_HEADERS) })
            assertEquals("", assertIsSuccess(api.fetchNearby(-6.2, 106.8, 20000, "s")).posts.first().cityName)
        }

    // ---- Negative regression: the shipped wire is mixed-case, NOT the spec's snake_case example. ----

    @Test
    fun `snake_case-only post body fails to parse camelCase wire binding is required`() {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        // author_user_id / author_username / author_display_name / distance_m / created_at are
        // snake_case (the stale spec JSON shape); the REQUIRED camelCase fields are then absent →
        // decoding throws. A fixture MUST use the shipped mixed-case keys, so regenerating the DTO
        // from a stale spec example cannot silently slip in.
        val snakePost =
            """{"id":"p1","author_user_id":"a","author_username":"u","author_display_name":"D",""" +
                """"content":"c","latitude":-6.2,"longitude":106.8,""" +
                """"distance_m":10.0,"city_name":"X","created_at":"t","liked_by_viewer":false,"reply_count":0}"""
        assertFailsWithSerialization { json.decodeFromString(NearbyPostDto.serializer(), snakePost) }
    }

    @Test
    fun `snake_case next_cursor does NOT populate the camelCase nextCursor`() {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        val parsed = json.decodeFromString(NearbyResponseDto.serializer(), """{"posts":[],"next_cursor":"tok"}""")
        assertNull(parsed.nextCursor, "snake_case next_cursor must be ignored (shipped wire is camelCase nextCursor)")
    }

    // ---- Non-2xx / transport mapping (the repository keys outcome on these). ----

    @Test
    fun `non-2xx maps to HttpError carrying the status`() =
        runTest {
            val api400 =
                NearbyTimelineApiClient(
                    client { respond("""{"error":{"code":"invalid_request"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) },
                )
            assertEquals(400, assertIsHttpError(api400.fetchNearby(-6.2, 106.8, 20000, "s")).status)

            val api500 = NearbyTimelineApiClient(client { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) })
            assertEquals(500, assertIsHttpError(api500.fetchNearby(-6.2, 106.8, 20000, "s")).status)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = NearbyTimelineApiClient(client { throw RuntimeException("connection refused") })
            assertTrue(api.fetchNearby(-6.2, 106.8, 20000, "s") is NearbyApiResult.NetworkError)
        }
}

private fun assertIsSuccess(result: NearbyApiResult): NearbyResponseDto {
    assertTrue(result is NearbyApiResult.Success, "expected Success, was $result")
    return result.body
}

private fun assertIsHttpError(result: NearbyApiResult): NearbyApiResult.HttpError {
    assertTrue(result is NearbyApiResult.HttpError, "expected HttpError, was $result")
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
