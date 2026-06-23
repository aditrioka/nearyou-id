package id.nearyou.app.appeal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/**
 * MockEngine-backed coverage of [AppealRepository] (task 5.5): the HTTP status + nested `error.code` →
 * [AppealSubmitOutcome] / [AppealStatusOutcome] mapping (no generic fallthrough), plus the invariant that
 * the limited **appeal token** is attached EXPLICITLY (the caller has no normal token). Uses a RAW client
 * (no `Auth` plugin) — the production shape for the ban-exempt appeal calls.
 */
class AppealRepositoryTest {
    private fun rawClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    },
                )
            }
            defaultRequest { url("http://test.local") }
        }

    private fun repository(handler: MockRequestHandler): AppealRepository = AppealRepository(AppealApiClient(rawClient(handler)))

    @Test
    fun `submit 201 maps to Submitted and sends the appeal token + appeal_text`() =
        runTest {
            var authHeader: String? = null
            var body = ""
            val repo =
                repository { request ->
                    authHeader = request.headers["Authorization"]
                    body = request.body.bodyText()
                    respond("""{"id":"appeal-1","status":"pending"}""", HttpStatusCode.Created, JSON_HEADERS)
                }
            assertEquals(AppealSubmitOutcome.Submitted("appeal-1"), repo.submit("mohon ditinjau", "tok-123"))
            assertEquals("Bearer tok-123", authHeader, "the limited appeal token must be attached explicitly")
            assertTrue(body.contains("appeal_text"), "the wire field must be snake_case appeal_text: $body")
        }

    @Test
    fun `submit 409 splits on the nested error code`() =
        runTest {
            val pending =
                repository { respond("""{"error":{"code":"appeal_already_pending"}}""", HttpStatusCode.Conflict, JSON_HEADERS) }
            assertEquals(AppealSubmitOutcome.AlreadyPending, pending.submit("x", "t"))

            val notEligible =
                repository { respond("""{"error":{"code":"no_actionable_moderation"}}""", HttpStatusCode.Conflict, JSON_HEADERS) }
            assertEquals(AppealSubmitOutcome.NotEligible, notEligible.submit("x", "t"))
        }

    @Test
    fun `submit 429 maps to RateLimited with the Retry-After seconds`() =
        runTest {
            val repo =
                repository {
                    respond(
                        """{"error":{"code":"rate_limited"}}""",
                        HttpStatusCode.TooManyRequests,
                        headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("42")),
                    )
                }
            assertEquals(AppealSubmitOutcome.RateLimited(42L), repo.submit("x", "t"))
        }

    @Test
    fun `submit 400 maps to InvalidText`() =
        runTest {
            val repo = repository { respond("""{"error":{"code":"content_too_long"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
            assertEquals(AppealSubmitOutcome.InvalidText, repo.submit("x", "t"))
        }

    @Test
    fun `submit 401 maps to SessionExpired`() =
        runTest {
            val repo = repository { respond("""{"error":{"code":"token_revoked"}}""", HttpStatusCode.Unauthorized, JSON_HEADERS) }
            assertEquals(AppealSubmitOutcome.SessionExpired, repo.submit("x", "t"))
        }

    @Test
    fun `submit 5xx and transport failure map to TransportError`() =
        runTest {
            val server = repository { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(AppealSubmitOutcome.TransportError, server.submit("x", "t"))

            val io = repository { throw RuntimeException("connection refused") }
            assertEquals(AppealSubmitOutcome.TransportError, io.submit("x", "t"))
        }

    @Test
    fun `status has_appeal=false maps to None`() =
        runTest {
            val repo = repository { respond("""{"has_appeal":false}""", HttpStatusCode.OK, JSON_HEADERS) }
            assertEquals(AppealStatusOutcome.None, repo.status("t"))
        }

    @Test
    fun `status pending maps to Pending`() =
        runTest {
            val repo =
                repository {
                    respond(
                        """{"has_appeal":true,"action_type":"suspension","status":"pending","created_at":"2026-06-23T00:00:00Z"}""",
                        HttpStatusCode.OK,
                        JSON_HEADERS,
                    )
                }
            assertEquals(AppealStatusOutcome.Pending("suspension", "2026-06-23T00:00:00Z"), repo.status("t"))
        }

    @Test
    fun `status approved and rejected map to Decided`() =
        runTest {
            val approved =
                repository {
                    respond(
                        """{"has_appeal":true,"action_type":"permanent_ban","status":"approved","reviewed_at":"2026-06-24T00:00:00Z"}""",
                        HttpStatusCode.OK,
                        JSON_HEADERS,
                    )
                }
            assertEquals(
                AppealStatusOutcome.Decided(
                    approved = true,
                    actionType = "permanent_ban",
                    decisionReason = null,
                    reviewedAt = "2026-06-24T00:00:00Z",
                ),
                approved.status("t"),
            )

            val rejected =
                repository {
                    respond(
                        """{"has_appeal":true,"action_type":"suspension","status":"rejected","decision_reason":"Tidak cukup alasan."}""",
                        HttpStatusCode.OK,
                        JSON_HEADERS,
                    )
                }
            assertEquals(
                AppealStatusOutcome.Decided(
                    approved = false,
                    actionType = "suspension",
                    decisionReason = "Tidak cukup alasan.",
                    reviewedAt = null,
                ),
                rejected.status("t"),
            )
        }

    @Test
    fun `status 401 maps to SessionExpired`() =
        runTest {
            val repo = repository { respond("", HttpStatusCode.Unauthorized, JSON_HEADERS) }
            assertEquals(AppealStatusOutcome.SessionExpired, repo.status("t"))
        }
}
