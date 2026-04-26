package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class RawXForwardedForRuleTest : StringSpec({

    val rule = RawXForwardedForRule()

    "header read with X-Forwarded-For literal fires" {
        val code =
            """
            package id.nearyou.app.somefeature

            class Handler {
                fun ip(): String? = "X-Forwarded-For".let { null }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "case-insensitive match fires on lowercase x-forwarded-for" {
        val code =
            """
            package id.nearyou.app.something

            val raw = "x-forwarded-for"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "ClientIpExtractor.kt is allow-listed" {
        val dir = Files.createTempDirectory("xff-rule-extractor")
        val path = dir.resolve("ClientIpExtractor.kt")
        path.writeText(
            """
            package id.nearyou.app.common

            fun resolve() = "X-Forwarded-For"
            """.trimIndent(),
        )
        try {
            rule.lint(path).shouldBeEmpty()
        } finally {
            cleanupDir(dir)
        }
    }

    "consumer file outside ClientIpExtractor.kt fails" {
        val dir = Files.createTempDirectory("xff-rule-consumer")
        val path = dir.resolve("OtherHandler.kt")
        path.writeText(
            """
            package id.nearyou.app.consumer

            fun look() = "X-Forwarded-For"
            """.trimIndent(),
        )
        try {
            rule.lint(path) shouldHaveSize 1
        } finally {
            cleanupDir(dir)
        }
    }

    "unrelated string does not fire" {
        val code =
            """
            package id.nearyou.app.something

            val msg = "Forwarded message"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})

private fun cleanupDir(dir: Path) {
    Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
}
