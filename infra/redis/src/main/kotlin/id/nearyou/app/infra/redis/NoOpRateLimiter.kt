package id.nearyou.app.infra.redis

import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Fail-soft [RateLimiter] used when the production `redis-url` slot is absent at
 * startup (typically: local dev with no `REDIS_URL` env var set, or test runs
 * that don't exercise the limiter). Always admits with `Allowed(remaining =
 * capacity - 1)`; [releaseMostRecent] is a no-op.
 *
 * **NOT for production.** In staging and prod, missing `redis-url` MUST fail
 * startup loudly — see `Application.kt` for the conditional binding (the failure
 * mode in those envs is a slot-provisioning oversight, not a "Redis is
 * optional" choice).
 *
 * Why this lives in `:infra:redis` and not `:core:domain`: it's an alternative
 * binding for the production interface, on the Redis-binding-or-not axis.
 * Keeping it next to `RedisRateLimiter` documents the relationship and avoids
 * polluting `:core:domain` with deployment-specific bindings.
 *
 * **Security note**: ops MUST audit any deployment running this binding. A
 * Cloud Run instance with `KTOR_ENV=production` SHOULD never reach this code
 * path; if it does, the like rate limit is silently absent and abuse mitigation
 * relies entirely on downstream layers (CDN/WAF + DB-level constraints).
 */
class NoOpRateLimiter : RateLimiter {
    override fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome = RateLimiter.Outcome.Allowed(remaining = (capacity - 1).coerceAtLeast(0))

    override fun tryAcquireByKey(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome = RateLimiter.Outcome.Allowed(remaining = (capacity - 1).coerceAtLeast(0))

    override fun releaseMostRecent(
        userId: UUID,
        key: String,
    ) {
        // Intentionally empty.
    }
}
