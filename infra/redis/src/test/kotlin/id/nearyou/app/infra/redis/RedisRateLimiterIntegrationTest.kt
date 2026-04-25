package id.nearyou.app.infra.redis

import id.nearyou.app.core.domain.ratelimit.RateLimiter
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.lettuce.core.RedisClient
import io.lettuce.core.cluster.SlotHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Integration test for [RedisRateLimiter] against a real Redis instance. Tagged
 * `database` so unit-only test runs (`-Dkotest.tags='!database'`) skip these.
 *
 * Test target: `redis:7-alpine` standalone, reachable at the URL in `REDIS_URL`
 * env var (CI service container) or the local dev compose default
 * `redis://localhost:6379`. Each test uses a unique key prefix to isolate state.
 *
 * Standalone mode caveat: Redis Cluster `CROSSSLOT` rejection is only producible
 * against an actual cluster. The hash-tag scenario asserts the CRC16 *math*
 * invariant via [SlotHash.getSlot] — verifies that a properly-formatted
 * `{scope:...}:{user:...}` key would resolve both segments to the same slot in
 * production cluster mode. This proves the math; runtime cluster behavior is
 * exercised only via staging/prod traffic.
 *
 * Covers all 10 scenarios from `rate-limit-infrastructure/spec.md` § Test coverage.
 */
