package id.nearyou.app.data.ads

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** Repository mapping + fail-safe coverage (task 5.1): any fetch failure resolves to ads OFF. */
class AdsConfigRepositoryTest {
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

    private fun repo(handler: MockRequestHandler) = AdsConfigRepository(AdsConfigApiClient(client(handler)))

    @Test
    fun `enabled config maps to Enabled with the server frequency`() =
        runTest {
            val outcome =
                repo { respond("""{"adsEnabled":true,"timelineFrequency":6}""", HttpStatusCode.OK, JSON_HEADERS) }
                    .fetchConfig()
            assertEquals(AdsConfigOutcome.Enabled(6), outcome)
        }

    @Test
    fun `adsEnabled false maps to Disabled`() =
        runTest {
            val outcome =
                repo { respond("""{"adsEnabled":false,"timelineFrequency":6}""", HttpStatusCode.OK, JSON_HEADERS) }
                    .fetchConfig()
            assertEquals(AdsConfigOutcome.Disabled, outcome)
        }

    @Test
    fun `http error fails safe to Disabled`() =
        runTest {
            val outcome = repo { respond("", HttpStatusCode.InternalServerError) }.fetchConfig()
            assertEquals(AdsConfigOutcome.Disabled, outcome)
        }

    @Test
    fun `network error fails safe to Disabled`() =
        runTest {
            val outcome = repo { throw RuntimeException("boom") }.fetchConfig()
            assertEquals(AdsConfigOutcome.Disabled, outcome)
        }
}
