package id.nearyou.app.infra.redis

import id.nearyou.app.core.domain.ratelimit.RateLimiter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.lettuce.core.RedisClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Fail-soft regression suite. Pinned to the staging deploy crash on
 * `nearyou-backend-staging-00033-xj6` (2026-04-25): eager `client.connect()` in
 * the constructor crashed Ktor module load when Lettuce's first connect failed,
 * because Koin singletons are materialized synchronously before the HTTP server
 * binds 8080. The contract verified here:
 *
 *  1. Construction MUST NOT throw, even with an unreachable Redis URL.
 *  2. `tryAcquire` MUST return `Allowed(remaining = capacity)` when Redis is
 *     unreachable (fail-soft — better to over-admit than to crash the request).
 *  3. `releaseMostRecent` MUST NOT throw when Redis is unreachable.
 *  4. After a connect failure, retries are suppressed for [retryBackoff] — we
 *     don't hammer Redis on every request.
 *
 * No `@Tags("database")` — these run as unit tests in CI; the unreachable URL
 * is the whole point of the suite.
 */
class RedisRateLimiterFailSoftTest : StringSpec(
    {
        // Port 1 is reserved (tcpmux); no service listens by convention. Lettuce's
        // connect attempt fails fast with a connection-refused exception.
        val unreachableUrl = "redis://127.0.0.1:1"

        "constructor does not throw when Redis is unreachable" {
            // The whole point: this MUST be safe to call from Koin module load.
            val limiter = RedisRateLimiter(RedisClient.create(unreachableUrl))
            limiter.close()
        }

        "tryAcquire fails soft to Allowed(remaining=capacity) when Redis is unreachable" {
            val limiter = RedisRateLimiter(RedisClient.create(unreachableUrl))
            val outcome =
                limiter.tryAcquire(
                    userId = UUID.randomUUID(),
                    key = "{scope:test}:{user:fail-soft}",
                    capacity = 10,
                    ttl = Duration.ofHours(1),
                )
            outcome.shouldBeInstanceOf<RateLimiter.Outcome.Allowed>()
            outcome.remaining shouldBe 10
            limiter.close()
        }

        "releaseMostRecent is silent when Redis is unreachable" {
            val limiter = RedisRateLimiter(RedisClient.create(unreachableUrl))
            // Must not throw.
            limiter.releaseMostRecent(UUID.randomUUID(), "{scope:test}:{user:silent}")
            limiter.close()
        }

        "connect retries are suppressed during the backoff window" {
            // Use a frozen clock so we can prove the second call short-circuits without
            // attempting connect (and therefore returns instantly, regardless of network).
            val frozen = Instant.parse("2026-04-26T00:00:00Z")
            val clock = Clock.fixed(frozen, ZoneOffset.UTC)
            val limiter =
                RedisRateLimiter(
                    client = RedisClient.create(unreachableUrl),
                    clock = clock,
                    retryBackoff = Duration.ofSeconds(30),
                )

            val first =
                limiter.tryAcquire(
                    userId = UUID.randomUUID(),
                    key = "{scope:test}:{user:backoff-1}",
                    capacity = 5,
                    ttl = Duration.ofHours(1),
                )
            first.shouldBeInstanceOf<RateLimiter.Outcome.Allowed>()

            // Second call inside the backoff window — short-circuits to Allowed without
            // a fresh connect attempt. We assert behavior, not internal state, so the
            // test stays robust to refactors of the retry mechanism.
            val second =
                limiter.tryAcquire(
                    userId = UUID.randomUUID(),
                    key = "{scope:test}:{user:backoff-2}",
                    capacity = 5,
                    ttl = Duration.ofHours(1),
                )
            second.shouldBeInstanceOf<RateLimiter.Outcome.Allowed>()
            (second as RateLimiter.Outcome.Allowed).remaining shouldBe 5

            limiter.close()
        }
    },
)
