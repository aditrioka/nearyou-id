package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.writeText

class RateLimitTtlRuleTest : StringSpec({

    val rule = RateLimitTtlRule()

    fun writeKtFile(
        fileName: String,
        code: String,
        underTest: Boolean = false,
    ) = run {
        val root = Files.createTempDirectory("detekt-rl-ttl-")
        val dir =
            if (underTest) {
                val nested = root.resolve("src").resolve("test").resolve("kotlin")
                Files.createDirectories(nested)
                nested
            } else {
                root
            }
        val path = dir.resolve(fileName)
        path.writeText(code)
        path
    }

    // ---- positive-fail cases ----

    "daily-key with hardcoded Duration.ofDays(1) ttl fires" {
        val code =
            """
            package id.nearyou.app.engagement

            import java.time.Duration
            import java.util.UUID

            class T {
                fun limiter(): Any = object {
                    fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                }

                fun call(u: UUID) {
                    limiter() as? Any
                    val l = object {
                        fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                    }
                    l.tryAcquire(u, "{scope:rate_like_day}:{user:${'$'}u}", 10, Duration.ofDays(1))
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "daily-key (substring _day}) with hardcoded ttl fires" {
        val code =
            """
            package id.nearyou.app.engagement

            import java.time.Duration
            import java.util.UUID

            fun call(u: UUID) {
                val l = object {
                    fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                }
                l.tryAcquire(u, "{scope:rate_post_day}:{user:${'$'}u}", 5, Duration.ofHours(24))
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "daily-key with named arguments + hardcoded ttl fires" {
        val code =
            """
            package id.nearyou.app.engagement

            import java.time.Duration
            import java.util.UUID

            fun call(u: UUID) {
                val l = object {
                    fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                }
                l.tryAcquire(
                    userId = u,
                    key = "{scope:rate_like_day}:{user:${'$'}u}",
                    capacity = 10,
                    ttl = Duration.ofDays(1),
                )
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ---- negative-pass cases ----

    "daily-key with computeTTLToNextReset passes" {
        val code =
            """
            package id.nearyou.app.engagement

            import java.time.Duration
            import java.util.UUID

            fun computeTTLToNextReset(u: UUID): Duration = Duration.ofDays(1)

            fun call(u: UUID) {
                val l = object {
                    fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                }
                l.tryAcquire(u, "{scope:rate_like_day}:{user:${'$'}u}", 10, computeTTLToNextReset(u))
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "burst-key (no _day marker) with hardcoded ttl passes" {
        val code =
            """
            package id.nearyou.app.engagement

            import java.time.Duration
            import java.util.UUID

            fun call(u: UUID) {
                val l = object {
                    fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                }
                l.tryAcquire(u, "{scope:rate_like_burst}:{user:${'$'}u}", 500, Duration.ofHours(1))
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "non-tryAcquire callee with daily-key + hardcoded ttl passes" {
        // The rule is keyed off the callee name; calls to functions named anything
        // other than `tryAcquire` are out of scope even if the key text would match.
        val code =
            """
            package id.nearyou.app.engagement

            import java.time.Duration
            import java.util.UUID

            fun call(u: UUID) {
                val l = object {
                    fun unrelated(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                }
                l.unrelated(u, "{scope:rate_like_day}:{user:${'$'}u}", 10, Duration.ofDays(1))
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "test-path file with daily-key + hardcoded ttl passes" {
        val code =
            """
            package id.nearyou.app.engagement.tests

            import java.time.Duration
            import java.util.UUID

            class LikeRateLimitTest {
                fun call(u: UUID) {
                    val l = object {
                        fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                    }
                    l.tryAcquire(u, "{scope:rate_like_day}:{user:${'$'}u}", 10, Duration.ofDays(1))
                }
            }
            """.trimIndent()
        rule.lint(writeKtFile("LikeRateLimitTest.kt", code, underTest = true)).shouldBeEmpty()
    }

    "@AllowDailyTtlOverride with non-empty reason on enclosing function suppresses" {
        val code =
            """
            package id.nearyou.app.engagement

            import java.time.Duration
            import java.util.UUID

            annotation class AllowDailyTtlOverride(val reason: String)

            @AllowDailyTtlOverride("benchmark fixture — reviewed in PR #42")
            fun call(u: UUID) {
                val l = object {
                    fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                }
                l.tryAcquire(u, "{scope:rate_like_day}:{user:${'$'}u}", 10, Duration.ofDays(1))
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "@AllowDailyTtlOverride with empty-string reason still fires" {
        val code =
            """
            package id.nearyou.app.engagement

            import java.time.Duration
            import java.util.UUID

            annotation class AllowDailyTtlOverride(val reason: String)

            @AllowDailyTtlOverride("")
            fun call(u: UUID) {
                val l = object {
                    fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                }
                l.tryAcquire(u, "{scope:rate_like_day}:{user:${'$'}u}", 10, Duration.ofDays(1))
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "synthetic-file harness via id.nearyou.test.fixtures package passes" {
        val code =
            """
            package id.nearyou.test.fixtures.ratelimit

            import java.time.Duration
            import java.util.UUID

            fun call(u: UUID) {
                val l = object {
                    fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Any = Any()
                }
                l.tryAcquire(u, "{scope:rate_like_day}:{user:${'$'}u}", 10, Duration.ofDays(1))
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "rule registered in NearYouRuleSetProvider" {
        val provider = NearYouRuleSetProvider()
        val ruleSet = provider.instance(io.gitlab.arturbosch.detekt.api.Config.empty)
        val rules = ruleSet.rules.map { it::class.simpleName }
        rules.contains("RateLimitTtlRule") shouldBe true
    }
})
