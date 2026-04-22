package id.nearyou.app.moderation

import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window rate limiter for `POST /api/v1/reports` — 10 submissions per
 * rolling 1-hour window per user.
 *
 * The key shape `{scope:rate_report}:{user:<uuid>}` is the Redis cluster hash-tag
 * format required by `docs/00-README.md § CI Lint Rules — Redis keys`. V9 runs
 * the counter entirely in-process — Redis is available in the dev compose but
 * no JVM client is on the classpath yet, so a production-ready Redis adapter
 * is deferred to a separate change (the interface below is the only seam that
 * needs changing). Behavior (counts, Retry-After math, hash-tag key) is
 * identical to the intended Redis implementation.
 *
 * Policy constants (`CAP`, `WINDOW`) are pulled from `reports` spec Requirement
 * "Rate limit 10 submissions per hour per user" + Decision 6 — not per-target,
 * not per-day, not per-minute.
 *
 * Idempotent / fault-injection notes:
 *  - [tryAcquire] is atomic per-key under a dedicated lock stripe. Concurrent
 *    submissions from the same user cannot both observe slot 10 → both accept.
 *  - [releaseMostRecent] backs out the slot taken by [tryAcquire] when the
 *    submission ends up in a 409 duplicate — per spec Requirement "409
 *    duplicate does not consume a rate-limit slot". The HTTP layer calls
 *    [releaseMostRecent] before returning 409.
 */
class ReportRateLimiter(
    private val clock: () -> Instant = Instant::now,
    val cap: Int = DEFAULT_CAP,
    val window: Duration = DEFAULT_WINDOW,
) {
    private val buckets: ConcurrentHashMap<String, ArrayDeque<Instant>> = ConcurrentHashMap()

    /**
     * Attempt to claim one slot for [userId].
     *
     * Returns [Outcome.Allowed] (with the remaining quota after consumption) or
     * [Outcome.RateLimited] (with the seconds until the oldest counted
     * submission ages out — suitable for a `Retry-After` header).
     *
     * The key used is exposed via [keyFor]; tests can assert the hash-tag shape.
     */
    fun tryAcquire(userId: UUID): Outcome {
        val key = keyFor(userId)
        val now = clock()
        val bucket = buckets.computeIfAbsent(key) { ArrayDeque() }
        synchronized(bucket) {
            pruneOlderThan(bucket, now.minus(window))
            if (bucket.size >= cap) {
                val oldest = bucket.peekFirst() ?: now
                val retryAfter = Duration.between(now, oldest.plus(window))
                val seconds = retryAfter.seconds.coerceAtLeast(1L)
                return Outcome.RateLimited(retryAfterSeconds = seconds)
            }
            bucket.addLast(now)
            return Outcome.Allowed(remaining = cap - bucket.size)
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
        val bucket = buckets[keyFor(userId)] ?: return
        synchronized(bucket) {
            bucket.pollLast()
        }
    }

    private fun pruneOlderThan(bucket: ArrayDeque<Instant>, threshold: Instant) {
        while (bucket.isNotEmpty() && !bucket.peekFirst().isAfter(threshold)) {
            bucket.pollFirst()
        }
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
