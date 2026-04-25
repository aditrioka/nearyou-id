package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.writeText

class RedisHashTagRuleTest : StringSpec({

    val rule = RedisHashTagRule()

    fun writeKtFile(
        fileName: String,
        code: String,
        underTest: Boolean = false,
    ) = run {
        val root = Files.createTempDirectory("detekt-redis-hashtag-")
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

    "legacy `rate:user:` literal fires" {
        val code =
            """
            package id.nearyou.app.engagement

            class T {
                fun key(userId: String): String = "rate:user:${'$'}userId"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "legacy `rate:guest_issue:ip:` literal fires" {
        val code =
            """
            package id.nearyou.app.auth

            class T {
                fun key(ip: String): String = "rate:guest_issue:ip:${'$'}ip"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "malformed `{scope:` with unwrapped second segment fires" {
        val code =
            """
            package id.nearyou.app.engagement

            class T {
                fun key(userId: String): String = "{scope:rate_like_day}:user:${'$'}userId"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "malformed `{scope:` with no second segment fires" {
        val code =
            """
            package id.nearyou.app.engagement

            class T {
                fun key(): String = "{scope:rate_like_day}"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ---- negative-pass cases ----

    "conforming `{scope:rate_like_day}:{user:userId}` literal passes" {
        val code =
            """
            package id.nearyou.app.engagement

            class T {
                fun key(userId: String): String = "{scope:rate_like_day}:{user:${'$'}userId}"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "conforming `{scope:rate_like_burst}:{user:id}` literal passes" {
        val code =
            """
            package id.nearyou.app.engagement

            class T {
                fun key(userId: String): String = "{scope:rate_like_burst}:{user:${'$'}userId}"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "conforming V9 `{scope:rate_report}:{user:id}` literal passes" {
        // Verify the V9 precedent shape passes the strict pattern.
        val code =
            """
            package id.nearyou.app.moderation

            class T {
                fun key(userId: String): String = "{scope:rate_report}:{user:${'$'}userId}"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "non-rate-limit string literal passes (unrelated SQL)" {
        val code =
            """
            package id.nearyou.app.repo

            class T {
                fun q(): String = "SELECT id FROM users WHERE id = ?"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "non-rate-limit string literal passes (file path)" {
        val code =
            """
            package id.nearyou.app.io

            class T {
                fun path(): String = "/tmp/foo.txt"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "test-path file with `rate:legacy` literal passes" {
        val code =
            """
            package id.nearyou.app.engagement.tests

            class LikeRateLimitTest {
                fun key(userId: String): String = "rate:user:${'$'}userId"
            }
            """.trimIndent()
        rule.lint(writeKtFile("LikeRateLimitTest.kt", code, underTest = true)).shouldBeEmpty()
    }

    "@AllowRawRedisKey with non-empty reason suppresses" {
        val code =
            """
            package id.nearyou.app.engagement

            annotation class AllowRawRedisKey(val reason: String)

            class T {
                @AllowRawRedisKey("legacy backfill — reviewed in PR #42")
                fun key(userId: String): String = "rate:legacy:${'$'}userId"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "@AllowRawRedisKey with empty-string reason still fires" {
        val code =
            """
            package id.nearyou.app.engagement

            annotation class AllowRawRedisKey(val reason: String)

            class T {
                @AllowRawRedisKey("")
                fun key(userId: String): String = "rate:legacy:${'$'}userId"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "synthetic-file harness via id.nearyou.test.fixtures package passes" {
        val code =
            """
            package id.nearyou.test.fixtures.redis

            class T {
                fun key(userId: String): String = "rate:legacy:${'$'}userId"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "rule registered in NearYouRuleSetProvider" {
        val provider = NearYouRuleSetProvider()
        val ruleSet = provider.instance(io.gitlab.arturbosch.detekt.api.Config.empty)
        val rules = ruleSet.rules.map { it::class.simpleName }
        rules.contains("RedisHashTagRule") shouldBe true
    }
})