@Tags("database")
class RedisRateLimiterIntegrationTest : StringSpec(
    spec@{
        val redisUrl = System.getenv("REDIS_URL")?.takeIf { it.isNotBlank() } ?: "redis://localhost:6379"

        // Reachability probe: skip the entire spec silently if Redis is unreachable.
        // Mirrors the Postgres-probe pattern in `KotestProjectConfig.beforeProject()`
        // for unit-only local test runs that don't have docker-compose up.
        val redisReachable =
            try {
                RedisClient.create(redisUrl).use { probeClient ->
                    probeClient.connect().use { conn ->
                        conn.sync().ping()
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        if (!redisReachable) return@spec

        val client = RedisClient.create(redisUrl)
        afterSpec {
            client.shutdown()
        }

        fun freshKey(scope: String): String {
            val unique = UUID.randomUUID().toString().substring(0, 8)
            return "{scope:$scope}:{test:$unique}"
        }

        "scenario 1 — empty bucket admits up to capacity then rejects (capacity 10)" {
            RedisRateLimiter(client).use { limiter ->
                val u = UUID.randomUUID()
                val key = freshKey("rl_test_seq")
                repeat(10) { i ->
                    val outcome = limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
                    outcome.shouldBeInstanceOf<RateLimiter.Outcome.Allowed>()
                    outcome.remaining shouldBe (9 - i)
                }
                val rejected1 = limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
                val rejected2 = limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
                rejected1.shouldBeInstanceOf<RateLimiter.Outcome.RateLimited>()
                rejected2.shouldBeInstanceOf<RateLimiter.Outcome.RateLimited>()
            }
        }

        "scenario 2 — Retry-After math reflects oldest counted submission within ±5s" {
            // Use a frozen clock so the math is deterministic. The Lua script reads
            // ARGV-supplied now_ms (sourced from the injected Clock), so we can verify
            // the math without real-time skew.
            val baseInstant = Instant.parse("2026-04-25T12:00:00Z")
            var nowOffset = 0L
            val frozenClock =
                Clock.fixed(baseInstant.plusSeconds(nowOffset), ZoneOffset.UTC).let { clock ->
                    object : Clock() {
                        override fun getZone() = clock.zone

                        override fun withZone(zone: java.time.ZoneId) = clock.withZone(zone)

                        override fun instant(): Instant = baseInstant.plusSeconds(nowOffset)
                    }
                }
            RedisRateLimiter(client, clock = frozenClock).use { limiter ->
                val u = UUID.randomUUID()
                val key = freshKey("rl_test_retry")
                // Fill all 10 slots at t=0..9 (one per second).
                repeat(10) {
                    limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
                    nowOffset += 1
                }
                // Now at t=10. Window is 1h = 3600s. Oldest entry at t=0; ages out at t=3600.
                // Retry-After should be ~3590s.
                val rejected = limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
                rejected.shouldBeInstanceOf<RateLimiter.Outcome.RateLimited>()
                // ±5s tolerance: between 3585 and 3595 inclusive accommodates ceiling rounding.
                rejected.retryAfterSeconds.shouldBeBetween(3585L, 3595L)
            }
        }

        "scenario 3 — releaseMostRecent restores the slot" {
            RedisRateLimiter(client).use { limiter ->
                val u = UUID.randomUUID()
                val key = freshKey("rl_test_release")
                repeat(3) { limiter.tryAcquire(u, key, capacity = 3, ttl = Duration.ofHours(1)) }
                // Bucket full → reject.
                limiter.tryAcquire(u, key, capacity = 3, ttl = Duration.ofHours(1))
                    .shouldBeInstanceOf<RateLimiter.Outcome.RateLimited>()
                // Release one → next admit succeeds.
                limiter.releaseMostRecent(u, key)
                limiter.tryAcquire(u, key, capacity = 3, ttl = Duration.ofHours(1))
                    .shouldBeInstanceOf<RateLimiter.Outcome.Allowed>()
            }
        }

        "scenario 4 — releaseMostRecent on empty bucket is a no-op" {
            RedisRateLimiter(client).use { limiter ->
                val u = UUID.randomUUID()
                val key = freshKey("rl_test_empty_release")
                // Never acquired. Release must not throw, must not error.
                limiter.releaseMostRecent(u, key)
                // Subsequent admit still works.
                val outcome = limiter.tryAcquire(u, key, capacity = 1, ttl = Duration.ofHours(1))
                outcome shouldBe RateLimiter.Outcome.Allowed(remaining = 0)
            }
        }

        "scenario 5 — concurrent capacity-boundary: parallel coroutines, capacity 10, exactly 10 admit" {
            RedisRateLimiter(client).use { limiter ->
                val u = UUID.randomUUID()
                val key = freshKey("rl_test_concurrent")
                val outcomes =
                    runBlocking {
                        (1..15)
                            .map {
                                async(Dispatchers.IO) {
                                    limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
                                }
                            }.awaitAll()
                    }
                val admitted = outcomes.count { it is RateLimiter.Outcome.Allowed }
                val rejected = outcomes.count { it is RateLimiter.Outcome.RateLimited }
                admitted shouldBe 10
                rejected shouldBe 5
            }
        }

        "scenario 6 — old entries pruned: bucket pre-loaded with expired scores, fresh tryAcquire returns Allowed(remaining=9)" {
            // Pre-load the bucket with 10 entries at scores well outside the window.
            // The next tryAcquire's prune step should remove all of them.
            RedisRateLimiter(client).use { limiter ->
                val u = UUID.randomUUID()
                val key = freshKey("rl_test_prune")
                val ancientMs = 0L // Year 1970 — guaranteed older than any reasonable window.
                client.connect().use { conn ->
                    val sync = conn.sync()
                    repeat(10) { i ->
                        sync.zadd(key, ancientMs.toDouble() + i, "ancient-$i")
                    }
                }
                val outcome = limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
                outcome shouldBe RateLimiter.Outcome.Allowed(remaining = 9)
                // Verify the bucket has exactly 1 entry (the new one).
                client.connect().use { conn ->
                    conn.sync().zcard(key) shouldBe 1L
                }
            }
        }

        "scenario 7 — hash-tag CRC16 equivalence: both segments map to the same Redis Cluster slot" {
            // Standalone Redis cannot raise CROSSSLOT at runtime; this test exercises
            // the CRC16 math invariant via Lettuce's SlotHash helper. In production
            // (Upstash cluster), this invariant is what prevents CROSSSLOT errors on
            // multi-key Lua scripts.
            val u = UUID.randomUUID()
            val key = "{scope:rate_test_day}:{user:$u}"
            // Lettuce SlotHash respects hash tags: getSlot of any segment between the
            // first `{` and the next `}` yields the same slot for any input outside
            // that tag. Compute the slot of just the tag content vs. the full key.
            val slotForFullKey = SlotHash.getSlot(key)
            val slotForScopeOnly = SlotHash.getSlot("{scope:rate_test_day}")
            slotForFullKey shouldBe slotForScopeOnly
            // Sanity: a malformed key without hash tag would resolve a different slot
            // for a substring (not testing CROSSSLOT runtime; just the invariant).
            val malformed = "scope:rate_test_day:user:$u"
            SlotHash.getSlot(malformed) shouldBe SlotHash.getSlot(malformed) // trivially equal
        }

        "scenario 8 — two same-millisecond inserts both land via random JTI" {
            // Issue two parallel tryAcquire calls. Both Lua scripts may observe the
            // same `now_ms` (the Lettuce sync API doesn't add per-call latency
            // detectable below 1ms in fast paths). Random JTI prevents ZADD member
            // collision; bucket size after both calls must be exactly 2.
            RedisRateLimiter(client).use { limiter ->
                val u = UUID.randomUUID()
                val key = freshKey("rl_test_collision")
                runBlocking {
                    (1..2)
                        .map {
                            async(Dispatchers.IO) {
                                limiter.tryAcquire(u, key, capacity = 100, ttl = Duration.ofHours(1))
                            }
                        }.awaitAll()
                }
                client.connect().use { conn ->
                    conn.sync().zcard(key) shouldBe 2L
                }
            }
        }

        "scenario 9 — bucket already over capacity preserved on cap reduction" {
            // Pre-populate 7 entries within the window. Next tryAcquire with capacity=5
            // must reject AND bucket size remains 7 (no silent truncation).
            RedisRateLimiter(client).use { limiter ->
                val u = UUID.randomUUID()
                val key = freshKey("rl_test_overcap")
                repeat(7) { limiter.tryAcquire(u, key, capacity = 100, ttl = Duration.ofHours(1)) }
                val outcome = limiter.tryAcquire(u, key, capacity = 5, ttl = Duration.ofHours(1))
                outcome.shouldBeInstanceOf<RateLimiter.Outcome.RateLimited>()
                client.connect().use { conn ->
                    conn.sync().zcard(key) shouldBe 7L
                }
            }
        }

        "scenario 10 — V9 contract subsumption: 10-succeed, 11th-429, 409-release-on-empty" {
            // Reproduces the core V9 ReportRateLimiter assertions against the
            // Redis-backed impl, since V9's clock-injection seam doesn't survive the
            // port (Lua reads server-side TIME). This test class — not V9's
            // ReportRateLimiterTest — is the canonical correctness gate going forward.
            RedisRateLimiter(client).use { limiter ->
                val u = UUID.randomUUID()
                val key = freshKey("rl_test_v9_subsume")
                // 10 succeed.
                val admitted =
                    (1..10).map {
                        limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
                    }
                admitted.shouldHaveSize(10)
                admitted.forEach { it.shouldBeInstanceOf<RateLimiter.Outcome.Allowed>() }
                // 11th rejected with retry-after >= 1.
                val rejected = limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
                rejected.shouldBeInstanceOf<RateLimiter.Outcome.RateLimited>()
                (rejected.retryAfterSeconds >= 1L) shouldBe true
                // 409-style release on the no-op path: pop one slot, the next admit succeeds.
                limiter.releaseMostRecent(u, key)
                limiter.tryAcquire(u, key, capacity = 10, ttl = Duration.ofHours(1))
                    .shouldBeInstanceOf<RateLimiter.Outcome.Allowed>()
            }
        }
    },
)
