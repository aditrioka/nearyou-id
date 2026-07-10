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

    // The report path moved to the shared `data/report/` seam (ReportApiClient / ReportSubmitter —
    // mobile-content-report). Its wire-body + duplicate_report + 429 + cancellation coverage now lives in
    // `ReportSubmitterTest` / `ReportApiClientTest`; `ProfileApiClient` no longer exposes `report`.

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
            assertFailsWith<CancellationException> { api.unfollow("u1") }
            // block moved to the shared data/block/BlockSubmitter seam (mobile-block-from-content D2);
            // its cancellation-rethrow coverage lives in BlockSubmitterTest.
        }
}
