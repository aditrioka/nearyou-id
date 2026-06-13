package id.nearyou.app.diagnostics

import id.nearyou.app.infra.sentry.Breadcrumb
import id.nearyou.app.infra.sentry.CrashReporter

/**
 * Bridges the app's coordinate-free [DiagnosticSink] onto Sentry breadcrumbs via the vendor-free
 * [CrashReporter] (mobile-crash-reporting). Imports ONLY the `:infra:sentry` interface + models — no
 * vendor symbol. Messages are already pre-redacted by the sink's contract (status/outcome strings only);
 * the `CrashReporter` additionally re-scrubs at the breadcrumb boundary (defense in depth).
 */
class SentryBreadcrumbDiagnosticSink(
    private val crashReporter: CrashReporter,
) : DiagnosticSink {
    override fun log(message: String) {
        crashReporter.addBreadcrumb(Breadcrumb(message = message, category = "diagnostic"))
    }
}
