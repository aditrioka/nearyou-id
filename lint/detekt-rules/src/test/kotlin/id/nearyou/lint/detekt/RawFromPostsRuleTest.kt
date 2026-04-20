package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

class RawFromPostsRuleTest : StringSpec({

    val rule = RawFromPostsRule()

    "Kotlin literal with 'FROM posts' in a non-allowed file fires" {
        val code =
            """
            package id.nearyou.app.timeline

            class TimelineRepository {
                fun all(): String = "SELECT id FROM posts WHERE created_at > NOW()"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "JOIN posts in Kotlin literal fires" {
        val code =
            """
            package id.nearyou.app.social

            fun q() = "SELECT u.* FROM users u JOIN posts p ON p.author_id = u.id"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "case-insensitive match fires on lowercase 'from posts'" {
        val code =
            """
            package id.nearyou.app.whatever

            fun q(): String = "select * from posts"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "multi-line string concatenation is caught" {
        val code =
            """
            package id.nearyou.app.worker

            val sql = "SELECT id\n" + "FROM posts WHERE author_id = ?"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "annotation @AllowRawPostsRead suppresses the check on the enclosing function" {
        val code =
            """
            package id.nearyou.app.legacy

            annotation class AllowRawPostsRead(val reason: String)

            @AllowRawPostsRead("legacy admin export — remove in Phase 3.5")
            fun legacy(): String = "SELECT * FROM posts"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "annotation on enclosing class suppresses" {
        val code =
            """
            package id.nearyou.app.legacy

            annotation class AllowRawPostsRead(val reason: String)

            @AllowRawPostsRead("see doc link")
            class LegacyPostsRepository {
                fun q() = "SELECT id FROM posts"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "unrelated string containing the word 'posts' does not fire" {
        val code =
            """
            package id.nearyou.app.something

            val msg = "Your post is one of many posts you can read."
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "FROM postscategory does not match (word-boundary)" {
        val code =
            """
            package id.nearyou.app.category

            val sql = "SELECT * FROM postscategory"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})
