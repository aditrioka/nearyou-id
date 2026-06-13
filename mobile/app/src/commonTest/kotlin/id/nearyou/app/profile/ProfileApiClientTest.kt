package id.nearyou.app.profile

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

class ProfileApiClientTest {
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
    fun `getProfile parses the shipped camelCase wire with bio present and isPrivate omitted`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                ProfileApiClient(
                    client { request ->
                        captured = request
                        respond(
                            """
                            {"userId":"u1","username":"raka.jkt","displayName":"Raka Pratama","bio":"halo",
                             "followerCount":3,"followingCount":5,"isSelf":false,"followedByViewer":true,"isPremium":false}
                            """.trimIndent(),
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )
            val result = assertIs<ProfileReadApiResult.Success>(api.getProfile("u1"))
            assertEquals(HttpMethod.Get, captured!!.method)
            assertEquals("/api/v1/users/u1", captured!!.url.encodedPath)
            assertEquals("halo", result.body.bio)
            assertTrue(result.body.followedByViewer)
            assertNull(result.body.isPrivate, "isPrivate absent on the wire must decode to null")
        }

    @Test
    fun `getProfile tolerates an omitted bio - explicitNulls false`() =
        runTest {
            val api =
                ProfileApiClient(
                    client {
                        respond(
                            """
                            {"userId":"u1","username":"r","displayName":"R","followerCount":0,"followingCount":0,
                             "isSelf":true,"followedByViewer":false,"isPremium":false,"isPrivate":false}
                            """.trimIndent(),
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )
            val result = assertIs<ProfileReadApiResult.Success>(api.getProfile("u1"))
            assertNull(result.body.bio)
            assertTrue(result.body.isSelf)
            assertEquals(false, result.body.isPrivate)
        }

    @Test
    fun `getProfile does not bind a snake_case body - casing-drift negative guard`() =
        runTest {
            val api =
                ProfileApiClient(
                    client {
                        // snake_case keys (a stale-spec JSON shape) — the required camelCase fields are
                        // missing, so the parse fails → NetworkError, never a Success with bound fields.
                        respond(
                            """{"user_id":"u1","display_name":"R","follower_count":3}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )
            assertIs<ProfileReadApiResult.NetworkError>(api.getProfile("u1"))
        }

    @Test
    fun `getProfile maps a 404 to HttpError carrying the user_not_found code`() =
        runTest {
            val api =
                ProfileApiClient(
                    client { respond("""{"error":{"code":"user_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS) },
                )
            val result = assertIs<ProfileReadApiResult.HttpError>(api.getProfile("u1"))
            assertEquals(404, result.status)
            assertEquals("user_not_found", result.errorCode)
        }

    @Test
    fun `follow targets POST follows and maps 204 to NoContent`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                ProfileApiClient(
                    client { request ->
                        captured = request
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            assertEquals(ActionApiResult.NoContent, api.follow("u9"))
            assertEquals(HttpMethod.Post, captured!!.method)
            assertEquals("/api/v1/follows/u9", captured!!.url.encodedPath)
        }

    @Test
    fun `unfollow targets DELETE follows`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                ProfileApiClient(
                    client { request ->
                        captured = request
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            assertEquals(ActionApiResult.NoContent, api.unfollow("u9"))
            assertEquals(HttpMethod.Delete, captured!!.method)
            assertEquals("/api/v1/follows/u9", captured!!.url.encodedPath)
        }

    @Test
    fun `follow 429 carries the Retry-After seconds`() =
        runTest {
            val api =
                ProfileApiClient(
                    client {
                        respond(
                            """{"error":{"code":"rate_limited"}}""",
                            HttpStatusCode.TooManyRequests,
                            headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("42")),
                        )
                    },
                )
            val result = assertIs<ActionApiResult.HttpError>(api.follow("u9"))
            assertEquals(429, result.status)
            assertEquals(42L, result.retryAfterSeconds)
        }

    @Test
    fun `report sends the snake_case user-target body and omits a blank note`() =
        runTest {
            var captured: HttpRequestData? = null
            var body = ""
            val api =
                ProfileApiClient(
                    client { request ->
                        captured = request
                        body = request.body.bodyText()
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            assertEquals(ActionApiResult.NoContent, api.report("u9", reasonCategory = "harassment", reasonNote = "   "))
            assertEquals("/api/v1/reports", captured!!.url.encodedPath)
            assertTrue(body.contains("\"target_type\":\"user\""), body)
            assertTrue(body.contains("\"target_id\":\"u9\""), body)
            assertTrue(body.contains("\"reason_category\":\"harassment\""), body)
            // A blank note is normalized to null → the key is OMITTED (not "" / null).
            assertTrue(!body.contains("reason_note"), "blank note must be omitted: $body")
        }

    @Test
    fun `report includes a non-blank note`() =
        runTest {
            var body = ""
            val api =
                ProfileApiClient(
                    client { request ->
                        body = request.body.bodyText()
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            api.report("u9", reasonCategory = "spam", reasonNote = "kasar sekali")
            assertTrue(body.contains("\"reason_note\":\"kasar sekali\""), body)
        }

    @Test
    fun `report 409 carries the duplicate_report code`() =
        runTest {
            val api =
                ProfileApiClient(
                    client { respond("""{"error":{"code":"duplicate_report"}}""", HttpStatusCode.Conflict, JSON_HEADERS) },
                )
            val result = assertIs<ActionApiResult.HttpError>(api.report("u9", "spam", null))
            assertEquals(409, result.status)
            assertEquals("duplicate_report", result.errorCode)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = ProfileApiClient(client { throw RuntimeException("io") })
            assertIs<ProfileReadApiResult.NetworkError>(api.getProfile("u1"))
            assertIs<ActionApiResult.NetworkError>(api.follow("u1"))
        }

    @Test
    fun `cancellation is rethrown - never mapped to NetworkError`() =
        runTest {
            val api = ProfileApiClient(client { throw CancellationException("cancelled") })
            assertFailsWith<CancellationException> { api.getProfile("u1") }
            assertFailsWith<CancellationException> { api.follow("u1") }
        }
}
