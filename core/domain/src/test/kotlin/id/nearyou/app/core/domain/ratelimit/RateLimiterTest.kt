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

    "Different users using distinct interpolated keys are independent" {
        val limiter = InMemoryRateLimiter()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        // Production callers ALWAYS interpolate the user UUID into the key
        // (`{scope:rate_like_day}:{user:<uuid>}`), so different users get
        // different keys and therefore independent buckets. The Redis-backed
        // limiter only sees the `key` parameter; `userId` is telemetry-only.
        // The in-memory test double matches this byte-for-byte (key-only bucket
        // map). See `rate-limit-infrastructure` MODIFIED scenario "tryAcquireByKey
        // shares Lua script with tryAcquire" — same Lua script, same bucket
        // semantics regardless of which entry point is used.
        val keyU1 = "{scope:test}:{user:$u1}"
        val keyU2 = "{scope:test}:{user:$u2}"
        repeat(3) { limiter.tryAcquire(u1, keyU1, capacity = 3, ttl = Duration.ofHours(1)) }
        val outcome = limiter.tryAcquire(u2, keyU2, capacity = 3, ttl = Duration.ofHours(1))
        outcome shouldBe RateLimiter.Outcome.Allowed(remaining = 2)
    }

    "tryAcquireByKey and tryAcquire share the same bucket when the key matches" {
        // Spec contract: rate-limit-infrastructure MODIFIED — "tryAcquireByKey
        // shares Lua script with tryAcquire". Same Lua script in production →
        // same bucket. This guards against future maintainers re-introducing a
        // sentinel-UUID workaround (calling tryAcquire with ZERO_UUID) and
        // expecting it to land in a different bucket than tryAcquireByKey.
        val limiter = InMemoryRateLimiter()
        val key = "{scope:health}:{ip:1.2.3.4}"
        // Fill 60 slots via tryAcquireByKey (the canonical IP-axis call site).
        repeat(60) {
            val outcome = limiter.tryAcquireByKey(key, capacity = 60, ttl = Duration.ofSeconds(60))
            outcome.shouldBeInstanceOf<RateLimiter.Outcome.Allowed>()
        }
        // 61st call via tryAcquire(ZERO_UUID, ...) MUST land in the same
        // bucket and observe RateLimited — NOT a fresh empty bucket.
        val zeroUuid = UUID(0, 0)
        val rejected = limiter.tryAcquire(zeroUuid, key, capacity = 60, ttl = Duration.ofSeconds(60))
        rejected.shouldBeInstanceOf<RateLimiter.Outcome.RateLimited>()
    }

    "60 sequential tryAcquireByKey calls admit; 61st rejects" {
        // rate-limit-infrastructure MODIFIED scenario "Concurrent tryAcquireByKey
        // at capacity boundary" — the in-memory test double validates the
        // capacity-boundary contract sequentially. The real-parallel-coroutines
        // assertion lives in the :infra:redis integration test against a
        // redis:7-alpine container (see RedisRateLimiterIntegrationTest).
        val limiter = InMemoryRateLimiter()
        val key = "{scope:health}:{ip:1.2.3.4}"
        repeat(60) {
            val outcome = limiter.tryAcquireByKey(key, capacity = 60, ttl = Duration.ofSeconds(60))
            outcome.shouldBeInstanceOf<RateLimiter.Outcome.Allowed>()
        }
        val rejected = limiter.tryAcquireByKey(key, capacity = 60, ttl = Duration.ofSeconds(60))
        rejected.shouldBeInstanceOf<RateLimiter.Outcome.RateLimited>()
    }
})
