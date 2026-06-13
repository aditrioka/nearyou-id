package id.nearyou.app.timeline

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

private fun postJson(id: String): String =
    """{"id":"$id","authorUserId":"a-$id","authorUsername":"fajar.r","authorDisplayName":"Fajar Ramadhan",""" +
        """"content":"c","latitude":-6.2,"longitude":106.8,""" +
        """"city_name":"Jakarta","createdAt":"2026-06-13T10:00:00Z","liked_by_viewer":false,"reply_count":0}"""

/**
 * MockEngine-backed coverage of [FollowingTimelineRepository] (tasks 6.3): the HTTP status →
 * [FollowingTimelineOutcome] mapping with no generic fallthrough (design D1), the first-page request
 * shape (NO spatial params — Following has no location filter) wired from [SessionIdProvider], and the
 * coord-safe 400 diagnostic (status-only — Following coords live in the response body). Mirrors
 * `GlobalTimelineRepositoryTest`.
 */
class FollowingTimelineRepositoryTest {
    private fun repository(
        tokenStore: TokenStore = InMemoryTokenStore(),
        log: (String) -> Unit = {},
        handler: MockRequestHandler,
    ): FollowingTimelineRepository {
        val httpClient =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = tokenStore,
                // Same store so a refresh-failure invalidate() clears the very tokens loadTokens reads.
                sessionInvalidator = SessionInvalidator(tokenStore),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return FollowingTimelineRepository(
            apiClient = FollowingTimelineApiClient(httpClient),
            sessionIdProvider = SessionIdProvider(),
            diagnosticLog = log,
        )
    }

    @Test
    fun `first-page request targets following with no spatial params and a well-formed session header`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repository { request ->
                    captured = request
                    respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                }
            repo.loadFirstPage()

            val req = requireNotNull(captured)
            assertEquals("/api/v1/timeline/following", req.url.encodedPath)
            assertFalse(req.url.parameters.contains("lat"), "Following has no spatial filter")
            assertFalse(req.url.parameters.contains("lng"), "Following has no spatial filter")
            assertFalse(req.url.parameters.contains("radius_m"), "Following has no spatial filter")
            assertFalse(req.url.parameters.contains("cursor"), "first page omits cursor")
            assertTrue(Regex("^[A-Za-z0-9-]{1,64}$").matches(requireNotNull(req.headers["X-Session-Id"])))
        }

    @Test
    fun `200 maps to Loaded carrying posts cursor and parsed upsell`() =
        runTest {
            val body = """{"posts":[${postJson("p1")},${postJson("p2")},${postJson("p3")}],"nextCursor":"tok","upsell":{"soft":true}}"""
            val repo = repository { respond(body, HttpStatusCode.OK, JSON_HEADERS) }

            val outcome = repo.loadFirstPage()
            assertTrue(outcome is FollowingTimelineOutcome.Loaded)
            assertEquals(3, outcome.posts.size)
            assertEquals("tok", outcome.nextCursor)
            assertEquals(true, outcome.upsell?.soft)
        }

    @Test
    fun `hard-cap 200 empty plus upsell hard maps to Loaded NOT Error`() =
        runTest {
            val repo = repository { respond("""{"posts":[],"nextCursor":null,"upsell":{"hard":true}}""", HttpStatusCode.OK, JSON_HEADERS) }

            val outcome = repo.loadFirstPage()
            assertTrue(outcome is FollowingTimelineOutcome.Loaded, "hard cap is a 200, not an error outcome")
            assertTrue(outcome.posts.isEmpty())
            assertEquals(true, outcome.upsell?.hard)
        }

    @Test
    fun `empty-follows result maps to Loaded with empty posts`() =
        runTest {
            // The caller follows nobody → HTTP 200 posts=[] (the directive empty state at the screen).
            val repo = repository { respond("""{"posts":[],"nextCursor":null}""", HttpStatusCode.OK, JSON_HEADERS) }
            val outcome = repo.loadFirstPage()
            assertTrue(outcome is FollowingTimelineOutcome.Loaded)
            assertTrue(outcome.posts.isEmpty())
            assertEquals(null, outcome.upsell)
        }

    @Test
    fun `5xx maps to NetworkError not SessionExpired`() =
        runTest {
            val repo = repository { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(FollowingTimelineOutcome.NetworkError, repo.loadFirstPage())
        }

    @Test
    fun `transport IO failure maps to NetworkError`() =
        runTest {
            val repo =
                repository(tokenStore = InMemoryTokenStore(TokenPair("at", "rt", 1L))) {
                    throw RuntimeException("connection refused")
                }
            assertEquals(FollowingTimelineOutcome.NetworkError, repo.loadFirstPage())
        }

    @Test
    fun `terminal 401 maps to SessionExpired never NetworkError`() =
        runTest {
            // A token is present so the Auth plugin attempts a refresh; both the fetch AND the refresh
            // POST return 401 → terminal 401 → SessionExpired (NOT NetworkError, NOT Error).
            val store = InMemoryTokenStore(TokenPair("at-stale", "rt-Y", 1L))
            val repo =
                repository(tokenStore = store) { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/auth/refresh" ->
                            respond("", HttpStatusCode.Unauthorized, JSON_HEADERS)
                        else ->
                            respond(
                                "",
                                HttpStatusCode.Unauthorized,
                                headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"nearyou\""),
                            )
                    }
                }

            assertEquals(FollowingTimelineOutcome.SessionExpired, repo.loadFirstPage())
        }

    @Test
    fun `400 maps to retryable Error with a coord-safe status-only diagnostic`() =
        runTest {
            val logs = mutableListOf<String>()
            val repo =
                repository(log = { logs.add(it) }) {
                    respond("""{"error":{"code":"invalid_cursor"}}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                }

            assertEquals(FollowingTimelineOutcome.Error, repo.loadFirstPage())
            assertTrue(logs.any { it.contains("400") }, "a diagnostic must be emitted on 400 (not a silent no-op): $logs")
            // Coord-safe (security review): the diagnostic carries status only — never a coordinate or a
            // response-body field (Following's lat/lng arrive in the body, which LogLevel.HEADERS excludes).
            assertTrue(logs.none { it.contains("-6.2") || it.contains("106.8") }, "diagnostic must not echo a coordinate: $logs")
        }
}
