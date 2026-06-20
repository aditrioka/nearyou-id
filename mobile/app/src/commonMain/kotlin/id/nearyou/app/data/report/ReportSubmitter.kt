package id.nearyou.app.data.report

/**
 * The single shared report-submission seam consumed by BOTH the profile (user report) and post-detail
 * (post/reply report) surfaces (mobile-content-report, design D2). Delegates to [api] and maps each
 * low-level [ReportApiResult] to EXACTLY one member of the sealed [ReportOutcome], keyed on the HTTP
 * **status** (+ the parsed `error.code` for the 409 `duplicate_report`), with no generic "failed"
 * wildcard. This IS the report-submission mapping previously inlined in `ProfileRepository.report` —
 * relocated here so there is exactly ONE report-submission implementation (the anti-patchwork rule).
 *
 * A stateless seam: every call takes the [target] + [targetId] explicitly (so `single { … }` is safe).
 *
 * PII discipline: the [diagnosticLog] sink carries only the HTTP `status` + `errorCode` primitives —
 * never a body, the `targetId`, or a coordinate; this seam never `println`s/logs bodies.
 *
 * `open` (class + [submit]) only so commonTest can substitute a `FakeReportSubmitter` for the
 * `PostDetailViewModel` report-mapping test (the VM injects this concrete class per the change brief,
 * rather than a separate interface seam); production never subclasses it.
 */
open class ReportSubmitter(
    private val api: ReportApiClient,
    // Diagnostic sink for non-user-facing error detail (status + error code only). MUST NOT carry
    // tokens, bodies, the targetId, or coordinates.
    private val diagnosticLog: (status: Int, errorCode: String?) -> Unit = { _, _ -> },
) {
    open suspend fun submit(
        target: ReportTargetType,
        targetId: String,
        category: ReportReasonCategory,
        note: String?,
    ): ReportOutcome =
        when (val result = api.submit(target.wire, targetId, category.toWire(), note)) {
            ReportApiResult.NoContent -> ReportOutcome.Submitted
            is ReportApiResult.NetworkError -> ReportOutcome.NetworkError
            is ReportApiResult.HttpError ->
                when {
                    result.status == 409 && result.errorCode == DUPLICATE_REPORT -> ReportOutcome.Duplicate
                    result.status == 429 -> ReportOutcome.RateLimited(result.retryAfterSeconds ?: 0L)
                    else -> {
                        diagnosticLog(result.status, result.errorCode)
                        ReportOutcome.NetworkError
                    }
                }
        }

    private companion object {
        // The SHIPPED reports duplicate error code (ReportRoutes.kt + reports-spec requirement); NOT the
        // stale `reports.duplicate` in that spec's purpose line.
        const val DUPLICATE_REPORT = "duplicate_report"
    }
}
