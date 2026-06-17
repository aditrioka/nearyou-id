package id.nearyou.app.username

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** The shipped backend sends both `Content-Type: application/json` (via `respondJson`) AND `Retry-After`
 *  on a 429 — the error-code split needs the JSON content type to parse the body, so the fixture must
 *  carry both (a Retry-After-only response would leave the error code unparseable). */
private fun jsonWithRetryAfter(seconds: String) =
    headersOf(
        "Content-Type" to listOf("application/json"),
        "Retry-After" to listOf(seconds),
    )

/**
 * MockEngine-backed coverage of [UsernameRepository]: every HTTP status maps to exactly one outcome,
 * reading the flat body `error` code ONLY to split the two same-status pairs (429 cooldown_active vs
 * rate_limited; 422 invalid_username vs username_rejected) and parsing the `Retry-After` header
 * (absent → 0 floor). Mirrors `SearchRepositoryTest`.
 */
class UsernameRepositoryTest {
    private fun repository(
        tokenStore: TokenStore = InMemoryTokenStore(),
        handler: MockRequestHandler,
    ): UsernameRepository {
        val client: HttpClient =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = tokenStore,
                sessionInvalidator = SessionInvalidator(tokenStore),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return UsernameRepository(UsernameApiClient(client))
    }

    // --- change (PATCH /api/v1/user/username) ---

    @Test
    fun `change 200 maps to Success carrying the new username`() =
        runTest {
            val repo = repository { respond("""{"username":"dewi.kuliner"}""", HttpStatusCode.OK, JSON_HEADERS) }
            val outcome = repo.change("dewi.kuliner")
            assertTrue(outcome is UsernameChangeOutcome.Success)
            assertEquals("dewi.kuliner", outcome.username)
        }

