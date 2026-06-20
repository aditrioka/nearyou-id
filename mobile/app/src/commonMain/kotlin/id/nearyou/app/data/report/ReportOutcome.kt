package id.nearyou.app.data.report

/**
 * A report submission maps to EXACTLY one member. `204` → [Submitted]; `409 duplicate_report` →
 * [Duplicate] (the SHIPPED code — NOT the stale `reports.duplicate` in the reports-spec purpose line);
 * `429` → [RateLimited]; `5xx`/transport/any other → [NetworkError].
 *
 * Relocated from `id.nearyou.app.profile.ProfileFlow` into the shared `data/report/` seam
 * (mobile-content-report) so BOTH the profile and post-detail surfaces consume one outcome type. How a
 * surface RENDERS each member differs by design: profile distinguishes [Duplicate] (a per-user
 * "already reported" copy); post-detail folds [Duplicate] into the same success message as [Submitted]
 * (anti-enumeration — `docs/03`:234). The outcome type is shared; the mapping-to-UI is per-surface.
 */
sealed interface ReportOutcome {
    data object Submitted : ReportOutcome

    data object Duplicate : ReportOutcome

    data class RateLimited(val retryAfterSeconds: Long) : ReportOutcome

    data object NetworkError : ReportOutcome
}
