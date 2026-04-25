package id.nearyou.app.moderation

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Wrapper that adapts the shared [RateLimiter] interface (introduced by the
 * `rate-limit-infrastructure` capability — see `like-rate-limit` change) to the
 * V9-era public surface that [ReportService] and `ReportEndpointsTest` consume.
 *
 * **Public surface preserved byte-for-byte from V9:**
 *  - `tryAcquire(userId): Outcome` — `Outcome.Allowed(remaining)` /
 *    `Outcome.RateLimited(retryAfterSeconds)` shape unchanged.
 *  - `releaseMostRecent(userId)` — same name, same semantics (used on the 409
 *    duplicate path so a UNIQUE-violation does NOT consume a slot).
 *  - `cap` and `window` constructor parameters — same defaults (10 / 1 hour).
 *  - `keyFor(userId)` companion helper — same hash-tag form
 *    `{scope:rate_report}:{user:<uuid>}`.
 *
 * **What changed:**
 *  - Internals delegate to a [RateLimiter] (the shared interface) instead of an
 *    in-process `ConcurrentHashMap`. Production wiring binds the Redis-backed
 *    `RedisRateLimiter` from `:infra:redis`; the default constructor uses
 *    [InMemoryRateLimiter] (the V9 algorithm extracted into `:core:domain`),
 *    which keeps every existing `ReportRateLimiter()` test call working without
 *    modification.
 *  - The "deferred to a separate change" comment is gone — the Redis adapter
 *    landed in the `like-rate-limit` change (see `:infra:redis/RedisRateLimiter.kt`).
 *
 * **Test gate split (per `like-rate-limit` design.md Decision 7):** V9's
 * `ReportEndpointsTest` is the byte-for-byte HTTP-level regression gate (assertions
 * on rate-limited HTTP 429 responses, Retry-After headers, 409-release behavior).
 * Lua-level correctness moves to `:infra:redis`'s `RedisRateLimiterIntegrationTest`
 * — V9 had no separate `ReportRateLimiterTest` unit class to re-point.
 */
class ReportRateLimiter(
    private val rateLimiter: RateLimiter = InMemoryRateLimiter(),
    val cap: Int = DEFAULT_CAP,
    val window: Duration = DEFAULT_WINDOW,
) {
    /**
     * Attempt to claim one slot for [userId].
     *
     * Returns [Outcome.Allowed] (with the remaining quota after consumption) or
     * [Outcome.RateLimited] (with the seconds until the oldest counted
     * submission ages out — suitable for a `Retry-After` header).
     *
     * Synthesizes the key via [keyFor] and delegates to the injected
     * [RateLimiter]. The `ttl` argument equals the sliding window — matches the
     * V9 contract where each submission's slot is occupied for exactly `window`
     * before pruning.
     */
    fun tryAcquire(userId: UUID): Outcome {
        val key = keyFor(userId)
        return when (val outcome = rateLimiter.tryAcquire(userId, key, cap, window)) {
            is RateLimiter.Outcome.Allowed -> Outcome.Allowed(remaining = outcome.remaining)
            is RateLimiter.Outcome.RateLimited ->
                Outcome.RateLimited(retryAfterSeconds = outcome.retryAfterSeconds)
        }
    }

    /**
     * Pop the most-recently-added timestamp for [userId]. Used when a
     * submission that would have counted is retroactively rejected (409
     * duplicate) — the UNIQUE-violation path does NOT consume a slot.
     *
     * No-op if the bucket is empty (defensive — the only callsite is the 409
     * path, which only runs after a successful [tryAcquire]).
     */
    fun releaseMostRecent(userId: UUID) {
        rateLimiter.releaseMostRecent(userId, keyFor(userId))
    }

    sealed interface Outcome {
        data class Allowed(val remaining: Int) : Outcome

        data class RateLimited(val retryAfterSeconds: Long) : Outcome
    }

    companion object {
        const val DEFAULT_CAP: Int = 10
        val DEFAULT_WINDOW: Duration = Duration.ofHours(1)

        /**
         * Redis cluster hash-tag form: `{scope:rate_report}:{user:<uuid>}`. The
         * braces tell Redis Cluster to route both segments to the same slot —
         * important when a future feature extends this key pattern (e.g.
         * per-user + per-target composite keys).
         */
        fun keyFor(userId: UUID): String = "{scope:rate_report}:{user:$userId}"
    }
}