    @Test
    fun `change 200 request carries the snake_case new_username body`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repository { request ->
                    captured = request
                    respond("""{"username":"x"}""", HttpStatusCode.OK, JSON_HEADERS)
                }
            repo.change("dewi.kuliner")
            assertEquals(HttpMethod.Patch, captured?.method)
            assertEquals("/api/v1/user/username", captured?.url?.encodedPath)
            val bodyText = (captured?.body as? TextContent)?.text ?: ""
            assertTrue(bodyText.contains("new_username"), "body uses the snake_case wire key")
            assertTrue(bodyText.contains("dewi.kuliner"), "body carries the candidate")
        }

    @Test
    fun `change 403 maps to PremiumGate`() =
        runTest {
            val repo = repository { respond("""{"error":"premium_required","upsell":true}""", HttpStatusCode.Forbidden, JSON_HEADERS) }
            assertEquals(UsernameChangeOutcome.PremiumGate, repo.change("dewi.kuliner"))
        }

    @Test
    fun `change 409 maps to Unavailable`() =
        runTest {
            val repo = repository { respond("""{"error":"username_unavailable"}""", HttpStatusCode.Conflict, JSON_HEADERS) }
            assertEquals(UsernameChangeOutcome.Unavailable, repo.change("dewi.kuliner"))
        }

    @Test
    fun `change 422 invalid_username maps to InvalidFormat and username_rejected maps to Moderated`() =
        runTest {
            val invalid = repository { respond("""{"error":"invalid_username"}""", HttpStatusCode.UnprocessableEntity, JSON_HEADERS) }
            assertEquals(UsernameChangeOutcome.InvalidFormat, invalid.change("dewi.kuliner"))
            val rejected = repository { respond("""{"error":"username_rejected"}""", HttpStatusCode.UnprocessableEntity, JSON_HEADERS) }
            assertEquals(UsernameChangeOutcome.Moderated, rejected.change("dewi.kuliner"))
        }

    @Test
    fun `change 429 cooldown_active maps to CooldownActive with Retry-After`() =
        runTest {
            val repo =
                repository {
                    respond("""{"error":"cooldown_active"}""", HttpStatusCode.TooManyRequests, jsonWithRetryAfter("1209600"))
                }
            assertEquals(UsernameChangeOutcome.CooldownActive(1209600L), repo.change("dewi.kuliner"))
        }

    @Test
    fun `change 429 rate_limited maps to RateLimited with Retry-After`() =
        runTest {
            val repo =
                repository {
                    respond("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests, jsonWithRetryAfter("1800"))
                }
            assertEquals(UsernameChangeOutcome.RateLimited(1800L), repo.change("dewi.kuliner"))
        }

    @Test
    fun `change 429 with absent Retry-After floors to zero for both error codes`() =
        runTest {
            val cooldown = repository { respond("""{"error":"cooldown_active"}""", HttpStatusCode.TooManyRequests, JSON_HEADERS) }
            assertEquals(UsernameChangeOutcome.CooldownActive(0L), cooldown.change("dewi.kuliner"))
            val rate = repository { respond("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests, JSON_HEADERS) }
            assertEquals(UsernameChangeOutcome.RateLimited(0L), rate.change("dewi.kuliner"))
        }

    @Test
    fun `change 503 maps to Disabled and 400 maps to Error`() =
        runTest {
            val disabled = repository { respond("""{"error":"feature_disabled"}""", HttpStatusCode.ServiceUnavailable, JSON_HEADERS) }
            assertEquals(UsernameChangeOutcome.Disabled, disabled.change("dewi.kuliner"))
            val error = repository { respond("""{"error":"invalid_request"}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
            assertEquals(UsernameChangeOutcome.Error, error.change("dewi.kuliner"))
        }

    @Test
    fun `change terminal 401 maps to SessionExpired not NetworkError`() =
        runTest {
            val store = InMemoryTokenStore(TokenPair("at-stale", "rt-Y", 1L))
            val repo =
                repository(tokenStore = store) { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/auth/refresh" -> respond("", HttpStatusCode.Unauthorized, JSON_HEADERS)
                        else ->
                            respond(
                                "",
                                HttpStatusCode.Unauthorized,
                                headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"nearyou\""),
                            )
                    }
                }
            assertEquals(UsernameChangeOutcome.SessionExpired, repo.change("dewi.kuliner"))
        }

    @Test
    fun `change 5xx and transport failure map to NetworkError`() =
        runTest {
            val server = repository { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(UsernameChangeOutcome.NetworkError, server.change("dewi.kuliner"))
            val transport = repository { throw RuntimeException("connection refused") }
            assertEquals(UsernameChangeOutcome.NetworkError, transport.change("dewi.kuliner"))
        }

    // --- check (GET /api/v1/username/check) ---

    @Test
    fun `check 200 maps to Available both ways and carries the candidate query param`() =
        runTest {
            var captured: HttpRequestData? = null
            val available =
                repository { request ->
                    captured = request
                    respond("""{"available":true}""", HttpStatusCode.OK, JSON_HEADERS)
                }
            assertEquals(UsernameCheckOutcome.Available(true), available.check("dewi.kuliner"))
            assertEquals("/api/v1/username/check", captured?.url?.encodedPath)
            assertEquals("dewi.kuliner", captured?.url?.parameters?.get("candidate"))
            val taken = repository { respond("""{"available":false}""", HttpStatusCode.OK, JSON_HEADERS) }
            assertEquals(UsernameCheckOutcome.Available(false), taken.check("dewi.kuliner"))
        }

    @Test
    fun `check statuses each map to one outcome`() =
        runTest {
            val gate = repository { respond("""{"error":"premium_required"}""", HttpStatusCode.Forbidden, JSON_HEADERS) }
            assertEquals(UsernameCheckOutcome.CheckPremiumGate, gate.check("dewi.kuliner"))
            val invalid = repository { respond("""{"error":"invalid_username"}""", HttpStatusCode.UnprocessableEntity, JSON_HEADERS) }
            assertEquals(UsernameCheckOutcome.CheckInvalidFormat, invalid.check("dewi.kuliner"))
            val exhausted = repository { respond("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests, JSON_HEADERS) }
            assertEquals(UsernameCheckOutcome.ProbeExhausted, exhausted.check("dewi.kuliner"))
            val disabled = repository { respond("""{"error":"feature_disabled"}""", HttpStatusCode.ServiceUnavailable, JSON_HEADERS) }
            assertEquals(UsernameCheckOutcome.CheckDisabled, disabled.check("dewi.kuliner"))
            val server = repository { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(UsernameCheckOutcome.CheckNetworkError, server.check("dewi.kuliner"))
        }
}
