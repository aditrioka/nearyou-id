package id.nearyou.app.data.report

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

private val JSON = headersOf("Content-Type", "application/json")

/**
 * Unit coverage of the shared [ReportSubmitter] (the single report-submission status→[ReportOutcome]
 * mapping, relocated from `ProfileRepository.report`), driven by a real [ReportApiClient] over a
 * MockEngine (mirrors `ProfileRepositoryTest`): 204 → Submitted; 409 `duplicate_report` → Duplicate;
 * 429 → RateLimited(retryAfter); 5xx/other → NetworkError. Also captures the outbound wire so the
 * `target_type`/`target_id`/`reason_category` mapping is asserted per target.
 */
class ReportSubmitterTest {
    private fun submitter(handler: MockRequestHandler): ReportSubmitter {
        val client: HttpClient =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return ReportSubmitter(ReportApiClient(client))
    }

    @Test
    fun `204 maps to Submitted`() =
        runTest {
            val outcome =
                submitter { respond("", HttpStatusCode.NoContent) }
                    .submit(ReportTargetType.POST, "p1", ReportReasonCategory.SPAM, null)
            assertEquals(ReportOutcome.Submitted, outcome)
        }

    @Test
    fun `409 duplicate_report maps to Duplicate`() =
        runTest {
            val outcome =
                submitter { respond("""{"error":{"code":"duplicate_report"}}""", HttpStatusCode.Conflict, JSON) }
                    .submit(ReportTargetType.REPLY, "r1", ReportReasonCategory.HARASSMENT, null)
            assertEquals(ReportOutcome.Duplicate, outcome)
        }

    @Test
    fun `409 with the stale reports_duplicate code does NOT map to Duplicate`() =
        runTest {
            // Guard against re-introducing the stale `reports.duplicate` code: a 409 carrying it is NOT the
            // shipped duplicate signal → it falls through to the retryable NetworkError, never Duplicate.
            val outcome =
                submitter { respond("""{"error":{"code":"reports.duplicate"}}""", HttpStatusCode.Conflict, JSON) }
                    .submit(ReportTargetType.USER, "u1", ReportReasonCategory.SPAM, null)
            assertEquals(ReportOutcome.NetworkError, outcome)
        }

    @Test
    fun `429 maps to RateLimited with the retry-after`() =
        runTest {
            val outcome =
                submitter { respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "60")) }
                    .submit(ReportTargetType.POST, "p1", ReportReasonCategory.OTHER, "note")
            assertEquals(ReportOutcome.RateLimited(60L), outcome)
        }

    @Test
    fun `429 with no Retry-After maps to RateLimited zero`() =
        runTest {
            val outcome =
                submitter { respond("", HttpStatusCode.TooManyRequests) }
                    .submit(ReportTargetType.POST, "p1", ReportReasonCategory.OTHER, null)
            assertEquals(ReportOutcome.RateLimited(0L), outcome)
        }

    @Test
    fun `5xx maps to NetworkError`() =
        runTest {
            val outcome =
                submitter { respond("", HttpStatusCode.InternalServerError) }
                    .submit(ReportTargetType.REPLY, "r1", ReportReasonCategory.SPAM, null)
            assertEquals(ReportOutcome.NetworkError, outcome)
        }

    @Test
    fun `the submitted wire carries the target type and id and reason category`() =
        runTest {
            var body = ""
            val outcome =
                submitter { request ->
                    body = request.body.bodyText()
                    respond("", HttpStatusCode.NoContent)
                }.submit(ReportTargetType.REPLY, "r1", ReportReasonCategory.HATE_SPEECH_SARA, "  ")
            assertEquals(ReportOutcome.Submitted, outcome)
            assertEquals(true, body.contains(""""target_type":"reply""""), "wire target_type; body=$body")
            assertEquals(true, body.contains(""""target_id":"r1""""), "wire target_id; body=$body")
            assertEquals(true, body.contains(""""reason_category":"hate_speech_sara""""), "wire reason_category; body=$body")
            // A blank note is normalized to null → the reason_note key is omitted from the body.
            assertEquals(false, body.contains("reason_note"), "a blank note is omitted from the wire; body=$body")
        }
}

private fun io.ktor.http.content.OutgoingContent.bodyText(): String =
    (this as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
