package id.nearyou.app.data.accountdeletion

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
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON = headersOf("Content-Type", "application/json")

/**
 * MockEngine-backed coverage of [AccountDeletionRepository] / [AccountDeletionApiClient]: the canonical
 * `POST`/`DELETE`/`GET /api/v1/account/deletion-request` paths, the camelCase wire parse + unknown-key
 * tolerance, and the status-driven outcome mapping for all three calls (no generic fallthrough).
 */
class AccountDeletionRepositoryTest {
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

    private fun repo(handler: MockRequestHandler) = AccountDeletionRepository(AccountDeletionApiClient(client(handler)))

    @Test
    fun `request targets POST deletion-request and maps 200 to Scheduled with the parsed instant`() =
        runTest {
            var captured: HttpRequestData? = null
            val outcome =
                repo { request ->
                    captured = request
                    respond(
                        // extra unknown key verifies ignoreUnknownKeys tolerance
                        """{"scheduledHardDeleteAt":"2026-07-17T00:00:00Z","extra":"x"}""",
                        HttpStatusCode.OK,
                        JSON,
                    )
                }.requestDeletion()
            assertEquals("/api/v1/account/deletion-request", captured?.url?.encodedPath)
            assertEquals(HttpMethod.Post, captured?.method)
            assertTrue(outcome is AccountDeletionOutcome.Scheduled)
            assertEquals("2026-07-17T00:00:00Z", (outcome as AccountDeletionOutcome.Scheduled).scheduledHardDeleteAt)
        }

    @Test
    fun `request maps 401 to Terminal401 and 500 to RetryableError`() =
        runTest {
            val r401 = repo { respond("", HttpStatusCode.Unauthorized) }.requestDeletion()
            assertEquals(AccountDeletionOutcome.Terminal401, r401)
            val r500 = repo { respond("", HttpStatusCode.InternalServerError) }.requestDeletion()
            assertEquals(AccountDeletionOutcome.RetryableError, r500)
        }

    @Test
    fun `status pending maps to Pending and absent maps to NotPending`() =
        runTest {
            val pending =
                repo {
                    respond("""{"pending":true,"scheduledHardDeleteAt":"2026-07-17T00:00:00Z"}""", HttpStatusCode.OK, JSON)
                }.fetchStatus()
            assertTrue(pending is DeletionStatusOutcome.Pending)
            assertEquals("2026-07-17T00:00:00Z", (pending as DeletionStatusOutcome.Pending).scheduledHardDeleteAt)

            // pending=false (and a body that omits the date) → NotPending
            val notPending = repo { respond("""{"pending":false}""", HttpStatusCode.OK, JSON) }.fetchStatus()
            assertEquals(DeletionStatusOutcome.NotPending, notPending)
        }

    @Test
    fun `status 401 maps to Terminal401 and 500 maps to Unavailable non-trapping`() =
        runTest {
            assertEquals(DeletionStatusOutcome.Terminal401, repo { respond("", HttpStatusCode.Unauthorized) }.fetchStatus())
            assertEquals(DeletionStatusOutcome.Unavailable, repo { respond("", HttpStatusCode.InternalServerError) }.fetchStatus())
        }

    @Test
    fun `cancel targets DELETE and maps 2xx to Success and 401 to Terminal401 and 409 to RetryableError`() =
        runTest {
            var captured: HttpRequestData? = null
            val ok =
                repo { request ->
                    captured = request
                    respond("", HttpStatusCode.OK)
                }.cancelDeletion()
            assertEquals("/api/v1/account/deletion-request", captured?.url?.encodedPath)
            assertEquals(HttpMethod.Delete, captured?.method)
            assertEquals(DeletionCancelOutcome.Success, ok)

            assertEquals(DeletionCancelOutcome.Terminal401, repo { respond("", HttpStatusCode.Unauthorized) }.cancelDeletion())
            assertEquals(DeletionCancelOutcome.RetryableError, repo { respond("", HttpStatusCode.Conflict) }.cancelDeletion())
        }
}
