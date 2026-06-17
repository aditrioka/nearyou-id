package id.nearyou.app.referral

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.time.Duration
import java.util.UUID

/**
 * Pure unit test (no DB) pinning the burst-limiter key shape + defaults so the
 * as-built `rate_referral_ticket` scope can't silently drift from the
 * `RedisHashTagRule` strict contract.
 */
class ReferralTicketRateLimiterTest : StringSpec({
    "keyFor is the strict two-segment hash-tag shape and stays a sliding window" {
        val key = ReferralTicketRateLimiter.keyFor(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        // Matches RedisHashTagRule.STRICT_HASH_TAG_PATTERN ({scope:NAME}:{AXIS:VAL}).
        key.shouldMatch(Regex("""^\{scope:rate_referral_ticket}:\{inviter:[0-9a-f-]+}$"""))
        // Scope must NOT end in `_day` (that marker = fixed daily window); referral is sliding.
        key.endsWith("_day}") shouldBe false
    }

    "defaults are cap 3 over a 7-day window (docs/01 Max 3 referrals/week)" {
        val limiter = ReferralTicketRateLimiter()
        limiter.cap shouldBe 3
        limiter.window shouldBe Duration.ofDays(7)
    }
})
