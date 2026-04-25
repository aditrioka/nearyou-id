package id.nearyou.app.core.domain.ratelimit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.util.UUID

/**
 * Interface contract test for [RateLimiter] using the canonical [InMemoryRateLimiter]
 * test double. The same in-memory implementation backs the V9 `ReportRateLimiter`
 * default constructor — this test thus also functions as a regression gate for the
 * shared sliding-window algorithm.
 *
 * The contract is the union of V9's `ReportRateLimiter.Outcome` semantics and the
 * `rate-limit-infrastructure` capability scenarios — verifies any conforming
 * implementation (Redis-backed OR in-memory test double) honors:
 *  - `Allowed.remaining` is the post-consumption count.
 *  - `RateLimited.retryAfterSeconds >= 1`.
 *  - The empty-bucket no-op contract on [RateLimiter.releaseMostRecent].
 *  - Capacity boundary (N admit, N+1 reject).
 *  - releaseMostRecent restores the slot.
 *
 * The Redis-backed impl is exercised separately in `:infra:redis` against a real
 * `redis:7-alpine` test container — see `RedisRateLimiterIntegrationTest`.
 */
class RateLimiterTest : StringSpec({

    "Allowed remaining decrements as slots fill" {
        val limiter = InMemoryRateLimiter()
        val u = UUID.randomUUID()
        val key = "{scope:test}:{user:$u}"
        val r1 = limiter.tryAcquire(u, key, capacity = 3, ttl = Duration.ofHours(1))
        r1 shouldBe RateLimiter.Outcome.Allowed(remaining = 2)
        val r2 = limiter.tryAcquire(u, key, capacity = 3, ttl = Duration.ofHours(1))
        r2 shouldBe RateLimiter.Outcome.Allowed(remaining = 1)
        val r3 = limiter.tryAcquire(u, key, capacity = 3, ttl = Duration.ofHours(1))
        r3 shouldBe RateLimiter.Outcome.Allowed(remaining = 0)
    }

    "11th call at capacity 10 returns RateLimited with retryAfterSeconds >= 1" {
        val limiter = InMemoryRateLimiter()
        val u = UUID.randomUUID()
        val key = "{scope:test}:{user:$u}"
        repeat(10) {
            val outcome = limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
            outcome.shouldBeInstanceOf<RateLimiter.Outcome.Allowed>()
        }
        val rejected = limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
        rejected.shouldBeInstanceOf<RateLimiter.Outcome.RateLimited>()
        (rejected.retryAfterSeconds >= 1L) shouldBe true
    }

    "releaseMostRecent restores the slot" {
        val limiter = InMemoryRateLimiter()
        val u = UUID.randomUUID()
        val key = "{scope:test}:{user:$u}"
        repeat(3) { limiter.tryAcquire(u, key, capacity = 3, ttl = Duration.ofHours(1)) }
        // Bucket full. Next try must reject.
        val rejected = limiter.tryAcquire(u, key, capacity = 3, ttl = Duration.ofHours(1))
        rejected.shouldBeInstanceOf<RateLimiter.Outcome.RateLimited>()
        // Release one. Next try must succeed.
        limiter.releaseMostRecent(u, key)
        val allowed = limiter.tryAcquire(u, key, capacity = 3, ttl = Duration.ofHours(1))
        allowed.shouldBeInstanceOf<RateLimiter.Outcome.Allowed>()
    }

    "releaseMostRecent on empty bucket is a no-op" {
        val limiter = InMemoryRateLimiter()
        val u = UUID.randomUUID()
        val key = "{scope:test}:{user:$u}"
        // Never acquired. Release must not throw.
        limiter.releaseMostRecent(u, key)
        // And subsequent acquires still work.
        val outcome = limiter.tryAcquire(u, key, capacity = 1, ttl = Duration.ofHours(1))
        outcome shouldBe RateLimiter.Outcome.Allowed(remaining = 0)
    }

    "Different keys are independent" {
        val limiter = InMemoryRateLimiter()
        val u = UUID.randomUUID()
        val keyA = "{scope:test_a}:{user:$u}"
        val keyB = "{scope:test_b}:{user:$u}"
        repeat(3) { limiter.tryAcquire(u, keyA, capacity = 3, ttl = Duration.ofHours(1)) }
        // keyA full. keyB should still be empty.
        val outcome = limiter.tryAcquire(u, keyB, capacity = 3, ttl = Duration.ofHours(1))
        outcome shouldBe RateLimiter.Outcome.Allowed(remaining = 2)
    }

    "Different users on the same key are independent" {
        val limiter = InMemoryRateLimiter()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        // Same key string for both users (note: real production keys interpolate the
        // user UUID, but the contract MUST scope by (userId, key) tuple regardless).
        val key = "{scope:test}:{user:shared}"
        repeat(3) { limiter.tryAcquire(u1, key, capacity = 3, ttl = Duration.ofHours(1)) }
        val outcome = limiter.tryAcquire(u2, key, capacity = 3, ttl = Duration.ofHours(1))
        outcome shouldBe RateLimiter.Outcome.Allowed(remaining = 2)
    }
})
