package id.nearyou.app.core.domain.health

/**
 * Result of a single dependency probe (Postgres `SELECT 1`, Redis `PING`, Supabase
 * Realtime HTTP probe). Shared shape across all `RedisProbe` / `SupabaseRealtimeProbe`
 * implementations and the inline Postgres probe.
 *
 * `error` is non-null only when `ok == false`. When non-null, it MUST be one of the
 * fixed-vocabulary strings defined in the `health-check` capability spec
 * (`/health/ready` response shape requirement):
 *  - `"timeout"` — the dep is reachable but slow / non-responsive within the per-probe budget.
 *  - `"connection_refused"` — TCP connection refused, port closed, service down.
 *  - `"dns_failure"` — hostname cannot be resolved.
 *  - `"tls_failure"` — TLS handshake failure (cert expiry, pinning mismatch).
 *  - `"unknown"` — escape hatch; any other throwable.
 *
 * Stack traces and full exception messages MUST NOT be embedded in `error` —
 * implementations log the original exception at WARN with full context for
 * operator debugging and emit only the short classification here.
 */
data class ProbeResult(
    val ok: Boolean,
    val latencyMs: Long,
    val error: String? = null,
)

/**
 * Fixed error-vocabulary constants for [ProbeResult.error]. Exposed as `const val`
 * so call-sites use these literals (avoids typos in error mapping logic).
 */
object ProbeError {
    const val TIMEOUT: String = "timeout"
    const val CONNECTION_REFUSED: String = "connection_refused"
    const val DNS_FAILURE: String = "dns_failure"
    const val TLS_FAILURE: String = "tls_failure"
    const val UNKNOWN: String = "unknown"
}
