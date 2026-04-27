package id.nearyou.app.core.domain.ratelimit

import java.time.Duration
import java.util.UUID

/**
 * Shared rate-limit primitive backing both daily-with-WIB-stagger windows and
 * rolling-hour windows. Two entry points:
 *
 *  - [tryAcquire] - user-axis call site (per-user telemetry + WIB-stagger inference).
 *  - [tryAcquireByKey] - axis-agnostic call site (IP, geocell, fingerprint, etc.).
 *
 * Both methods MUST share the same Lua script in the Redis-backed implementation -
 * see the rate-limit-infrastructure MODIFIED spec for the SHA1-equality contract.
 *
 * The interface is constructed so daily and burst limiters are NOT separate methods;
 * the caller passes the [ttl] explicitly. Daily callers pass
 * computeTTLToNextReset(userId) (per-user WIB stagger); hourly callers pass a fixed
 * Duration.ofHours(1). The RateLimitTtlRule Detekt rule enforces this convention at
 * every daily call site.
 *
 * Atomicity contract: both methods MUST be atomic across concurrent calls with the
 * same key. Two simultaneous requests at slot capacity MUST NOT both observe Allowed.
 */
interface RateLimiter {
    sealed interface Outcome {
        data class Allowed(val remaining: Int) : Outcome

        data class RateLimited(val retryAfterSeconds: Long) : Outcome
    }

    /**
     * Attempt to claim one slot for [userId] under [key], with capacity [capacity]
     * and key TTL [ttl]. User-axis entry point.
     */
    fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): Outcome

    /**
     * Attempt to claim one slot under [key], with capacity [capacity] and key TTL
     * [ttl]. Axis-agnostic entry point for non-user-keyed buckets (IP, geocell,
     * fingerprint, global circuit-breaker). Shares the same Lua script as
     * [tryAcquire] in the Redis-backed implementation.
     */
    fun tryAcquireByKey(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): Outcome

    /**
     * Pop the most-recently-added slot for [userId] under [key]. No-op if the bucket
     * is empty (defensive - the only legitimate caller invokes this after a
     * successful [tryAcquire]).
     *
     * A releaseMostRecentByKey counterpart for [tryAcquireByKey] is intentionally
     * NOT defined: the only tryAcquireByKey call site at the time of introduction
     * (anti-scrape on /health endpoints) does not exhibit the idempotent-re-action
     * pattern.
     */
    fun releaseMostRecent(
        userId: UUID,
        key: String,
    )
}
