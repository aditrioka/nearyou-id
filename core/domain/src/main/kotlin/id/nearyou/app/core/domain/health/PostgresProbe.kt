package id.nearyou.app.core.domain.health

import java.time.Duration

/**
 * Postgres liveness probe. Implementations issue `SELECT 1` (or equivalent) over
 * the configured DataSource and return [ProbeResult] with the per-probe latency
 * and a fixed-vocabulary error classification on failure.
 *
 * The interface lives in `:core:domain` so the health handler can consume the
 * probe via DI without coupling to the JDBC `DataSource` import surface
 * directly.
 */
interface PostgresProbe {
    /**
     * Issue the liveness probe with an absolute timeout budget. Implementations
     * MUST honor [timeout] cooperatively (e.g., via `withTimeoutOrNull`) and MUST
     * return a [ProbeResult] regardless of failure mode. Pool-exhaustion blocks
     * (HikariCP `getConnection` queueing) surface as `error = "timeout"` once the
     * cooperative timeout fires; other JDBC failures map to `connection_refused`
     * (or `unknown` for non-SQL throwables).
     */
    suspend fun probe(timeout: Duration): ProbeResult
}
