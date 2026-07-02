package id.nearyou.app.privateprofile

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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** Reads a serialized request body — `TextContent` (what ContentNegotiation emits) extends ByteArrayContent. */
private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/** MockEngine-backed coverage of [PrivateProfileApiClient]: GET state parse + PATCH request/mapping. */
class PrivateProfileApiClientTest {
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
    fun `fetchState parses privateProfile and premium`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                PrivateProfileApiClient(
                    client { request ->
                        captured = request
                        respond("""{"privateProfile":true,"premium":true}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )

            val result = api.fetchState()

            assertEquals("/api/v1/user/private-profile", requireNotNull(captured).url.encodedPath)
            assertEquals(HttpMethod.Get, requireNotNull(captured).method)
            assertEquals(PrivateProfileStateResult.Success(privateProfile = true, premium = true), result)
        }

    @Test
    fun `fetchState maps a 401 to Failure`() =
        runTest {
            val api = PrivateProfileApiClient(client { respond("", HttpStatusCode.Unauthorized) })
            assertEquals(PrivateProfileStateResult.Failure, api.fetchState())
        }

    @Test
    fun `fetchState maps a 5xx to Failure`() =
        runTest {
            val api = PrivateProfileApiClient(client { respond("", HttpStatusCode.InternalServerError) })
            assertEquals(PrivateProfileStateResult.Failure, api.fetchState())
        }

    @Test
    fun `setPrivateProfile issues a single PATCH carrying the value and maps 200 to Success`() =
        runTest {
            var captured: HttpRequestData? = null
            var callCount = 0
            val api =
                PrivateProfileApiClient(
                    client { request ->
                        captured = request
                        callCount++
                        respond("""{"privateProfile":true}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )

            val result = api.setPrivateProfile(true)

            val req = requireNotNull(captured)
            assertEquals("/api/v1/user/private-profile", req.url.encodedPath)
            assertEquals(HttpMethod.Patch, req.method)
            assertTrue(req.body.bodyText().contains("\"privateProfile\":true"))
            assertEquals(1, callCount, "exactly one PATCH issued")
            assertEquals(PrivateProfileWriteResult.Success, result)
        }

    @Test
    fun `setPrivateProfile records the false value on opt-out`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                PrivateProfileApiClient(
                    client { request ->
                        captured = request
                        respond("""{"privateProfile":false}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )

            api.setPrivateProfile(false)

            assertTrue(requireNotNull(captured).body.bodyText().contains("\"privateProfile\":false"))
        }

    @Test
    fun `setPrivateProfile maps a 401 to Failure`() =
        runTest {
            val api = PrivateProfileApiClient(client { respond("", HttpStatusCode.Unauthorized) })
            assertEquals(PrivateProfileWriteResult.Failure, api.setPrivateProfile(true))
        }

    @Test
    fun `setPrivateProfile maps a 5xx to Failure`() =
        runTest {
            val api = PrivateProfileApiClient(client { respond("", HttpStatusCode.InternalServerError) })
            assertEquals(PrivateProfileWriteResult.Failure, api.setPrivateProfile(true))
        }

    @Test
    fun `fetchState rethrows CancellationException rather than swallowing it`() =
        runTest {
            val api = PrivateProfileApiClient(client { throw CancellationException("cancelled") })
            assertFailsWith<CancellationException> { api.fetchState() }
        }

    @Test
    fun `setPrivateProfile rethrows CancellationException rather than swallowing it`() =
        runTest {
            val api = PrivateProfileApiClient(client { throw CancellationException("cancelled") })
            assertFailsWith<CancellationException> { api.setPrivateProfile(true) }
        }
}
