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

/** A 200 body whose post object uses the SHIPPED Following wire (camelCase authorUserId/authorUsername/
 *  authorDisplayName/createdAt/nextCursor; snake city_name/liked_by_viewer/reply_count) and — because
 *  Following has no spatial filter — carries NO `distanceM`. NOT the spec's stale snake_case example. */
private const val MIXED_CASE_BODY =
    """{"posts":[{"id":"p1","authorUserId":"a-1","authorUsername":"fajar.r","authorDisplayName":"Fajar Ramadhan",""" +
        """"content":"halo","latitude":-6.21,"longitude":106.85,""" +
        """"city_name":"Jakarta","createdAt":"2026-06-13T10:00:00Z","liked_by_viewer":true,""" +
        """"reply_count":3}],"nextCursor":"tok"}"""

/**
 * MockEngine-backed coverage of [FollowingTimelineApiClient] (tasks 6.3): the request path carries NO
 * spatial params / first-page cursor, the `X-Session-Id` header, parsing against the SHIPPED distance-less
 * mixed-case wire, optional `nextCursor`/`upsell` tolerance, the snake_case-only negative regression
 * (guards the stale-spec assumption), and the non-2xx → result mapping. Mirrors `GlobalTimelineApiClientTest`.
 */
class FollowingTimelineApiClientTest {
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
    fun `fetchFollowing targets the canonical path with no spatial params and omits the first-page cursor`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                FollowingTimelineApiClient(
                    client { request ->
                        captured = request
                        respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetchFollowing(sessionId = "sess-abc")

            val req = requireNotNull(captured)
            assertEquals("/api/v1/timeline/following", req.url.encodedPath)
            assertFalse(req.url.parameters.contains("lat"), "Following has no spatial filter: no lat")
            assertFalse(req.url.parameters.contains("lng"), "Following has no spatial filter: no lng")
            assertFalse(req.url.parameters.contains("radius_m"), "Following has no spatial filter: no radius_m")
            assertFalse(req.url.parameters.contains("cursor"), "first-page request must omit cursor")
        }

    @Test
    fun `X-Session-Id header is sent and equals the shared provider id and matches the well-formed regex`() =
        runTest {
            var sessionHeader: String? = null
            val id = SessionIdProvider().sessionId
            val api =
                FollowingTimelineApiClient(
                    client { request ->
                        sessionHeader = request.headers["X-Session-Id"]
                        respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetchFollowing(sessionId = id)

            assertEquals(id, sessionHeader)
            assertTrue(Regex("^[A-Za-z0-9-]{1,64}$").matches(requireNotNull(sessionHeader)))
        }

    @Test
    fun `a passed cursor is sent as the cursor param`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                FollowingTimelineApiClient(
                    client { request ->
                        captured = request
                        respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetchFollowing(sessionId = "s", cursor = "tok-2")

            assertEquals("tok-2", requireNotNull(captured).url.parameters["cursor"])
        }

    @Test
    fun `a load-more request carries the cursor and still NO spatial params`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                FollowingTimelineApiClient(
                    client { request ->
                        captured = request
                        respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetchFollowing(sessionId = "sess-abc", cursor = "c1")

            val req = requireNotNull(captured)
            assertEquals("c1", req.url.parameters["cursor"], "the load-more cursor is sent")
            assertFalse(req.url.parameters.contains("lat"), "Following load-more has no spatial filter: no lat")
            assertFalse(req.url.parameters.contains("lng"), "Following load-more has no spatial filter: no lng")
            assertFalse(req.url.parameters.contains("radius_m"), "Following load-more has no spatial filter: no radius_m")
        }

    @Test
    fun `full post shape parses against the shipped distance-less mixed-case wire`() =
        runTest {
            val api = FollowingTimelineApiClient(client { respond(MIXED_CASE_BODY, HttpStatusCode.OK, JSON_HEADERS) })
            val result = api.fetchFollowing(sessionId = "s")

            val body = assertIsSuccess(result)
            assertEquals(1, body.posts.size)
            val post = body.posts.first()
            assertEquals("p1", post.id)
            assertEquals("a-1", post.authorUserId)
            assertEquals("fajar.r", post.authorUsername)
            assertEquals("Fajar Ramadhan", post.authorDisplayName)
            assertEquals("halo", post.content)
            assertEquals(-6.21, post.latitude)
            assertEquals(106.85, post.longitude)
            assertEquals("Jakarta", post.cityName)
            assertEquals("2026-06-13T10:00:00Z", post.createdAt)
            assertTrue(post.likedByViewer)
            assertEquals(3, post.replyCount)
            assertEquals("tok", body.nextCursor)
            assertNull(body.upsell, "absent upsell tolerated → null")
        }

    @Test
    fun `upsell soft and hard parse`() =
        runTest {
            val softApi =
                FollowingTimelineApiClient(
                    client { respond("""{"posts":[],"upsell":{"soft":true}}""", HttpStatusCode.OK, JSON_HEADERS) },
                )
            assertEquals(true, assertIsSuccess(softApi.fetchFollowing(sessionId = "s")).upsell?.soft)

            val hardApi =
                FollowingTimelineApiClient(
                    client { respond("""{"posts":[],"nextCursor":null,"upsell":{"hard":true}}""", HttpStatusCode.OK, JSON_HEADERS) },
                )
            val hardBody = assertIsSuccess(hardApi.fetchFollowing(sessionId = "s"))
            assertTrue(hardBody.posts.isEmpty())
            assertEquals(true, hardBody.upsell?.hard)
        }

    @Test
    fun `empty city_name parses to empty string`() =
        runTest {
            val body =
                """{"posts":[{"id":"p1","authorUserId":"a","authorUsername":"u","authorDisplayName":"D",""" +
                    """"content":"c","latitude":-6.2,"longitude":106.8,""" +
                    """"city_name":"","createdAt":"t","liked_by_viewer":false,"reply_count":0}]}"""
            val api = FollowingTimelineApiClient(client { respond(body, HttpStatusCode.OK, JSON_HEADERS) })
            assertEquals("", assertIsSuccess(api.fetchFollowing(sessionId = "s")).posts.first().cityName)
        }

    @Test
    fun `no distanceM field is defined on the Following DTO`() =
        runTest {
            // A wire body that (erroneously) carries distanceM must NOT populate a field — there is none.
            // ignoreUnknownKeys swallows the extra key; the parsed post simply has no distance to render.
            val body =
                """{"posts":[{"id":"p1","authorUserId":"a","authorUsername":"u","authorDisplayName":"D",""" +
                    """"content":"c","latitude":-6.2,"longitude":106.8,"distanceM":1234.0,""" +
                    """"city_name":"X","createdAt":"t","liked_by_viewer":false,"reply_count":0}]}"""
            val api = FollowingTimelineApiClient(client { respond(body, HttpStatusCode.OK, JSON_HEADERS) })
            val post = assertIsSuccess(api.fetchFollowing(sessionId = "s")).posts.first()
            // The DTO has no distanceM property; toString() therefore never echoes the stray value.
            assertFalse(post.toString().contains("1234.0"), "FollowingPostDto declares no distanceM field")
        }

    // ---- Negative regression: the shipped wire is mixed-case, NOT the spec's snake_case example. ----

    @Test
    fun `snake_case-only post body fails to parse camelCase wire binding is required`() {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        // author_user_id / author_username / author_display_name / created_at are snake_case (the stale
        // spec JSON shape); the REQUIRED camelCase fields are then absent → decoding throws. A fixture
        // MUST use the shipped mixed-case keys, so regenerating the DTO from a stale spec example cannot
        // silently slip in.
        val snakePost =
            """{"id":"p1","author_user_id":"a","author_username":"u","author_display_name":"D",""" +
                """"content":"c","latitude":-6.2,"longitude":106.8,""" +
                """"city_name":"X","created_at":"t","liked_by_viewer":false,"reply_count":0}"""
        assertFailsWithSerialization { json.decodeFromString(FollowingPostDto.serializer(), snakePost) }
    }

    @Test
    fun `snake_case next_cursor does NOT populate the camelCase nextCursor`() {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        val parsed = json.decodeFromString(FollowingResponseDto.serializer(), """{"posts":[],"next_cursor":"tok"}""")
        assertNull(parsed.nextCursor, "snake_case next_cursor must be ignored (shipped wire is camelCase nextCursor)")
    }

    // ---- Non-2xx / transport mapping (the repository keys outcome on these). ----

    @Test
    fun `non-2xx maps to HttpError carrying the status`() =
        runTest {
            val api400 =
                FollowingTimelineApiClient(
                    client { respond("""{"error":{"code":"invalid_cursor"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) },
                )
            assertEquals(400, assertIsHttpError(api400.fetchFollowing(sessionId = "s")).status)

            val api500 = FollowingTimelineApiClient(client { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) })
            assertEquals(500, assertIsHttpError(api500.fetchFollowing(sessionId = "s")).status)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = FollowingTimelineApiClient(client { throw RuntimeException("connection refused") })
            assertTrue(api.fetchFollowing(sessionId = "s") is FollowingApiResult.NetworkError)
        }
}

private fun assertIsSuccess(result: FollowingApiResult): FollowingResponseDto {
    assertTrue(result is FollowingApiResult.Success, "expected Success, was $result")
    return result.body
}

private fun assertIsHttpError(result: FollowingApiResult): FollowingApiResult.HttpError {
    assertTrue(result is FollowingApiResult.HttpError, "expected HttpError, was $result")
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
