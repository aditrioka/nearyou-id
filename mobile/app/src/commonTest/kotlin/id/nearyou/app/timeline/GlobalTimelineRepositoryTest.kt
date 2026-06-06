package id.nearyou.app.timeline

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
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

private fun postJson(id: String): String =
    """{"id":"$id","authorUserId":"a-$id","content":"c","latitude":-6.2,"longitude":106.8,""" +
        """"city_name":"Jakarta","createdAt":"2026-05-31T10:00:00Z","liked_by_viewer":false,"reply_count":0}"""

/**
 * MockEngine-backed coverage of [GlobalTimelineRepository] (8.3): the HTTP status →
 * [GlobalTimelineOutcome] mapping with no generic fallthrough (design D4), and the first-page request
 * shape (NO spatial params — Global has no location filter) wired from [SessionIdProvider].
 */
class GlobalTimelineRepositoryTest {
    private fun repository(
        log: (String) -> Unit = {},
        handler: MockRequestHandler,
    ): GlobalTimelineRepository {
        val httpClient =
            HttpClientFactory.create(
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return GlobalTimelineRepository(
            apiClient = GlobalTimelineApiClient(httpClient),
            sessionIdProvider = SessionIdProvider(),
            diagnosticLog = log,
        )
    }

    @Test
    fun `first-page request targets global with no spatial params and a well-formed session header`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repository { request ->
                    captured = request
                    respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                }
            repo.loadFirstPage()

            val req = requireNotNull(captured)
            assertEquals("/api/v1/timeline/global", req.url.encodedPath)
            assertFalse(req.url.parameters.contains("lat"), "Global has no spatial filter")
            assertFalse(req.url.parameters.contains("lng"), "Global has no spatial filter")
            assertFalse(req.url.parameters.contains("radius_m"), "Global has no spatial filter")
            assertFalse(req.url.parameters.contains("cursor"), "first page omits cursor")
            assertTrue(Regex("^[A-Za-z0-9-]{1,64}$").matches(requireNotNull(req.headers["X-Session-Id"])))
        }

    @Test
    fun `200 maps to Loaded carrying posts cursor and parsed upsell`() =
        runTest {
            val body = """{"posts":[${postJson("p1")},${postJson("p2")},${postJson("p3")}],"nextCursor":"tok","upsell":{"soft":true}}"""
            val repo = repository { respond(body, HttpStatusCode.OK, JSON_HEADERS) }

            val outcome = repo.loadFirstPage()
            assertTrue(outcome is GlobalTimelineOutcome.Loaded)
            assertEquals(3, outcome.posts.size)
            assertEquals("tok", outcome.nextCursor)
            assertEquals(true, outcome.upsell?.soft)
        }

    @Test
    fun `hard-cap 200 empty plus upsell hard maps to Loaded NOT Error`() =
        runTest {
            val repo = repository { respond("""{"posts":[],"nextCursor":null,"upsell":{"hard":true}}""", HttpStatusCode.OK, JSON_HEADERS) }

            val outcome = repo.loadFirstPage()
            assertTrue(outcome is GlobalTimelineOutcome.Loaded, "hard cap is a 200, not an error outcome")
            assertTrue(outcome.posts.isEmpty())
            assertEquals(true, outcome.upsell?.hard)
        }

    @Test
    fun `5xx maps to NetworkError`() =
        runTest {
            val repo = repository { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(GlobalTimelineOutcome.NetworkError, repo.loadFirstPage())
        }

    @Test
    fun `transport IO failure maps to NetworkError`() =
        runTest {
            val repo = repository { throw RuntimeException("connection refused") }
            assertEquals(GlobalTimelineOutcome.NetworkError, repo.loadFirstPage())
        }

    @Test
    fun `400 maps to retryable Error with a logged diagnostic`() =
        runTest {
            val logs = mutableListOf<String>()
            val repo =
                repository(log = { logs.add(it) }) {
                    respond("""{"error":{"code":"invalid_cursor"}}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                }

            assertEquals(GlobalTimelineOutcome.Error, repo.loadFirstPage())
            assertTrue(logs.any { it.contains("400") }, "a diagnostic must be emitted on 400 (not a silent no-op): $logs")
        }
}
