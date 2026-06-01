package id.nearyou.app.network

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.timeline.NearbyTimelineApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.Logger
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Captures every log line so the coordinate-masking scenario can assert against emitted output. */
private class CapturingLogger : Logger {
    val messages = mutableListOf<String>()

    override fun log(message: String) {
        messages.add(message)
    }
}

/**
 * Coverage of `mobile-location` § "Coordinate query parameters are masked in HTTP-client logs"
 * (task 8.5). The Nearby fetch sends the coordinate as `lat`/`lng` URL query params, and the shared
 * `HttpClient` logs the request line (incl. the query string) at `LogLevel.HEADERS` in debug builds —
 * so without masking the now-real coordinate would leak to logs. Asserts the captured log output does
 * NOT contain the latitude/longitude values, while non-coordinate params (`radius_m`) stay visible.
 */
class HttpClientCoordinateMaskTest {
    @Test
    fun `nearby request log line masks the lat and lng query-param values`() =
        runTest {
            val capturing = CapturingLogger()
            val store = InMemoryTokenStore()
            val client =
                HttpClientFactory.create(
                    apiBaseUrl = "http://test.local",
                    tokenStore = store,
                    sessionInvalidator = SessionInvalidator(store),
                    engine =
                        MockEngine {
                            respond(
                                content = """{"posts":[]}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    installLogging = true,
                    logger = capturing,
                    nowMillis = { 0L },
                )

            NearbyTimelineApiClient(client).fetchNearby(
                lat = -6.275123,
                lng = 106.812456,
                radiusM = 20_000,
                sessionId = "session-1",
            )

            val joined = capturing.messages.joinToString("\n")
            assertFalse(joined.contains("-6.275123"), "latitude value must be masked in the log line:\n$joined")
            assertFalse(joined.contains("106.812456"), "longitude value must be masked in the log line:\n$joined")
            assertTrue(joined.contains("***"), "coordinate query-param values must be masked to ***:\n$joined")
            // radius_m is not a coordinate — the mask is scoped, not blanket, so it stays visible.
            assertTrue(joined.contains("radius_m=20000"), "non-coordinate params must stay visible:\n$joined")
        }
}
