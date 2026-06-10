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
 *    Redis-impl convention where `ttl == window`) for rolling keys; keys with
 *    [RateLimiter.FIXED_WINDOW_KEY_MARKER] use fixed-window counting that only
 *    resets when the bucket expires (mirrors the Redis INCR/PEXPIRE script —
 *    2026-06-10 audit, finding 02-H4).
 *
 * The optional [clock] constructor parameter (defaulting to [Instant.now])
 * supports deterministic Retry-After math in tests.
 */
class InMemoryRateLimiter(
    private val clock: () -> Instant = Instant::now,
) : RateLimiter {
    // Both methods share the same bucket map keyed by full Redis-style key string.
    // For the user-keyed `tryAcquire`, the caller's `userId + key` uniqueness is
    // preserved by encoding `userId` into the `key` (callers consistently pass
    // `{scope:<role>}:{user:<uuid>}` per the hash-tag convention). For
    // `tryAcquireByKey`, the caller's IP / geocell / etc. is in `key` directly.
    // Sharing the bucket map guarantees that a user-keyed and key-axis call against
    // the same physical key map to the same sliding-window bucket — matching the
    // production Redis impl's behavior and the rate-limit-infrastructure spec
    // scenario "tryAcquireByKey shares Lua script with tryAcquire".
    private val buckets: ConcurrentHashMap<String, ArrayDeque<Instant>> = ConcurrentHashMap()

    private class FixedWindowBucket(
        var count: Int,
        var resetAt: Instant,
    )

    private val fixedBuckets: ConcurrentHashMap<String, FixedWindowBucket> = ConcurrentHashMap()

    override fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome = admit(key, capacity, ttl)

    override fun tryAcquireByKey(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome = admit(key, capacity, ttl)

    private fun admit(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        if (RateLimiter.isFixedWindowKey(key)) return admitFixedWindow(key, capacity, ttl)
        val now = clock()
        val bucket = buckets.computeIfAbsent(key) { ArrayDeque() }
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

    private fun admitFixedWindow(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        val now = clock()
        val bucket = fixedBuckets.computeIfAbsent(key) { FixedWindowBucket(count = 0, resetAt = now.plus(ttl)) }
        synchronized(bucket) {
            if (!now.isBefore(bucket.resetAt)) {
                // Window expired — start a fresh one anchored on the caller's ttl
                // (computeTTLToNextReset at this moment).
                bucket.count = 0
                bucket.resetAt = now.plus(ttl)
            }
            if (bucket.count >= capacity) {
                val seconds = Duration.between(now, bucket.resetAt).seconds.coerceAtLeast(1L)
                return RateLimiter.Outcome.RateLimited(retryAfterSeconds = seconds)
            }
            bucket.count++
            return RateLimiter.Outcome.Allowed(remaining = capacity - bucket.count)
        }
    }

    override fun releaseMostRecent(
        userId: UUID,
        key: String,
    ) {
        if (RateLimiter.isFixedWindowKey(key)) {
            val fixed = fixedBuckets[key] ?: return
            synchronized(fixed) {
                if (fixed.count > 0) fixed.count--
            }
            return
        }
        val bucket = buckets[key] ?: return
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
