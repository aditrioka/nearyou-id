package id.nearyou.app.diagnostics

/**
 * Process-wide, **coordinate-free** sink for non-user-facing repository diagnostics
 * (mobile-session-expiry-and-proactive-refresh, design D6). Repositories take a `(String) -> Unit`
 * diagnostic lambda; `MobileModule` wires the timeline repositories to `get<DiagnosticSink>()::log`
 * instead of the no-op default, so `nearby_network_error` / `global_network_error` and the 400
 * `invalid_request` diagnostics are actually observable.
 *
 * **PII discipline:** the sink carries ONLY pre-redacted status/outcome strings — the call sites pass
 * an `HttpError.status: Int` or a `NetworkError.cause.message`, NEVER a coordinate or a token (guarded
 * by a comments-stripped source scan). It composes with the HTTP-path `CoordinateMaskingLogger`; it
 * does not widen the log surface. Modelled as a Koin-bound `fun interface` so a test can substitute a
 * capturing spy and assert the graph wires a real (non-no-op) sink into each repository.
 */
fun interface DiagnosticSink {
    fun log(message: String)
}

/**
 * The default [DiagnosticSink]: emits to the console in debug builds (visible in logcat / the iOS
 * console) and drops in release ([enabled] = false) — until a Sentry/OTel sink lands. Mirrors
 * `HttpClientFactory`'s debug-only `PrintlnLogger`; the message is already coordinate-free.
 */
class ConsoleDiagnosticSink(
    private val enabled: Boolean,
) : DiagnosticSink {
    override fun log(message: String) {
        if (enabled) println("[diagnostic] $message")
    }
}
