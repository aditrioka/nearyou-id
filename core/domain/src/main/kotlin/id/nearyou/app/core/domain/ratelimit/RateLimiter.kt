package id.nearyou.app.core.domain.ratelimit

import java.time.Duration
import java.util.UUID

/**
 * Shared rate-limit primitive backing both daily-with-WIB-stagger windows and
 * rolling-hour windows. Implementations:
 *  - `RedisRateLimiter` in `:infra:redis` — production binding (Lettuce + Lua sliding window).
 *  - In-memory test doubles for unit-speed plumbing checks (V9 `ReportRateLimiterTest` precedent).
 *
 * The interface is constructed so daily and burst limiters are NOT separate methods —
 * the caller passes the [ttl] explicitly. Daily callers pass
 * `computeTTLToNextReset(userId)` (per-user WIB stagger); hourly callers pass a
 * fixed `Duration.ofHours(1)`. The new `RateLimitTtlRule` Detekt rule enforces this
 * convention at every daily call site.
 *
 * Atomicity contract: [tryAcquire] MUST be atomic across concurrent calls with the
 * same `userId + key` pair. Two simultaneous requests at slot capacity MUST NOT both
 * observe `Allowed`. The Redis-backed impl achieves this via a single Lua script;
 * test doubles use `synchronized(...)` per-bucket.
 */
interface RateLimiter {
    /**
     * Attempt to claim one slot for [userId] under [key], with capacity [capacity]
     * and key TTL [ttl].
     *
     * Returns [Outcome.Allowed] (with the remaining quota AFTER consumption) or
     * [Outcome.RateLimited] (with seconds until the oldest counted event ages out,
     * suitable for a `Retry-After` HTTP header).
     */
    fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): Outcome

    /**
     * Pop the most-recently-added slot for [userId] under [key]. Used on the no-op
     * idempotent path where a request consumed a slot but did not produce a state
     * change (e.g., re-like already-liked, 409 duplicate report).
     *
     * No-op if the bucket is empty (defensive — the only legitimate caller invokes
     * this after a successful [tryAcquire], but the implementation MUST tolerate
     * the empty case for future call sites).
     */
    fun releaseMostRecent(
        userId: UUID,
        key: String,
    )

    /**
     * Result of a [tryAcquire] call. Sealed interface mirroring V9 `ReportRateLimiter.Outcome`
     * byte-for-byte so the V9 port preserves the contract.
     */
    sealed interface Outcome {
        /** Slot consumed; [remaining] is the post-consumption count of available slots. */
        data class Allowed(val remaining: Int) : Outcome

        /**
         * Slot rejected. [retryAfterSeconds] is the seconds until the oldest counted
         * event ages out. Coerced to at least 1 by the implementation so
         * `Retry-After: 0` is never returned.
         */
        data class RateLimited(val retryAfterSeconds: Long) : Outcome
    }
}
