package id.nearyou.app.image

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.util.UUID

class ImageUploadRateLimiterTest : StringSpec({

    "throttle allows the first upload then blocks the second (1 per 60s)" {
        val limiter = ImageUploadRateLimiter(InMemoryRateLimiter())
        val user = UUID.randomUUID()
        limiter.tryAcquireThrottle(user).shouldBeInstanceOf<ImageUploadRateLimiter.Outcome.Allowed>()
        limiter.tryAcquireThrottle(user).shouldBeInstanceOf<ImageUploadRateLimiter.Outcome.RateLimited>()
    }

    "daily quota allows up to the cap then blocks (fixed window)" {
        val limiter = ImageUploadRateLimiter(InMemoryRateLimiter())
        val user = UUID.randomUUID()
        val ttl = Duration.ofHours(5)
        repeat(3) {
            limiter.tryAcquireDaily(user, cap = 3, ttl = ttl).shouldBeInstanceOf<ImageUploadRateLimiter.Outcome.Allowed>()
        }
        limiter.tryAcquireDaily(user, cap = 3, ttl = ttl).shouldBeInstanceOf<ImageUploadRateLimiter.Outcome.RateLimited>()
    }

    "the two limiters use distinct, slot-stable hash-tagged keys" {
        val user = UUID.fromString("00000000-0000-0000-0000-000000000001")
        ImageUploadRateLimiter.dailyKey(user) shouldBeKey "{scope:rate_image_upload_day}:{user:$user}"
        ImageUploadRateLimiter.throttleKey(user) shouldBeKey "{scope:rate_image_upload_throttle}:{user:$user}"
    }
})

private infix fun String.shouldBeKey(expected: String) {
    require(this == expected) { "expected key '$expected' but was '$this'" }
}
