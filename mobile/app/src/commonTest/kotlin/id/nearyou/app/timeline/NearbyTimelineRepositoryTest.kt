package id.nearyou.app.timeline

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.distance.LatLng
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** The page-1 outcome of a NON-gated fetch (every case here except the radius_premium_only ones). */
private suspend fun NearbyTimelineRepository.firstPageOutcome(): NearbyTimelineOutcome =
    assertIs<NearbyFetchResult.Loaded>(loadFirstPage()).outcome

private fun postJson(id: String): String =
    """{"id":"$id","authorUserId":"a-$id","authorUsername":"raka.jkt","authorDisplayName":"Raka Pratama",""" +
        """"content":"c","latitude":-6.2,"longitude":106.8,""" +
        """"distanceM":10.0,"city_name":"Jakarta","createdAt":"2026-05-31T10:00:00Z","liked_by_viewer":false,"reply_count":0}"""

/**
 * MockEngine-backed coverage of [NearbyTimelineRepository] (8.2): the HTTP status → [NearbyTimelineOutcome]
 * mapping with no generic fallthrough (design D6), and the first-page request shape wired from
 * [StubLocationProvider] + [SessionIdProvider] + [NEARBY_RADIUS_M].
 */
class NearbyTimelineRepositoryTest {
    private fun repository(
        tokenStore: TokenStore = InMemoryTokenStore(),
        log: (String) -> Unit = {},
        handler: MockRequestHandler,
    ): NearbyTimelineRepository {
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
        return NearbyTimelineRepository(
            apiClient = NearbyTimelineApiClient(httpClient),
            locationProvider = StubLocationProvider(),
            sessionIdProvider = SessionIdProvider(),
            diagnosticLog = log,
        )
    }

    @Test
    fun `first-page request uses the stub coordinate the fixed radius and a well-formed session header`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repository { request ->
                    captured = request
                    respond("""{"posts":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                }
            repo.loadFirstPage()

            val req = requireNotNull(captured)
            assertEquals("/api/v1/timeline/nearby", req.url.encodedPath)
            assertEquals("-6.2", req.url.parameters["lat"], "from StubLocationProvider")
            assertEquals("106.8", req.url.parameters["lng"], "from StubLocationProvider")
            assertEquals("20000", req.url.parameters["radius_m"], "NEARBY_RADIUS_M, the Free fixed radius")
            assertFalse(req.url.parameters.contains("cursor"), "first page omits cursor")
            assertTrue(Regex("^[A-Za-z0-9-]{1,64}$").matches(requireNotNull(req.headers["X-Session-Id"])))
        }

    @Test
    fun `200 maps to Loaded carrying posts cursor and parsed upsell`() =
        runTest {
            val body = """{"posts":[${postJson("p1")},${postJson("p2")},${postJson("p3")}],"nextCursor":"tok","upsell":{"soft":true}}"""
            val repo = repository { respond(body, HttpStatusCode.OK, JSON_HEADERS) }

            val outcome = repo.firstPageOutcome()
            assertTrue(outcome is NearbyTimelineOutcome.Loaded)
            assertEquals(3, outcome.posts.size)
            assertEquals("tok", outcome.nextCursor)
            assertEquals(true, outcome.upsell?.soft)
        }

    @Test
    fun `hard-cap 200 empty plus upsell hard maps to Loaded NOT Error`() =
        runTest {
            val repo = repository { respond("""{"posts":[],"nextCursor":null,"upsell":{"hard":true}}""", HttpStatusCode.OK, JSON_HEADERS) }

            val outcome = repo.firstPageOutcome()
            assertTrue(outcome is NearbyTimelineOutcome.Loaded, "hard cap is a 200, not an error outcome")
            assertTrue(outcome.posts.isEmpty())
            assertEquals(true, outcome.upsell?.hard)
        }

    @Test
    fun `5xx maps to NetworkError not SessionExpired`() =
        runTest {
            val repo = repository { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(NearbyTimelineOutcome.NetworkError, repo.firstPageOutcome())
        }

    @Test
    fun `transport IO failure maps to NetworkError`() =
        runTest {
            // A genuine connectivity fault keeps the NetworkError (connectivity copy), distinct from a
            // terminal 401 — even with a token present, so the path is the IOException branch, not 401.
            val repo =
                repository(tokenStore = InMemoryTokenStore(TokenPair("at", "rt", 1L))) {
                    throw RuntimeException("connection refused")
                }
            assertEquals(NearbyTimelineOutcome.NetworkError, repo.firstPageOutcome())
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

            // SessionExpired is a distinct data object, so this also asserts it is NOT NetworkError/Error.
            assertEquals(NearbyTimelineOutcome.SessionExpired, repo.firstPageOutcome())
        }

    @Test
    fun `400 maps to retryable Error with a logged diagnostic`() =
        runTest {
            val logs = mutableListOf<String>()
            val repo =
                repository(log = { logs.add(it) }) {
                    respond("""{"error":{"code":"invalid_request"}}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                }

            assertEquals(NearbyTimelineOutcome.Error, repo.firstPageOutcome())
            assertTrue(logs.any { it.contains("400") }, "a diagnostic must be emitted on 400 (not a silent no-op): $logs")
        }

    // ---- #518: ONE radius_premium_only mapping for every Nearby fetch (mobile-nearby-radius-slider) ----

    private fun gate403(code: String) =
        repository {
            respond("""{"error":{"code":"$code","message":"requires Premium"}}""", HttpStatusCode.Forbidden, JSON_HEADERS)
        }

    @Test
    fun `a radius_premium_only 403 is PremiumGated from loadFirstPage`() =
        runTest {
            assertEquals(NearbyFetchResult.PremiumGated, gate403("radius_premium_only").loadFirstPage(50_000))
        }

    @Test
    fun `a radius_premium_only 403 is PremiumGated from loadMore too`() =
        runTest {
            assertEquals(
                NearbyFetchResult.PremiumGated,
                gate403("radius_premium_only").loadMore("c1", LatLng(-6.2, 106.8), 50_000),
            )
        }

    @Test
    fun `any other 403 keeps the frozen mapping - Loaded wrapping NetworkError`() =
        runTest {
            val repo = gate403("forbidden")
            assertEquals(NearbyFetchResult.Loaded(NearbyTimelineOutcome.NetworkError), repo.loadFirstPage(50_000))
            assertEquals(
                NearbyFetchResult.Loaded(NearbyTimelineOutcome.NetworkError),
                repo.loadMore("c1", LatLng(-6.2, 106.8), 50_000),
            )
        }
}
