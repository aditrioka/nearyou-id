package id.nearyou.app.data.dataexport

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
 * MockEngine-backed coverage of [DataExportRepository] / [DataExportApiClient]: the canonical
 * `POST`/`GET /api/v1/account/export` paths, the camelCase wire parse + unknown-key tolerance, and the
 * status-driven outcome mapping over the full vocabulary (`none`/`pending`/`processing`/`ready`/`expired`/
 * `failed`) with no generic fallthrough. Mirrors `AccountDeletionRepositoryTest`.
 */
class DataExportRepositoryTest {
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

    private fun repo(handler: MockRequestHandler) = DataExportRepository(DataExportApiClient(client(handler)))

    @Test
    fun `request targets POST export and maps 202 to Requested`() =
        runTest {
            var captured: HttpRequestData? = null
            val outcome =
                repo { request ->
                    captured = request
                    respond(
                        // extra unknown key verifies ignoreUnknownKeys tolerance on the POST body too
                        """{"requestId":"r-1","status":"pending","extra":"x"}""",
                        HttpStatusCode.Accepted,
                        JSON,
                    )
                }.requestExport()
            assertEquals("/api/v1/account/export", captured?.url?.encodedPath)
            assertEquals(HttpMethod.Post, captured?.method)
            assertEquals(DataExportRequestOutcome.Requested, outcome)
        }

    @Test
    fun `request maps 401 to Terminal401 and 500 to RetryableError`() =
        runTest {
            val r401 = repo { respond("", HttpStatusCode.Unauthorized) }.requestExport()
            assertEquals(DataExportRequestOutcome.Terminal401, r401)
            val r500 = repo { respond("", HttpStatusCode.InternalServerError) }.requestExport()
            assertEquals(DataExportRequestOutcome.RetryableError, r500)
        }

    @Test
    fun `status none maps to None`() =
        runTest {
            var captured: HttpRequestData? = null
            val outcome =
                repo { request ->
                    captured = request
                    respond("""{"status":"none"}""", HttpStatusCode.OK, JSON)
                }.fetchStatus()
            assertEquals("/api/v1/account/export", captured?.url?.encodedPath)
            assertEquals(HttpMethod.Get, captured?.method)
            assertEquals(DataExportStatusOutcome.None, outcome)
        }

    @Test
    fun `status pending and processing both map to InProgress`() =
        runTest {
            assertEquals(
                DataExportStatusOutcome.InProgress,
                repo { respond("""{"status":"pending"}""", HttpStatusCode.OK, JSON) }.fetchStatus(),
            )
            assertEquals(
                DataExportStatusOutcome.InProgress,
                repo { respond("""{"status":"processing"}""", HttpStatusCode.OK, JSON) }.fetchStatus(),
            )
        }

    @Test
    fun `status ready maps to Ready with the deadline and signed url and tolerates unknown keys`() =
        runTest {
            val outcome =
                repo {
                    respond(
                        """{"status":"ready","downloadExpiresAt":"2026-07-01T00:00:00Z",""" +
                            """"downloadUrl":"https://r2.example/signed","extra":1}""",
                        HttpStatusCode.OK,
                        JSON,
                    )
                }.fetchStatus()
            assertTrue(outcome is DataExportStatusOutcome.Ready)
            assertEquals("2026-07-01T00:00:00Z", outcome.downloadExpiresAt)
            assertEquals("https://r2.example/signed", outcome.downloadUrl)
        }

    @Test
    fun `status ready missing url degrades to Unavailable not a crash`() =
        runTest {
            assertEquals(
                DataExportStatusOutcome.Unavailable,
                repo { respond("""{"status":"ready","downloadExpiresAt":"2026-07-01T00:00:00Z"}""", HttpStatusCode.OK, JSON) }
                    .fetchStatus(),
            )
        }

    @Test
    fun `status expired and failed map to their distinct outcomes`() =
        runTest {
            assertEquals(
                DataExportStatusOutcome.Expired,
                repo { respond("""{"status":"expired"}""", HttpStatusCode.OK, JSON) }.fetchStatus(),
            )
            assertEquals(
                DataExportStatusOutcome.Failed,
                repo { respond("""{"status":"failed"}""", HttpStatusCode.OK, JSON) }.fetchStatus(),
            )
        }

    @Test
    fun `status 401 maps to Terminal401 and 500 maps to Unavailable non-trapping`() =
        runTest {
            assertEquals(
                DataExportStatusOutcome.Terminal401,
                repo { respond("", HttpStatusCode.Unauthorized) }.fetchStatus(),
            )
            assertEquals(
                DataExportStatusOutcome.Unavailable,
                repo { respond("", HttpStatusCode.InternalServerError) }.fetchStatus(),
            )
        }
}
