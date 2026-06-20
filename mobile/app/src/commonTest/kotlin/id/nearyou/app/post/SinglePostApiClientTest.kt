package id.nearyou.app.post

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

// The deployed SinglePostResponse wire: bare camelCase id/authorUsername/authorDisplayName/content/createdAt,
// and @SerialName snake_case city_name / liked_by_viewer / reply_count (verified vs SinglePostRoutes.kt).
private const val MIXED_CASE_BODY =
    """{"id":"p1","authorUsername":"budi","authorDisplayName":"Budi","content":"halo dunia",""" +
        """"createdAt":"2026-05-31T10:00:00Z","city_name":"Jakarta","liked_by_viewer":true,"reply_count":5}"""

// The WRONG shape: the three snake fields written as bare camelCase keys. With @SerialName snake the
// camelCase keys are ignored (ignoreUnknownKeys) and the required snake keys are absent → the parse fails.
private const val ALL_CAMEL_CASE_BODY =
    """{"id":"p1","authorUsername":"budi","authorDisplayName":"Budi","content":"halo dunia",""" +
        """"createdAt":"2026-05-31T10:00:00Z","cityName":"Jakarta","likedByViewer":true,"replyCount":5}"""

/**
 * MockEngine-backed coverage of [SinglePostApiClient.fetchFullPost] (the `single-post-read` full projection
 * the notification deep-link consumes, `mobile-notifications-deep-link-targets` task 6.1): the
 * `GET /api/v1/posts/{id}` path + method, the deployed MIXED-case parse (the three `@SerialName` snake
 * fields), the all-camelCase regression guard (the snake fields do NOT bind from camelCase keys → the parse
 * fails → `Unavailable`), the `404` / `5xx` / transport → `Unavailable` mapping, cancellation rethrow, and
 * that the minimal [SinglePostApiClient.fetchPost] projection is undisturbed.
 */
class SinglePostApiClientTest {
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
    fun `fetchFullPost targets GET api v1 posts id and parses the mixed-case wire`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                SinglePostApiClient(
                    client { request ->
                        captured = request
                        respond(MIXED_CASE_BODY, HttpStatusCode.OK, JSON_HEADERS)
                    },
                )

            val result = api.fetchFullPost("p1")

            val req = requireNotNull(captured)
            assertEquals(HttpMethod.Get, req.method)
            assertEquals("/api/v1/posts/p1", req.url.encodedPath)
            assertTrue(result is SinglePostFullResult.Success, "expected Success, got $result")
            val post = result.post
            assertEquals("p1", post.id)
            assertEquals("budi", post.authorUsername)
            assertEquals("Budi", post.authorDisplayName)
            assertEquals("halo dunia", post.content)
            assertEquals("2026-05-31T10:00:00Z", post.createdAt)
            // The three snake_case fields bound from their @SerialName keys (the load-bearing assertion).
            assertEquals("Jakarta", post.cityName)
            assertTrue(post.likedByViewer)
            assertEquals(5, post.replyCount)
        }

    @Test
    fun `fetchFullPost does NOT bind the snake fields from all-camelCase keys (regression guard)`() =
        runTest {
            val api = SinglePostApiClient(client { respond(ALL_CAMEL_CASE_BODY, HttpStatusCode.OK, JSON_HEADERS) })

            // A regression to an all-camelCase DTO would parse this body to a Success with cityName=""/
            // likedByViewer=false/replyCount=0; the required snake keys being absent makes the parse fail →
            // Unavailable. This is what stops the silent-mis-parse bug from reappearing.
            assertEquals(SinglePostFullResult.Unavailable, api.fetchFullPost("p1"))
        }

    @Test
    fun `fetchFullPost maps 404 to Unavailable`() =
        runTest {
            val api = SinglePostApiClient(client { respondError(HttpStatusCode.NotFound) })
            assertEquals(SinglePostFullResult.Unavailable, api.fetchFullPost("p1"))
        }

    @Test
    fun `fetchFullPost maps 500 to Unavailable`() =
        runTest {
            val api = SinglePostApiClient(client { respondError(HttpStatusCode.InternalServerError) })
            assertEquals(SinglePostFullResult.Unavailable, api.fetchFullPost("p1"))
        }

    @Test
    fun `fetchFullPost maps a malformed 200 body to Unavailable`() =
        runTest {
            val api = SinglePostApiClient(client { respond("not json", HttpStatusCode.OK, JSON_HEADERS) })
            assertEquals(SinglePostFullResult.Unavailable, api.fetchFullPost("p1"))
        }

    @Test
    fun `cancellation mid-fetchFullPost propagates rather than mapping to Unavailable`() =
        runTest {
            val api =
                SinglePostApiClient(
                    client {
                        delay(60_000) // never completes within the job's lifetime
                        respond(MIXED_CASE_BODY, HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            var completed = false
            val job =
                launch {
                    api.fetchFullPost("p1")
                    completed = true
                }
            delay(100)
            job.cancel()
            job.join()

            assertTrue(job.isCancelled, "the in-flight fetchFullPost job is cancelled, not hung")
            assertFalse(completed, "fetchFullPost did not silently complete with Unavailable after cancellation")
        }

    @Test
    fun `the minimal fetchPost projection is undisturbed`() =
        runTest {
            // The post-detail freshness fetch still parses its minimal projection (content / editedAt /
            // isAuthor) — the full-projection addition did not change it.
            val api =
                SinglePostApiClient(
                    client {
                        respond(
                            """{"content":"teks baru","editedAt":"2026-06-01T00:00:00Z","isAuthor":true}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )
            val result = api.fetchPost("p1")
            assertTrue(result is SinglePostApiResult.Success, "expected Success, got $result")
            assertEquals("teks baru", result.post.content)
            assertEquals("2026-06-01T00:00:00Z", result.post.editedAt)
            assertTrue(result.post.isAuthor)
        }
}
