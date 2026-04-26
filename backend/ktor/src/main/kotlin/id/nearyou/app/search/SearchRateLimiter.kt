package id.nearyou.app.search

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Hourly Layer-2 rate limiter for premium search queries — `60` queries per
 * Premium user per rolling 60-minute window. Mirrors V9's `ReportRateLimiter`
 * precedent class structure (Outcome sealed interface, companion `keyFor`,
 * default constructor with `InMemoryRateLimiter` for tests).
 *
 * **Why hourly, not daily-WIB-stagger?** `docs/05-Implementation.md:1746` lists
 * "Search query (Premium) | 60/hour". The `RateLimitTtlRule` only enforces the
 * `computeTTLToNextReset` helper on daily caps; hourly caps use a fixed
 * `Duration.ofHours(1)`. `ReportRateLimiter` is the precedent (also hourly,
 * also Layer-2). Static analysis exempts both.
 *
 * **Why this distinct class wrapping the shared `RateLimiter`?** Same reason
 * as `ReportRateLimiter`: presents a stable, feature-specific surface
 * (`tryAcquire(userId)`, `releaseMostRecent(userId)`, `Outcome` sealed) so the
 * service layer doesn't need to know about the underlying Redis key shape or
 * the cap/window numbers. Refactors to either dimension stay local.
 *
 * **Redis key form** matches the canonical two-segment hash-tag pattern
 * established by `ReportRateLimiter.keyFor`:
 * `{scope:rate_search}:{user:<uuid>}`. Both segments hash-tagged so cluster-
 * safe multi-key ops remain available if a burst clause is added later
 * (search has no burst clause in V1 — see `design.md` Decision 2).
 *
 * **No `releaseMostRecent` on a not-found path.** Search is read-only — there
 * is no analogue of the `ReportRateLimiter`'s 409-duplicate path. The
 * `releaseMostRecent` method is therefore exposed for symmetry but only used
 * by tests; the service never calls it.
 */
class SearchRateLimiter(
    private val rateLimiter: RateLimiter = InMemoryRateLimiter(),
    val cap: Int = DEFAULT_CAP,
    val window: Duration = DEFAULT_WINDOW,
) {
    /**
     * Attempt to claim one slot for [userId].
     *
     * Returns [Outcome.Allowed] (with the remaining quota after consumption) or
     * [Outcome.RateLimited] (with the seconds until the oldest counted query
     * ages out — suitable for a `Retry-After` header populated from
     * `RateLimiter.Outcome.RateLimited.retryAfterSeconds`).
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
     * Release the most-recently-counted slot for [userId]. Provided for
     * symmetry with `ReportRateLimiter`; not used by the search service in V1
     * (read-only endpoint, no idempotent-no-op path that would warrant a
     * release). Tests may use it to sandbox state across cases.
     */
    fun releaseMostRecent(userId: UUID) {
        rateLimiter.releaseMostRecent(userId, keyFor(userId))
    }

    sealed interface Outcome {
        data class Allowed(val remaining: Int) : Outcome

        data class RateLimited(val retryAfterSeconds: Long) : Outcome
    }

    companion object {
        const val DEFAULT_CAP: Int = 60
        val DEFAULT_WINDOW: Duration = Duration.ofHours(1)

        /**
         * Redis cluster hash-tag form: `{scope:rate_search}:{user:<uuid>}`.
         * Two separate hash-tag segments — `RedisHashTagRule` accepts this
         * form; matches `ReportRateLimiter.keyFor` precedent at
         * `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt:92`.
         */
        fun keyFor(userId: UUID): String = "{scope:rate_search}:{user:$userId}"
    }
}
