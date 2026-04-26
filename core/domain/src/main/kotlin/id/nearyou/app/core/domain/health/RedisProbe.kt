package id.nearyou.app.core.domain.health

import java.time.Duration

/**
 * Redis liveness probe. Implementations issue `PING` (or equivalent) over the
 * supplied client and return [ProbeResult] with the per-probe latency and a
 * fixed-vocabulary error classification on failure.
 *
 * The interface lives in `:core:domain` so business code (e.g., `:backend:ktor`'s
 * `HealthRoutes`) can consume Redis liveness without importing `io.lettuce.core.*`,
 * preserving the "no vendor SDK outside `:infra:*`" critical invariant from
 * [`openspec/project.md`](../../../../../../../../openspec/project.md).
 */
interface RedisProbe {
    /**
     * Issue the liveness probe with an absolute timeout budget. Implementations
     * MUST honor [timeout] cooperatively (e.g., via `withTimeoutOrNull`) and MUST
     * return a [ProbeResult] regardless of failure mode (no exceptions escape).
     */
    suspend fun ping(timeout: Duration): ProbeResult
}
