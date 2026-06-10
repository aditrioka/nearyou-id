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
 * Window semantics are derived from the KEY, not a parameter (2026-06-10 audit,
 * finding 02-H4): keys carrying the [FIXED_WINDOW_KEY_MARKER] (`_day}` — every
 * daily-cap key scope ends in `_day`) use FIXED-WINDOW counting — entries never
 * age out mid-window; the bucket resets only when the key expires at the
 * WIB-staggered reset. All other keys use the original sliding window
 * (window == ttl). Rationale: the previous always-sliding behavior pruned daily
 * entries after `ttl` (= time REMAINING to reset, not 24 h), so late-day usage
 * silently refilled daily caps several-fold.
 *
 * Atomicity contract: both methods MUST be atomic across concurrent calls with the
 * same key. Two simultaneous requests at slot capacity MUST NOT both observe Allowed.
 */
interface RateLimiter {
    companion object {
        /**
         * Keys containing this marker get fixed-window semantics. Matches the
         * project's daily-cap key convention — `{scope:rate_like_day}:{user:U}`,
         * `{scope:rate_reply_day}:`, `{scope:rate_chat_send_day}:` — where the
         * scope hash-tag always ends in `_day`.
         */
        const val FIXED_WINDOW_KEY_MARKER: String = "_day}"

        fun isFixedWindowKey(key: String): Boolean = key.contains(FIXED_WINDOW_KEY_MARKER)
    }

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
