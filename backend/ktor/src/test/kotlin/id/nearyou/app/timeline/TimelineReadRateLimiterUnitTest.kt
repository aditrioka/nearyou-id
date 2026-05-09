package id.nearyou.app.timeline

import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Pure-unit coverage for the helpers + invariants of [TimelineReadRateLimiter] that
 * don't need a database or Redis container — sanitization, key shape, post-increment
 * loop count, and the Premium short-circuits.
 *
 * The full HTTP behaviour (32 spec scenarios) lives in [TimelineReadRateLimitTest]
 * which boots Ktor against the test DB. This unit class is the first line of defense.
 */
class TimelineReadRateLimiterUnitTest : StringSpec({

    val freeUser = UUID.fromString("11111111-1111-4111-8111-111111111111")
    val premiumUser = UUID.fromString("22222222-2222-4222-8222-222222222222")
    val premiumBillingUser = UUID.fromString("33333333-3333-4333-8333-333333333333")
    val sid = "4f8b9c1e-2d3a-4b5c-9d1e-7f8a9b0c1d2e"

    fun principal(
        userId: UUID,
        status: String,
    ) = UserPrincipal(
        userId = userId,
        tokenVersion = 0,
        subscriptionStatus = status,
        isShadowBanned = false,
    )

    // ---- 2.2 SessionIdSanitizer behaviour ----

    "sanitizeSessionId — null returns no-session" {
        TimelineReadRateLimiter.sanitizeSessionId(null) shouldBe TimelineReadRateLimiter.NO_SESSION
    }

    "sanitizeSessionId — empty string returns no-session" {
        TimelineReadRateLimiter.sanitizeSessionId("") shouldBe TimelineReadRateLimiter.NO_SESSION
    }

    "sanitizeSessionId — valid UUIDv4 returned unchanged" {
        TimelineReadRateLimiter.sanitizeSessionId(sid) shouldBe sid
    }

    "sanitizeSessionId — length 65 falls back" {
        val too =
            buildString {
                repeat(65) { append('a') }
            }
        TimelineReadRateLimiter.sanitizeSessionId(too) shouldBe TimelineReadRateLimiter.NO_SESSION
    }

    "sanitizeSessionId — closing brace falls back" {
        TimelineReadRateLimiter.sanitizeSessionId("foo}bar") shouldBe TimelineReadRateLimiter.NO_SESSION
    }

    "sanitizeSessionId — opening brace falls back" {
        TimelineReadRateLimiter.sanitizeSessionId("foo{bar") shouldBe TimelineReadRateLimiter.NO_SESSION
    }

    "sanitizeSessionId — colon falls back" {
        TimelineReadRateLimiter.sanitizeSessionId("foo:bar") shouldBe TimelineReadRateLimiter.NO_SESSION
    }

    "sanitizeSessionId — whitespace falls back" {
        TimelineReadRateLimiter.sanitizeSessionId("foo bar") shouldBe TimelineReadRateLimiter.NO_SESSION
    }

    "sanitizeSessionId — control char falls back" {
        TimelineReadRateLimiter.sanitizeSessionId("foo\u0001bar") shouldBe TimelineReadRateLimiter.NO_SESSION
    }

    "sanitizeSessionId — exact length-64 boundary admitted unchanged" {
        val sixtyFour =
            buildString {
                repeat(64) { append('a') }
            }
        TimelineReadRateLimiter.sanitizeSessionId(sixtyFour) shouldBe sixtyFour
    }

    // ---- 3.2 (key shape — also enforced by RedisHashTagRule fixtures in :lint) ----

    "rollingKey — strict hash-tag pattern" {
        val key = TimelineReadRateLimiter.rollingKey(freeUser)
        key shouldBe "{scope:rate_timeline_rolling}:{user:$freeUser}"
        // The strict regex from `RedisHashTagRule` MUST match — the rule fixtures in
        // `:lint:detekt-rules` enforce this for the rule itself; we re-assert here so
        // a future rename to either side trips both gates.
        key.matches(STRICT_HASH_TAG_PATTERN) shouldBe true
    }

    "sessionKey — strict hash-tag pattern with composite axis value" {
        val key = TimelineReadRateLimiter.sessionKey(freeUser, sid)
        key shouldBe "{scope:rate_timeline_session}:{session:${freeUser}__$sid}"
        key.matches(STRICT_HASH_TAG_PATTERN) shouldBe true
    }

    "sessionKey — no-session fallback also matches strict pattern" {
        val key = TimelineReadRateLimiter.sessionKey(freeUser, TimelineReadRateLimiter.NO_SESSION)
        key shouldBe "{scope:rate_timeline_session}:{session:${freeUser}__no-session}"
        key.matches(STRICT_HASH_TAG_PATTERN) shouldBe true
    }

    // ---- 1.5 / 1.6 / 4.4 — Premium short-circuit (no Redis calls) ----

    "preCheck — premium_active issues zero Redis calls" {
        runBlocking {
            val spy = SpyLimiter(InMemoryRateLimiter())
            val limiter = TimelineReadRateLimiter(spy)
            val outcome = limiter.preCheck(principal(premiumUser, "premium_active"), sid)
            outcome shouldBe TimelineReadRateLimiter.PreCheckOutcome.Admit(softCapReached = false)
            spy.acquireKeys().shouldBeEmpty()
        }
    }

    "preCheck — premium_billing_retry issues zero Redis calls" {
        runBlocking {
            val spy = SpyLimiter(InMemoryRateLimiter())
            val limiter = TimelineReadRateLimiter(spy)
            val outcome = limiter.preCheck(principal(premiumBillingUser, "premium_billing_retry"), sid)
            outcome shouldBe TimelineReadRateLimiter.PreCheckOutcome.Admit(softCapReached = false)
            spy.acquireKeys().shouldBeEmpty()
        }
    }

    "postIncrement — premium_active issues zero Redis calls regardless of postCount" {
        runBlocking {
            val spy = SpyLimiter(InMemoryRateLimiter())
            val limiter = TimelineReadRateLimiter(spy)
            limiter.postIncrement(principal(premiumUser, "premium_active"), sid, postCount = 30)
            spy.acquireKeys().shouldBeEmpty()
        }
    }

    "postIncrement — premium_billing_retry issues zero Redis calls regardless of postCount" {
        runBlocking {
            val spy = SpyLimiter(InMemoryRateLimiter())
            val limiter = TimelineReadRateLimiter(spy)
            limiter.postIncrement(principal(premiumBillingUser, "premium_billing_retry"), sid, postCount = 30)
            spy.acquireKeys().shouldBeEmpty()
        }
    }

    // ---- 4.5 — post-increment loop count math ----

    "postIncrement — postCount=30 issues exactly 29 rolling + 29 session calls" {
        runBlocking {
            val spy = SpyLimiter(InMemoryRateLimiter())
            val limiter = TimelineReadRateLimiter(spy)
            limiter.postIncrement(principal(freeUser, "free"), sid, postCount = 30)
            val keys = spy.acquireKeys()
            keys.count { it == TimelineReadRateLimiter.rollingKey(freeUser) } shouldBe 29
            keys.count { it == TimelineReadRateLimiter.sessionKey(freeUser, sid) } shouldBe 29
            keys.size shouldBe 58
        }
    }

    "postIncrement — postCount=0 issues zero calls" {
        runBlocking {
            val spy = SpyLimiter(InMemoryRateLimiter())
            val limiter = TimelineReadRateLimiter(spy)
            limiter.postIncrement(principal(freeUser, "free"), sid, postCount = 0)
            spy.acquireKeys().shouldBeEmpty()
        }
    }

    "postIncrement — postCount=1 issues zero additional calls" {
        runBlocking {
            val spy = SpyLimiter(InMemoryRateLimiter())
            val limiter = TimelineReadRateLimiter(spy)
            limiter.postIncrement(principal(freeUser, "free"), sid, postCount = 1)
            spy.acquireKeys().shouldBeEmpty()
        }
    }

    // ---- 4.1 / 4.2 — pre-check ordering: rolling first, then session ----

    "preCheck — Free hot path consults rolling THEN session (in that order)" {
        runBlocking {
            val spy = SpyLimiter(InMemoryRateLimiter())
            val limiter = TimelineReadRateLimiter(spy)
            val outcome = limiter.preCheck(principal(freeUser, "free"), sid)
            outcome shouldBe TimelineReadRateLimiter.PreCheckOutcome.Admit(softCapReached = false)
            val keys = spy.acquireKeys()
            keys.size shouldBe 2
            keys[0] shouldBe TimelineReadRateLimiter.rollingKey(freeUser)
            keys[1] shouldBe TimelineReadRateLimiter.sessionKey(freeUser, sid)
        }
    }
})

/**
 * The strict hash-tag pattern enforced by `RedisHashTagRule`. Duplicated here so the
 * unit assertion is independent of the lint module's classpath; the lint-rule
 * fixtures in `:lint:detekt-rules` enforce the same regex.
 */
private val STRICT_HASH_TAG_PATTERN: Regex = Regex("""^\{scope:[^{}]+}:\{[^{}:]+:[^{}]+}$""")

/**
 * Records every `tryAcquire` call's key so tests can assert ordering, key shape, and
 * call count. Delegates to a real limiter for the outcome — typically the
 * deterministic [InMemoryRateLimiter].
 */
private class SpyLimiter(val delegate: RateLimiter) : RateLimiter {
    private val acquires = ConcurrentLinkedQueue<String>()

    override fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        acquires.add(key)
        return delegate.tryAcquire(userId, key, capacity, ttl)
    }

    override fun tryAcquireByKey(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome = delegate.tryAcquireByKey(key, capacity, ttl)

    override fun releaseMostRecent(
        userId: UUID,
        key: String,
    ) {
        delegate.releaseMostRecent(userId, key)
    }

    fun acquireKeys(): List<String> = acquires.toList()
}
