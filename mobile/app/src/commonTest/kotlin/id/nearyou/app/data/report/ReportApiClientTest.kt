package id.nearyou.app.data.report

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/**
 * Low-level coverage of the shared [ReportApiClient] (relocated from `ProfileApiClient.report`): the
 * snake_case body shape over the SHIPPED `POST /api/v1/reports`, blank-note normalization, the
 * status→[ReportApiResult] mapping (204 → NoContent; non-204 → HttpError with status + error.code +
 * Retry-After; transport → NetworkError), and `CancellationException` rethrow. Mirrors the (removed)
 * `ProfileApiClientTest` report tests at the new home.
 */
class ReportApiClientTest {
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
    fun `submit sends the snake_case body and omits a blank note`() =
        runTest {
            var path = ""
            var body = ""
            val api =
                ReportApiClient(
                    client { request ->
                        path = request.url.encodedPath
                        body = request.body.bodyText()
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            assertEquals(ReportApiResult.NoContent, api.submit("post", "p9", "harassment", "   "))
            assertEquals("/api/v1/reports", path)
            assertTrue(body.contains("\"target_type\":\"post\""), body)
            assertTrue(body.contains("\"target_id\":\"p9\""), body)
            assertTrue(body.contains("\"reason_category\":\"harassment\""), body)
            // A blank note is normalized to null → the key is OMITTED (not "" / null).
            assertTrue(!body.contains("reason_note"), "blank note must be omitted: $body")
        }

    @Test
    fun `submit includes a non-blank note`() =
        runTest {
            var body = ""
            val api =
                ReportApiClient(
                    client { request ->
                        body = request.body.bodyText()
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            api.submit("reply", "r9", "spam", "kasar sekali")
            assertTrue(body.contains("\"reason_note\":\"kasar sekali\""), body)
        }

    @Test
    fun `409 carries the duplicate_report code`() =
        runTest {
            val api =
                ReportApiClient(
                    client { respond("""{"error":{"code":"duplicate_report"}}""", HttpStatusCode.Conflict, JSON_HEADERS) },
                )
            val result = assertIs<ReportApiResult.HttpError>(api.submit("post", "p9", "spam", null))
            assertEquals(409, result.status)
            assertEquals("duplicate_report", result.errorCode)
        }

    @Test
    fun `429 carries the Retry-After seconds`() =
        runTest {
            val api =
                ReportApiClient(
                    client {
                        respond(
                            "",
                            HttpStatusCode.TooManyRequests,
                            headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("42")),
                        )
                    },
                )
            val result = assertIs<ReportApiResult.HttpError>(api.submit("post", "p9", "spam", null))
            assertEquals(429, result.status)
            assertEquals(42L, result.retryAfterSeconds)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = ReportApiClient(client { throw RuntimeException("io") })
            assertIs<ReportApiResult.NetworkError>(api.submit("post", "p9", "spam", null))
        }

    @Test
    fun `cancellation is rethrown - never mapped to NetworkError`() =
        runTest {
            val api = ReportApiClient(client { throw CancellationException("cancelled") })
            assertFailsWith<CancellationException> { api.submit("post", "p9", "spam", null) }
        }
}
