package id.nearyou.app.core.domain.health

import java.time.Duration

/**
 * Supabase Realtime liveness probe. Implementations issue an HTTP request against
 * the Supabase REST surface (which terminates at the same edge as the Realtime WSS
 * endpoint) and treat any HTTP response as `ok = true` — TLS-handshake completion
 * plus an HTTP response is sufficient liveness signal because the probe asserts
 * reachability of the EDGE, not authorization to the data plane.
 *
 * The interface lives in `:core:domain` so business code can consume Supabase
 * liveness without importing `io.ktor.client.*`, preserving the "no vendor SDK
 * outside `:infra:*`" critical invariant.
 */
interface SupabaseRealtimeProbe {
    /**
     * Issue the liveness probe with an absolute timeout budget. Implementations
     * MUST honor [timeout] cooperatively (e.g., via `withTimeoutOrNull`) and MUST
     * return a [ProbeResult] regardless of failure mode.
     *
     * Any HTTP response status (200/401/404/500/502/503) MUST map to `ok = true`.
     * Only network-layer failures (TLS, DNS, connection refused, timeout) map to
     * `ok = false`.
     */
    suspend fun ping(timeout: Duration): ProbeResult
}
