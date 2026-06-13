package id.nearyou.app.followlist

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/**
 * Exercises the full stack `FollowListRepository` → `FollowListApiClient` → MockEngine, so the HTTP-status
 * → [FollowListOutcome] mapping is verified end-to-end (mirrors `ProfileRepositoryTest`). The constant
 * `404` → the single [FollowListOutcome.NotFound]; `5xx` / `400 invalid_cursor` / transport → the retryable
 * [FollowListOutcome.NetworkError]; no generic fallthrough.
 */
class FollowListRepositoryTest {
    private fun repository(handler: MockRequestHandler): FollowListRepository {
        val client: HttpClient =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return FollowListRepository(FollowListApiClient(client))
    }

    @Test
    fun `200 maps to Loaded with mapped rows and nextCursor`() =
        runTest {
            val repo =
                repository {
                    respond(
                        """
                        {"users":[
                          {"userId":"u1","username":"raka.jkt","displayName":"Raka Pratama","isPremium":true,
                           "createdAt":"2026-06-01T00:00:00Z"}
                        ],"nextCursor":"NEXT"}
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        JSON_HEADERS,
                    )
                }
            val outcome = assertIs<FollowListOutcome.Loaded>(repo.load("p1", FollowListTab.Followers, null))
            assertEquals(1, outcome.page.users.size)
            assertEquals("raka.jkt", outcome.page.users[0].username)
            assertEquals("Raka Pratama", outcome.page.users[0].displayName)
            assertEquals(true, outcome.page.users[0].isPremium)
            assertEquals("NEXT", outcome.page.nextCursor)
        }

    @Test
    fun `last page has a null nextCursor`() =
        runTest {
            val repo = repository { respond("""{"users":[]}""", HttpStatusCode.OK, JSON_HEADERS) }
            val outcome = assertIs<FollowListOutcome.Loaded>(repo.load("p1", FollowListTab.Following, null))
            assertNull(outcome.page.nextCursor)
        }

    @Test
    fun `constant 404 maps to the single NotFound regardless of cause`() =
        runTest {
            val repo =
                repository { respond("""{"error":{"code":"user_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS) }
            assertEquals(FollowListOutcome.NotFound, repo.load("p1", FollowListTab.Followers, null))
            assertEquals(FollowListOutcome.NotFound, repo.load("p1", FollowListTab.Following, null))
        }

    @Test
    fun `5xx maps to NetworkError`() =
        runTest {
            val repo = repository { respond("", HttpStatusCode.ServiceUnavailable) }
            assertEquals(FollowListOutcome.NetworkError, repo.load("p1", FollowListTab.Followers, null))
        }

    @Test
    fun `400 invalid_cursor maps to NetworkError - unreachable from UI, non-actionable`() =
        runTest {
            val repo =
                repository { respond("""{"error":{"code":"invalid_cursor"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
            assertEquals(FollowListOutcome.NetworkError, repo.load("p1", FollowListTab.Followers, "bad"))
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val repo = repository { throw RuntimeException("io") }
            assertEquals(FollowListOutcome.NetworkError, repo.load("p1", FollowListTab.Followers, null))
        }
}
