package id.nearyou.app.auth

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.infra.otel.IpHasher
import id.nearyou.app.infra.redis.NoOpRateLimiter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit coverage for the audit-#214 auth-endpoint limiter: key-shape conformance,
 * per-endpoint cap behaviour, and NoOp fail-soft parity. Route-level 429 + Retry-After
 * + no-work-performed behaviour lives in `AuthEndpointRateLimitTest` (signin/refresh) and
 * `SignupFlowTest` (signup, DB-tagged harness).
 */
class AuthRateLimiterTest : StringSpec({

    "keys conform to the two-segment hash-tag standard with literal scopes" {
        val ip = "deadbeefdeadbeef"
        AuthRateLimiter.signinKey(ip) shouldBe "{scope:rate_auth_signin}:{ip:deadbeefdeadbeef}"
        AuthRateLimiter.signupKey(ip) shouldBe "{scope:rate_auth_signup}:{ip:deadbeefdeadbeef}"
        AuthRateLimiter.refreshKey(ip) shouldBe "{scope:rate_auth_refresh}:{ip:deadbeefdeadbeef}"
    }

    "keys carry the hashed IP, never the raw client IP" {
        val raw = "203.0.113.7"
        val hashed = IpHasher.hash(raw)
        // 16-hex value (OtelForbiddenAttributeRule canonical shape) — not the raw dotted-quad.
        hashed shouldMatch Regex("[0-9a-f]{16}")
        listOf(
            AuthRateLimiter.signinKey(hashed),
            AuthRateLimiter.signupKey(hashed),
            AuthRateLimiter.refreshKey(hashed),
        ).forEach { key ->
            key shouldContain hashed
            key shouldNotContain raw
        }
    }

    "no scope uses the daily fixed-window marker (all burst / sliding-window keys)" {
        val ip = "abcd1234abcd1234"
        listOf(
            AuthRateLimiter.signinKey(ip),
            AuthRateLimiter.signupKey(ip),
            AuthRateLimiter.refreshKey(ip),
        ).forEach { it shouldNotContain "_day}" }
    }

    "signin admits up to the cap then rate-limits with a positive Retry-After" {
        val limiter = AuthRateLimiter(InMemoryRateLimiter(), signinCap = 2)
        val ip = IpHasher.hash("198.51.100.4")
        limiter.trySignin(ip).shouldBeInstanceOf<AuthRateLimiter.Outcome.Allowed>()
        limiter.trySignin(ip).shouldBeInstanceOf<AuthRateLimiter.Outcome.Allowed>()
        val limited = limiter.trySignin(ip).shouldBeInstanceOf<AuthRateLimiter.Outcome.RateLimited>()
        limited.retryAfterSeconds shouldBeGreaterThanOrEqualTo 1L
    }

    "signup admits up to the cap then rate-limits" {
        val limiter = AuthRateLimiter(InMemoryRateLimiter(), signupCap = 1)
        val ip = IpHasher.hash("198.51.100.5")
        limiter.trySignup(ip).shouldBeInstanceOf<AuthRateLimiter.Outcome.Allowed>()
        limiter.trySignup(ip).shouldBeInstanceOf<AuthRateLimiter.Outcome.RateLimited>()
    }

    "refresh admits up to the cap then rate-limits" {
        val limiter = AuthRateLimiter(InMemoryRateLimiter(), refreshCap = 1)
        val ip = IpHasher.hash("198.51.100.6")
        limiter.tryRefresh(ip).shouldBeInstanceOf<AuthRateLimiter.Outcome.Allowed>()
        limiter.tryRefresh(ip).shouldBeInstanceOf<AuthRateLimiter.Outcome.RateLimited>()
    }

    "distinct IP hashes do not share a bucket" {
        val limiter = AuthRateLimiter(InMemoryRateLimiter(), signinCap = 1)
        limiter.trySignin(IpHasher.hash("203.0.113.1")).shouldBeInstanceOf<AuthRateLimiter.Outcome.Allowed>()
        // A different IP gets its own fresh bucket — still admitted at the same cap.
        limiter.trySignin(IpHasher.hash("203.0.113.2")).shouldBeInstanceOf<AuthRateLimiter.Outcome.Allowed>()
    }

    "NoOp limiter always admits all three endpoints (fail-soft parity)" {
        val limiter = AuthRateLimiter(NoOpRateLimiter(), signinCap = 1, signupCap = 1, refreshCap = 1)
        val ip = IpHasher.hash("192.0.2.9")
        repeat(5) { limiter.trySignin(ip).shouldBeInstanceOf<AuthRateLimiter.Outcome.Allowed>() }
        repeat(5) { limiter.trySignup(ip).shouldBeInstanceOf<AuthRateLimiter.Outcome.Allowed>() }
        repeat(5) { limiter.tryRefresh(ip).shouldBeInstanceOf<AuthRateLimiter.Outcome.Allowed>() }
    }
})
