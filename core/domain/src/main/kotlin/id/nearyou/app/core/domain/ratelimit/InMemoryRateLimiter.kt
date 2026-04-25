package id.nearyou.app.core.domain.ratelimit

import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process [RateLimiter] using the same sliding-window algorithm V9's
 * `ReportRateLimiter` shipped (`ConcurrentHashMap<key, ArrayDeque<Instant>>` +
 * per-bucket `synchronized` for atomicity). Functionally equivalent to the
 * Redis-backed implementation but without Lua / network round-trips.
 *
 * **Use as a test double**, not in production. The Redis-backed implementation
 * is the production binding (per-replica state would diverge across Cloud Run
 * instances if this were used in prod). Two legitimate uses:
 *  - Unit-speed test fixtures where a real Redis container is overkill
 *    (e.g., `LikeEndpointsTest` plumbing checks).
 *  - The fallback default for `ReportRateLimiter` (the V9 wrapper) so existing
 *    `ReportEndpointsTest` calls — `ReportRateLimiter()` with no injection —
 *    keep working byte-for-byte.
 *
 * The class lives in `:core:domain`'s main source set (not test) so it can be
 * imported by both `:core:domain` tests and `:backend:ktor` tests + production.
 * The KDoc warning is the convention; Kotlin lacks a cross-module test-only
 * visibility short of the Gradle test-fixtures plugin.
 *
 * Behavior matches the [RateLimiter] contract scenarios verbatim, including:
 *  - `Allowed.remaining` is the post-consumption count.
 *  - `RateLimited.retryAfterSeconds >= 1` (coerced).
 *  - `releaseMostRecent` no-op on empty bucket.
 *  - Atomicity per-(userId + key) tuple under a `synchronized` block.
 *  - The `ttl` parameter is interpreted as the sliding-window length (the
 *    Redis-impl convention where `ttl == window`).
 *
 * The optional [clock] constructor parameter (defaulting to [Instant.now])
 * supports deterministic Retry-After math in tests.
 */
class InMemoryRateLimiter(
    private val clock: () -> Instant = Instant::now,
) : RateLimiter {
    private val buckets: ConcurrentHashMap<Pair<UUID, String>, ArrayDeque<Instant>> = ConcurrentHashMap()

    override fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        val now = clock()
        val bucket = buckets.computeIfAbsent(userId to key) { ArrayDeque() }
        synchronized(bucket) {
            pruneOlderThan(bucket, now.minus(ttl))
            if (bucket.size >= capacity) {
                val oldest = bucket.peekFirst() ?: now
                val retryAfter = Duration.between(now, oldest.plus(ttl))
                val seconds = retryAfter.seconds.coerceAtLeast(1L)
                return RateLimiter.Outcome.RateLimited(retryAfterSeconds = seconds)
            }
            bucket.addLast(now)
            return RateLimiter.Outcome.Allowed(remaining = capacity - bucket.size)
        }
    }

    override fun releaseMostRecent(
        userId: UUID,
        key: String,
    ) {
        val bucket = buckets[userId to key] ?: return
        synchronized(bucket) {
            bucket.pollLast()
        }
    }

    private fun pruneOlderThan(
        bucket: ArrayDeque<Instant>,
        threshold: Instant,
    ) {
        while (bucket.isNotEmpty() && !bucket.peekFirst().isAfter(threshold)) {
            bucket.pollFirst()
        }
    }
}
