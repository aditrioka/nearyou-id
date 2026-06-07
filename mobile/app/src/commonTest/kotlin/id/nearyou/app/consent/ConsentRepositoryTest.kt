package id.nearyou.app.consent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
private const val OK_BODY = """{"analytics":true,"crash":false,"ads_personalization":true}"""

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/**
 * Behavioral coverage of `ConsentRepository.submitConsent` over a `MockEngine`. The status-driven
 * mapping (design D5) is isolated from the bearer/refresh plugin by wiring `ConsentApiClient` over a
 * plain client (the shared-client auth behavior is `HttpClientFactory`'s concern, tested elsewhere).
 */
class ConsentRepositoryTest {
    private fun client(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(DefaultRequest) { url("http://test.local") }
        }

    private fun repo(
        diagnosticLog: (String) -> Unit = {},
        handler: MockRequestHandler,
    ): ConsentRepository = ConsentRepository(ConsentApiClient(client(handler)), diagnosticLog)

    @Test
    fun `200 maps to Success and PATCHes the toggle triple to the canonical path`() =
        runTest {
            var method = ""
            var path = ""
            var body = ""
            val repo =
                repo {
                    method = it.method.value
                    path = it.url.encodedPath
                    body = it.body.bodyText()
                    respond(OK_BODY, HttpStatusCode.OK, JSON_HEADERS)
                }

            val outcome = repo.submitConsent(analytics = true, crash = false, adsPersonalization = true)

            assertEquals(ConsentOutcome.Success, outcome)
            assertEquals("PATCH", method)
            assertEquals("/api/v1/user/consent", path)
            val obj = Json.parseToJsonElement(body).jsonObject
            assertEquals(true, obj["analytics"]!!.jsonPrimitive.boolean)
            assertEquals(false, obj["crash"]!!.jsonPrimitive.boolean)
            assertEquals(true, obj["ads_personalization"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `submitted body reflects the given toggle values not a fixed default`() =
        runTest {
            var body = ""
            val repo =
                repo {
                    body = it.body.bodyText()
                    respond(OK_BODY, HttpStatusCode.OK, JSON_HEADERS)
                }

            repo.submitConsent(analytics = false, crash = true, adsPersonalization = false)

            val obj = Json.parseToJsonElement(body).jsonObject
            assertEquals(false, obj["analytics"]!!.jsonPrimitive.boolean)
            assertEquals(true, obj["crash"]!!.jsonPrimitive.boolean)
            assertEquals(false, obj["ads_personalization"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `submitConsent issues exactly one PATCH and no GET`() =
        runTest {
            val methods = mutableListOf<String>()
            val repo =
                repo {
                    methods.add(it.method.value)
                    respond(OK_BODY, HttpStatusCode.OK, JSON_HEADERS)
                }

            repo.submitConsent(false, true, false)

            assertEquals(listOf("PATCH"), methods)
        }

    @Test
    fun `401 maps to TokenInvalid`() =
        runTest {
            val repo = repo { respond("", HttpStatusCode.Unauthorized, JSON_HEADERS) }
            assertEquals(ConsentOutcome.TokenInvalid, repo.submitConsent(false, true, false))
        }

    @Test
    fun `503 maps to RetryableError`() =
        runTest {
            val repo = repo { respond("", HttpStatusCode.ServiceUnavailable, JSON_HEADERS) }
            assertEquals(ConsentOutcome.RetryableError, repo.submitConsent(false, true, false))
        }

    @Test
    fun `bare 5xx maps to RetryableError`() =
        runTest {
            val repo = repo { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(ConsentOutcome.RetryableError, repo.submitConsent(false, true, false))
        }

    @Test
    fun `400 maps to RetryableError and logs a diagnostic`() =
        runTest {
            val logged = mutableListOf<String>()
            val repo =
                repo(diagnosticLog = { logged.add(it) }) {
                    respond("", HttpStatusCode.BadRequest, JSON_HEADERS)
                }

            assertEquals(ConsentOutcome.RetryableError, repo.submitConsent(false, true, false))
            assertTrue(logged.any { it.contains("consent_invalid_request") }, "400 must log a diagnostic: $logged")
        }

    @Test
    fun `transport failure maps to RetryableError`() =
        runTest {
            val repo = repo { throw RuntimeException("connection refused") }
            assertEquals(ConsentOutcome.RetryableError, repo.submitConsent(false, true, false))
        }

    @Test
    fun `concurrent double-submit issues exactly one PATCH and Cancels the second`() =
        runTest {
            var calls = 0
            val repo =
                repo {
                    calls++
                    delay(1_000) // hold the first call in-flight while the second is attempted
                    respond(OK_BODY, HttpStatusCode.OK, JSON_HEADERS)
                }

            val first = async { repo.submitConsent(true, false, true) }
            delay(50) // let the first acquire the mutex + suspend on the delayed PATCH
            val second = repo.submitConsent(true, false, true)

            assertEquals(ConsentOutcome.Cancelled, second, "the concurrent second submit is rejected")
            assertEquals(ConsentOutcome.Success, first.await())
            assertEquals(1, calls, "exactly one PATCH")
        }
}
