package id.nearyou.app.hidedistance

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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** Reads a serialized request body — `TextContent` (what ContentNegotiation emits) extends ByteArrayContent. */
private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/** MockEngine-backed coverage of [HideDistanceApiClient]: GET state parse + PATCH request/mapping. */
class HideDistanceApiClientTest {
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
    fun `fetchState parses hideDistance and premium`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                HideDistanceApiClient(
                    client { request ->
                        captured = request
                        respond("""{"hideDistance":true,"premium":true}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )

            val result = api.fetchState()

            assertEquals("/api/v1/user/hide-distance", requireNotNull(captured).url.encodedPath)
            assertEquals(HttpMethod.Get, requireNotNull(captured).method)
            assertEquals(HideDistanceStateResult.Success(hideDistance = true, premium = true), result)
        }

    @Test
    fun `fetchState maps a non-2xx to Failure`() =
        runTest {
            val api = HideDistanceApiClient(client { respond("", HttpStatusCode.InternalServerError) })
            assertEquals(HideDistanceStateResult.Failure, api.fetchState())
        }

    @Test
    fun `setHideDistance issues a single PATCH carrying the value and maps 200 to Success`() =
        runTest {
            var captured: HttpRequestData? = null
            var callCount = 0
            val api =
                HideDistanceApiClient(
                    client { request ->
                        captured = request
                        callCount++
                        respond("""{"hideDistance":true}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )

            val result = api.setHideDistance(true)

            val req = requireNotNull(captured)
            assertEquals("/api/v1/user/hide-distance", req.url.encodedPath)
            assertEquals(HttpMethod.Patch, req.method)
            assertTrue(req.body.bodyText().contains("\"hideDistance\":true"))
            assertEquals(1, callCount, "exactly one PATCH issued")
            assertEquals(HideDistanceWriteResult.Success, result)
        }

    @Test
    fun `setHideDistance maps a non-2xx to Failure`() =
        runTest {
            val api = HideDistanceApiClient(client { respond("", HttpStatusCode.InternalServerError) })
            assertEquals(HideDistanceWriteResult.Failure, api.setHideDistance(true))
        }
}
