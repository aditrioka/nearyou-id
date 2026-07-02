package id.nearyou.app.data.block

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val JSON = headersOf("Content-Type", "application/json")

/**
 * Unit coverage of the shared [BlockSubmitter] (the single block-create status→[BlockOutcome] mapping,
 * relocated from `ProfileRepository.block` — mobile-block-from-content D2), driven over a MockEngine
 * (mirrors `ReportSubmitterTest`): 204 → Blocked; 429 → RateLimited(retryAfter, 0 when absent);
 * 5xx/transport/other → NetworkError. Also captures the outbound request so the
 * `POST /api/v1/blocks/{userId}` shape is asserted.
 */
class BlockSubmitterTest {
    private fun submitter(handler: MockRequestHandler): BlockSubmitter {
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
        return BlockSubmitter(client)
    }

    @Test
    fun `204 maps to Blocked and posts to the blocks path`() =
        runTest {
            var method: HttpMethod? = null
            var path = ""
            val outcome =
                submitter { request ->
                    method = request.method
                    path = request.url.encodedPath
                    respond("", HttpStatusCode.NoContent)
                }.submit("11111111-1111-1111-1111-111111111111")
            assertEquals(BlockOutcome.Blocked, outcome)
            assertEquals(HttpMethod.Post, method)
            assertEquals("/api/v1/blocks/11111111-1111-1111-1111-111111111111", path)
        }

    @Test
    fun `429 maps to RateLimited with the retry-after`() =
        runTest {
            val outcome =
                submitter {
                    respond(
                        """{"error":{"code":"rate_limited"}}""",
                        HttpStatusCode.TooManyRequests,
                        headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("90")),
                    )
                }.submit("u1")
            assertEquals(BlockOutcome.RateLimited(90L), outcome)
        }

    @Test
    fun `429 with no Retry-After maps to RateLimited zero`() =
        runTest {
            val outcome = submitter { respond("", HttpStatusCode.TooManyRequests) }.submit("u1")
            assertEquals(BlockOutcome.RateLimited(0L), outcome)
        }

    @Test
    fun `5xx maps to NetworkError`() =
        runTest {
            val outcome = submitter { respond("", HttpStatusCode.InternalServerError) }.submit("u1")
            assertEquals(BlockOutcome.NetworkError, outcome)
        }

    @Test
    fun `an unreachable-from-UI 404 maps to NetworkError`() =
        runTest {
            val outcome =
                submitter { respond("""{"error":{"code":"user_not_found"}}""", HttpStatusCode.NotFound, JSON) }
                    .submit("u1")
            assertEquals(BlockOutcome.NetworkError, outcome)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val outcome = submitter { throw RuntimeException("connection reset") }.submit("u1")
            assertEquals(BlockOutcome.NetworkError, outcome)
        }

    @Test
    fun `cancellation is rethrown - never mapped to NetworkError`() =
        runTest {
            kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
                submitter { throw kotlinx.coroutines.CancellationException("cancelled") }.submit("u1")
            }
        }
}
