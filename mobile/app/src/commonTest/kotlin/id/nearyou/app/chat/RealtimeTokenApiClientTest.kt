package id.nearyou.app.chat

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/**
 * MockEngine coverage of [RealtimeTokenApiClient] (task 12.2): the `{ token, expires_in }` envelope
 * parses to `token` + `expiresIn: Long` (NOT a bare string), and the path is `/api/v1/realtime/token`.
 */
class RealtimeTokenApiClientTest {
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
    fun `getRealtimeToken targets the canonical path and parses the token plus expires_in envelope`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                RealtimeTokenApiClient(
                    client { request ->
                        captured = request
                        respond("""{"token":"jws.abc.def","expires_in":3600}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            val response = api.getRealtimeToken()
            assertEquals("/api/v1/realtime/token", requireNotNull(captured).url.encodedPath)
            assertEquals("jws.abc.def", response.token)
            assertEquals(3600L, response.expiresIn)
        }

    @Test
    fun `fetchToken returns the bare JWS string from the envelope`() =
        runTest {
            val api =
                RealtimeTokenApiClient(
                    client { respond("""{"token":"jws.xyz","expires_in":3600}""", HttpStatusCode.OK, JSON_HEADERS) },
                )
            assertEquals("jws.xyz", api.fetchToken())
        }
}
