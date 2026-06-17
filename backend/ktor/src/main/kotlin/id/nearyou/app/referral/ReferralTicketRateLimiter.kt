package id.nearyou.app.referral

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Per-inviter burst limit on referral ticket creation — at most 3 tickets per
 * rolling 7-day window per inviter (docs/01-Business.md § Anti-Abuse Limits:
 * "Max 3 referrals/week burst rate on ticket creation").
 *
 * The inviter is NOT the request's authenticated user (the request is an invitee
 * signup), so this delegates to the axis-agnostic [RateLimiter.tryAcquireByKey] —
 * NOT the per-user `tryAcquire` (whose userId drives WIB-stagger + per-user
 * telemetry). Mirrors the [id.nearyou.app.follow.FollowRateLimiter] wrapper shape;
 * Redis-backed in production, [InMemoryRateLimiter] default for tests.
 *
 * Key `{scope:rate_referral_ticket}:{inviter:<uuid>}` is the strict two-segment
 * hash-tag form (RedisHashTagRule); the scope does NOT end in `_day`, so it stays
 * a sliding window (not a fixed daily window).
 */
class ReferralTicketRateLimiter(
    private val rateLimiter: RateLimiter = InMemoryRateLimiter(),
    val cap: Int = DEFAULT_CAP,
    val window: Duration = DEFAULT_WINDOW,
) {
    fun tryAcquire(inviterId: UUID): Outcome =
        when (val outcome = rateLimiter.tryAcquireByKey(keyFor(inviterId), cap, window)) {
            is RateLimiter.Outcome.Allowed -> Outcome.Allowed(remaining = outcome.remaining)
            is RateLimiter.Outcome.RateLimited -> Outcome.RateLimited(retryAfterSeconds = outcome.retryAfterSeconds)
        }

    sealed interface Outcome {
        data class Allowed(val remaining: Int) : Outcome

        data class RateLimited(val retryAfterSeconds: Long) : Outcome
    }

    companion object {
        const val DEFAULT_CAP: Int = 3
        val DEFAULT_WINDOW: Duration = Duration.ofDays(7)

        /** Strict hash-tag form; simple-name interpolation only (RedisHashTagRule). */
        fun keyFor(inviterId: UUID): String = "{scope:rate_referral_ticket}:{inviter:$inviterId}"
    }
}
