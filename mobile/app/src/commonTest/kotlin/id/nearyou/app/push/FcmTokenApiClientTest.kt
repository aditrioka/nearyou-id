package id.nearyou.app.push

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/**
 * MockEngine-backed coverage of [FcmTokenApiClient] (`mobile-fcm-token-registration`, tasks.md 4.4): the
 * `POST /api/v1/user/fcm-token` endpoint + method + body shape (token / platform constant / present + null
 * `app_version`), the 204→`Registered`, each documented 400→`Rejected(code)`, unknown-400→`Rejected(unknown)`,
 * 401→`Unauthorized`, transport→`TransportError` mappings, and the client-side guards (over-length token,
 * empty-after-trim, over-length app_version) each issuing NO request.
 */
class FcmTokenApiClientTest {
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

    private fun apiClient(
        platform: String = "android",
        appVersion: String? = "1.4.0",
        handler: MockRequestHandler,
    ) = FcmTokenApiClient(client(handler), platform = platform, appVersion = appVersion)

    @Test
    fun `register targets POST api v1 user fcm-token with the canonical body and platform constant`() =
        runTest {
            var captured: HttpRequestData? = null
            var body = ""
            val api =
                apiClient { request ->
                    captured = request
                    body = request.body.bodyText()
                    respond("", HttpStatusCode.NoContent)
                }

            val outcome = api.register("tok-abc")

            assertEquals(FcmRegistrationOutcome.Registered, outcome)
            assertEquals(HttpMethod.Post, captured?.method)
            assertEquals("http://test.local/api/v1/user/fcm-token", captured?.url.toString())
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("tok-abc", json["token"]?.jsonPrimitive?.content)
            assertEquals("android", json["platform"]?.jsonPrimitive?.content)
            assertEquals("1.4.0", json["app_version"]?.jsonPrimitive?.content)
        }

    @Test
    fun `null app_version is omitted from the body`() =
        runTest {
            var body = ""
            val api =
                apiClient(appVersion = null) { request ->
                    body = request.body.bodyText()
                    respond("", HttpStatusCode.NoContent)
                }

            api.register("tok-abc")

            // explicitNulls = false on the shared Json omits the null field; the backend accepts the absence.
            assertTrue("app_version" !in body, "null app_version must be omitted from the wire body, got: $body")
        }

    @Test
    fun `204 maps to Registered`() =
        runTest {
            val api = apiClient { respond("", HttpStatusCode.NoContent) }
            assertEquals(FcmRegistrationOutcome.Registered, api.register("tok"))
        }

    @Test
    fun `each documented 400 code maps to its Rejected variant`() =
        runTest {
            val codes =
                listOf(
                    FcmRegistrationOutcome.MALFORMED_BODY,
                    FcmRegistrationOutcome.INVALID_PLATFORM,
                    FcmRegistrationOutcome.EMPTY_TOKEN,
                    FcmRegistrationOutcome.TOKEN_TOO_LONG,
                    FcmRegistrationOutcome.APP_VERSION_TOO_LONG,
                )
            for (code in codes) {
                val api =
                    apiClient { respond("""{"error":"$code"}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
                assertEquals(FcmRegistrationOutcome.Rejected(code), api.register("tok"))
            }
        }

    @Test
    fun `unrecognized 400 code maps to Rejected unknown`() =
        runTest {
            val api =
                apiClient { respond("""{"error":"some_future_code"}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
            assertEquals(FcmRegistrationOutcome.Rejected(FcmRegistrationOutcome.UNKNOWN), api.register("tok"))
        }

    @Test
    fun `unparseable 400 body maps to Rejected unknown`() =
        runTest {
            val api = apiClient { respond("not json", HttpStatusCode.BadRequest, JSON_HEADERS) }
            assertEquals(FcmRegistrationOutcome.Rejected(FcmRegistrationOutcome.UNKNOWN), api.register("tok"))
        }

    @Test
    fun `401 maps to Unauthorized with no immediate re-request`() =
        runTest {
            var requests = 0
            val api =
                apiClient {
                    requests++
                    respond("", HttpStatusCode.Unauthorized)
                }
            assertEquals(FcmRegistrationOutcome.Unauthorized, api.register("tok"))
            assertEquals(1, requests, "a 401 must not trigger a client-side retry-loop (re-attempt is deferred to the next trigger)")
        }

    @Test
    fun `5xx maps to TransportError`() =
        runTest {
            val api = apiClient { respond("", HttpStatusCode.InternalServerError) }
            assertEquals(FcmRegistrationOutcome.TransportError, api.register("tok"))
        }

    @Test
    fun `over-length token is rejected client-side with no request issued`() =
        runTest {
            var requestIssued = false
            val api =
                apiClient { request ->
                    requestIssued = true
                    respond("", HttpStatusCode.NoContent)
                }
            val outcome = api.register("x".repeat(4097))
            assertEquals(FcmRegistrationOutcome.Rejected(FcmRegistrationOutcome.TOKEN_TOO_LONG), outcome)
            assertEquals(false, requestIssued, "no HTTP request must be issued for an over-length token")
        }

    @Test
    fun `empty-after-trim token is rejected client-side with no request issued`() =
        runTest {
            var requestIssued = false
            val api =
                apiClient { request ->
                    requestIssued = true
                    respond("", HttpStatusCode.NoContent)
                }
            val outcome = api.register("   ")
            assertEquals(FcmRegistrationOutcome.Rejected(FcmRegistrationOutcome.EMPTY_TOKEN), outcome)
            assertEquals(false, requestIssued, "no HTTP request must be issued for an empty-after-trim token")
        }

    @Test
    fun `over-length app_version is rejected client-side with no request issued`() =
        runTest {
            var requestIssued = false
            val api =
                apiClient(appVersion = "v".repeat(65)) { request ->
                    requestIssued = true
                    respond("", HttpStatusCode.NoContent)
                }
            val outcome = api.register("tok")
            assertEquals(FcmRegistrationOutcome.Rejected(FcmRegistrationOutcome.APP_VERSION_TOO_LONG), outcome)
            assertEquals(false, requestIssued, "no HTTP request must be issued for an over-length app_version")
        }
}
