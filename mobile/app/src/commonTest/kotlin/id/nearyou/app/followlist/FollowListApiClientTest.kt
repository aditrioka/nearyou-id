package id.nearyou.app.followlist

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

class FollowListApiClientTest {
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
    fun `fetch parses the shipped camelCase page wire and hits the followers path`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                FollowListApiClient(
                    client { request ->
                        captured = request
                        respond(
                            """
                            {"users":[
                              {"userId":"u1","username":"raka.jkt","displayName":"Raka Pratama","isPremium":false,
                               "createdAt":"2026-06-01T00:00:00Z"},
                              {"userId":"u2","username":"sari.bdg","displayName":"Sari Lestari","isPremium":true,
                               "createdAt":"2026-05-31T00:00:00Z"}
                            ],"nextCursor":"eyJjIjoiYSJ9"}
                            """.trimIndent(),
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )
            val result = assertIs<FollowListApiResult.Success>(api.fetch("p1", FollowListTab.Followers, cursor = null))
            assertEquals(HttpMethod.Get, captured!!.method)
            assertEquals("/api/v1/users/p1/followers", captured!!.url.encodedPath)
            assertEquals(2, result.body.users.size)
            assertEquals("raka.jkt", result.body.users[0].username)
            assertEquals("Raka Pratama", result.body.users[0].displayName)
            assertTrue(result.body.users[1].isPremium)
            assertEquals("eyJjIjoiYSJ9", result.body.nextCursor)
        }

    @Test
    fun `fetch following hits the following path and forwards the cursor`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                FollowListApiClient(
                    client { request ->
                        captured = request
                        respond("""{"users":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetch("p1", FollowListTab.Following, cursor = "CUR")
            assertEquals("/api/v1/users/p1/following", captured!!.url.encodedPath)
            assertEquals("CUR", captured!!.url.parameters["cursor"])
        }

    @Test
    fun `fetch omits the cursor param on the first page`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                FollowListApiClient(
                    client { request ->
                        captured = request
                        respond("""{"users":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetch("p1", FollowListTab.Followers, cursor = null)
            assertNull(captured!!.url.parameters["cursor"], "first page sends no cursor param")
        }

    @Test
    fun `fetch tolerates an omitted nextCursor - last page decodes to null`() =
        runTest {
            val api =
                FollowListApiClient(
                    client {
                        respond(
                            """{"users":[{"userId":"u1","username":"r","displayName":"R","isPremium":false,
                               "createdAt":"2026-06-01T00:00:00Z"}]}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )
            val result = assertIs<FollowListApiResult.Success>(api.fetch("p1", FollowListTab.Followers, null))
            assertNull(result.body.nextCursor, "absent nextCursor must decode to null (explicitNulls = false)")
        }

    @Test
    fun `fetch does not bind a snake_case body - casing-drift negative guard`() =
        runTest {
            val api =
                FollowListApiClient(
                    client {
                        // snake_case keys (a stale-spec JSON shape) — the required camelCase fields are
                        // missing, so the parse fails → NetworkError, never a Success with bound fields.
                        respond(
                            """{"users":[{"user_id":"u1","display_name":"R","is_premium":false}],"next_cursor":"x"}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )
            assertIs<FollowListApiResult.NetworkError>(api.fetch("p1", FollowListTab.Followers, null))
        }

    @Test
    fun `fetch maps a 404 to HttpError carrying the status`() =
        runTest {
            val api =
                FollowListApiClient(
                    client { respond("""{"error":{"code":"user_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS) },
                )
            val result = assertIs<FollowListApiResult.HttpError>(api.fetch("p1", FollowListTab.Followers, null))
            assertEquals(404, result.status)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = FollowListApiClient(client { throw RuntimeException("io") })
            assertIs<FollowListApiResult.NetworkError>(api.fetch("p1", FollowListTab.Followers, null))
        }

    @Test
    fun `cancellation is rethrown - never mapped to NetworkError`() =
        runTest {
            val api = FollowListApiClient(client { throw CancellationException("cancelled") })
            assertFailsWith<CancellationException> { api.fetch("p1", FollowListTab.Followers, null) }
            assertFailsWith<CancellationException> { api.fetch("p1", FollowListTab.Following, null) }
        }
}
