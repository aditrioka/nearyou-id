package id.nearyou.app.data.report

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode

/**
 * Test-only [ReportSubmitter] returning a pre-programmed [ReportOutcome] and recording the last
 * submission's target/category/note so a test can drive a specific report path WITHOUT a backend and
 * assert the wire arguments (e.g. "the reply report submitted `target_type = "reply"` + the reply id, and
 * no `author_id`"). The super [ReportApiClient] is a never-called MockEngine client (the override returns
 * the programmed outcome before any HTTP call), so this fake exercises zero transport.
 */
class FakeReportSubmitter(
    private val outcome: ReportOutcome = ReportOutcome.Submitted,
) : ReportSubmitter(neverCalledApiClient()) {
    var submitCount: Int = 0
        private set
    var lastTarget: ReportTargetType? = null
        private set
    var lastTargetId: String? = null
        private set
    var lastCategory: ReportReasonCategory? = null
        private set
    var lastNote: String? = null
        private set

    override suspend fun submit(
        target: ReportTargetType,
        targetId: String,
        category: ReportReasonCategory,
        note: String?,
    ): ReportOutcome {
        submitCount++
        lastTarget = target
        lastTargetId = targetId
        lastCategory = category
        lastNote = note
        return outcome
    }
}

/** A real [ReportApiClient] over a MockEngine that is never consulted (the fake overrides `submit`). */
private fun neverCalledApiClient(): ReportApiClient =
    ReportApiClient(
        HttpClientFactory.create(
            installTimeouts = false,
            apiBaseUrl = "http://test.local",
            tokenStore = InMemoryTokenStore(),
            sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
            engine = MockEngine { respond("", HttpStatusCode.NoContent) },
            installLogging = false,
            nowMillis = { 0L },
        ),
    )
