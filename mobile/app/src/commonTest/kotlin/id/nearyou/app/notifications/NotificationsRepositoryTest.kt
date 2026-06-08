package id.nearyou.app.notifications

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

private fun itemJson(id: String): String =
    """{"id":"$id","type":"post_liked","actor_user_id":"a-$id","target_type":"post","target_id":"t-$id",""" +
        """"body_data":{"post_excerpt":"halo"},"created_at":"2026-05-31T10:00:00Z","read_at":null}"""

/**
 * MockEngine-backed coverage of [NotificationsRepository] (§7): the HTTP status → [NotificationsOutcome]
 * mapping with no generic fallthrough (design D8), the no-PII-in-diagnostic discipline, and the mark-read
 * / mark-all-read / unread-count pass-throughs.
 */
class NotificationsRepositoryTest {
    private fun repository(
        log: (String) -> Unit = {},
        handler: MockRequestHandler,
    ): NotificationsRepository {
        val httpClient =
            HttpClientFactory.create(
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return NotificationsRepository(apiClient = NotificationsApiClient(httpClient), diagnosticLog = log)
    }

    @Test
    fun `first-page request targets notifications with no cursor`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repository { request ->
                    captured = request
                    respond("""{"items":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                }
            repo.loadFirstPage()

            val req = requireNotNull(captured)
            assertEquals("/api/v1/notifications", req.url.encodedPath)
            assertFalse(req.url.parameters.contains("cursor"), "first page omits cursor")
        }

    @Test
    fun `200 maps to Loaded carrying items and cursor`() =
        runTest {
            val body = """{"items":[${itemJson("n1")},${itemJson("n2")},${itemJson("n3")}],"next_cursor":"tok"}"""
            val repo = repository { respond(body, HttpStatusCode.OK, JSON_HEADERS) }

            val outcome = repo.loadFirstPage()
            assertIs<NotificationsOutcome.Loaded>(outcome)
            assertEquals(3, outcome.items.size)
            assertEquals("tok", outcome.nextCursor)
        }

    @Test
    fun `5xx maps to NetworkError`() =
        runTest {
            val repo = repository { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(NotificationsOutcome.NetworkError, repo.loadFirstPage())
        }

    @Test
    fun `transport IO failure maps to NetworkError`() =
        runTest {
            val repo = repository { throw RuntimeException("connection refused") }
            assertEquals(NotificationsOutcome.NetworkError, repo.loadFirstPage())
        }

    @Test
    fun `400 maps to retryable Error with a logged diagnostic`() =
        runTest {
            val logs = mutableListOf<String>()
            val repo =
                repository(log = { logs.add(it) }) {
                    respond("""{"error":{"code":"invalid_cursor"}}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                }

            assertEquals(NotificationsOutcome.Error, repo.loadFirstPage())
            assertTrue(logs.any { it.contains("400") }, "a diagnostic must be emitted on 400 (not a silent no-op): $logs")
        }

    @Test
    fun `error diagnostics carry no PII or response body`() =
        runTest {
            val logs = mutableListOf<String>()
            // A 400 body deliberately stuffed with PII-shaped values + a stale key — none may be logged.
            val piiBody =
                """{"error":{"code":"invalid_cursor"},"actor_user_id":"PII-ACTOR-UUID",""" +
                    """"target_id":"PII-TARGET-UUID","body_data":{"post_excerpt":"SECRET"}}"""
            val repo = repository(log = { logs.add(it) }) { respond(piiBody, HttpStatusCode.BadRequest, JSON_HEADERS) }
            repo.loadFirstPage()

            val joined = logs.joinToString("\n")
            assertTrue(logs.isNotEmpty(), "a diagnostic is emitted")
            assertFalse(joined.contains("PII-ACTOR-UUID"), "no actor_user_id in the diagnostic: $joined")
            assertFalse(joined.contains("PII-TARGET-UUID"), "no target_id in the diagnostic: $joined")
            assertFalse(joined.contains("SECRET"), "no body_data / response body in the diagnostic: $joined")
            assertTrue(joined.contains("400"), "the diagnostic carries the HTTP status only")
        }

    @Test
    fun `markRead pass-through maps 204 to Acknowledged 404 to NotFound and 500 to Failed`() =
        runTest {
            assertEquals(
                MarkReadResult.Acknowledged,
                repository { respond("", HttpStatusCode.NoContent) }.markRead("n1"),
            )
            assertEquals(
                MarkReadResult.NotFound,
                repository {
                    respond("""{"error":{"code":"not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS)
                }.markRead("n1"),
            )
            assertEquals(
                MarkReadResult.Failed,
                repository { respond("", HttpStatusCode.InternalServerError) }.markRead("n1"),
            )
        }

    @Test
    fun `markAllRead pass-through parses marked_read`() =
        runTest {
            val outcome =
                repository { respond("""{"marked_read":2}""", HttpStatusCode.OK, JSON_HEADERS) }.markAllRead()
            assertEquals(2, assertIs<MarkAllReadResult.Success>(outcome).markedRead)
        }

    @Test
    fun `unreadCount pass-through returns the count or null`() =
        runTest {
            assertEquals(5L, repository { respond("""{"count":5}""", HttpStatusCode.OK, JSON_HEADERS) }.unreadCount())
            assertEquals(null, repository { respond("", HttpStatusCode.InternalServerError) }.unreadCount())
        }
}
