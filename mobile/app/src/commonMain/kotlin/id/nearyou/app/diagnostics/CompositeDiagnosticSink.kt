package id.nearyou.app.diagnostics

/**
 * Fans a diagnostic out to multiple [DiagnosticSink]s (mobile-crash-reporting). Wired in `MobileModule`
 * so the single sink the repositories already inject reaches BOTH the debug console AND Sentry
 * breadcrumbs — reusing the EXISTING `DiagnosticSink` Koin seam rather than forking a parallel
 * diagnostics path (the seam `ConsoleDiagnosticSink`'s KDoc reserved: "until a Sentry/OTel sink lands").
 */
class CompositeDiagnosticSink(
    private val sinks: List<DiagnosticSink>,
) : DiagnosticSink {
    override fun log(message: String) {
        sinks.forEach { it.log(message) }
    }
}
