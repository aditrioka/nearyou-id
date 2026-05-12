package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class ContentWriteRequiresModerationLintTest : StringSpec({

    val rule = ContentWriteRequiresModerationRule()

    /**
     * Write a synthetic file under a controlled physical path so the rule's
     * `/src/test/` path-substring allowlist sees the simulated location.
     * `pathSegments = listOf("src", "test", "kotlin")` produces a file under
     * `<root>/src/test/kotlin/<name>.kt` — the `/src/test/` substring then matches.
     */
    fun writeKtFile(
        fileName: String,
        code: String,
        pathSegments: List<String> = emptyList(),
    ): Path {
        val root = Files.createTempDirectory("detekt-content-write-")
        val dir =
            if (pathSegments.isEmpty()) {
                root
            } else {
                val nested = pathSegments.fold(root) { acc, seg -> acc.resolve(seg) }
                Files.createDirectories(nested)
                nested
            }
        val path = dir.resolve(fileName)
        path.writeText(code)
        return path
    }

    // ============================================================
    // Task 4.2 — Positive cases (1, 2, 3): inlined SQL-literal INSERT no-moderate
    // ============================================================

    "positive: inlined INSERT INTO posts (..., content, ...) without preceding moderate fires" {
        val code =
            """
            package id.nearyou.app.post

            class T {
                fun handler() {
                    val sql = "INSERT INTO posts (id, author_id, content) VALUES (?, ?, ?)"
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "positive: inlined INSERT INTO post_replies (..., content, ...) without preceding moderate fires" {
        val code =
            """
            package id.nearyou.app.engagement

            class T {
                fun handler() {
                    val sql = "INSERT INTO post_replies (id, post_id, author_id, content) VALUES (?, ?, ?, ?)"
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "positive: inlined INSERT INTO chat_messages (..., content, ...) without preceding moderate fires" {
        val code =
            """
            package id.nearyou.app.chat

            class T {
                fun handler() {
                    val sql = "INSERT INTO chat_messages (id, conversation_id, sender_id, content) VALUES (?, ?, ?, ?)"
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Task 4.3 — Positive cases (4, 5, 6): service-method call no-moderate
    // ============================================================

    "positive: service-method posts.create(...) without preceding moderate fires" {
        val code =
            """
            package id.nearyou.app.post

            class T {
                val posts: Any = Any()
                fun handler(content: String) {
                    posts.create(content)
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "positive: service-method replies.insertInTx(...) without preceding moderate fires" {
        val code =
            """
            package id.nearyou.app.engagement

            class T {
                val replies: Any = Any()
                fun handler(content: String) {
                    replies.insertInTx(content)
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "positive: service-method repository.sendMessage(...) without preceding moderate fires" {
        val code =
            """
            package id.nearyou.app.chat

            class T {
                val repository: Any = Any()
                fun handler(content: String) {
                    repository.sendMessage(content)
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Task 4.4 — Negative case (7): write WITH preceding moderate
    // ============================================================

    "negative: inlined INSERT WITH preceding textModerator.moderate(content) passes" {
        val code =
            """
            package id.nearyou.app.post

            class T {
                val textModerator: Any = Any()
                val posts: Any = Any()
                fun handler(content: String) {
                    val verdict = textModerator.moderate(content)
                    val sql = "INSERT INTO posts (id, author_id, content) VALUES (?, ?, ?)"
                    posts.create(content)
                }
            }
            """.trimIndent()
        // Both the SQL-literal candidate AND the service-method candidate are preceded
        // by `textModerator.moderate(content)` in the same function body — zero findings.
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Task 4.5 — Annotation-bypass positive cases (8, 9, 10): each enumerated reason
    // ============================================================

    "annotation bypass: @AllowContentWriteWithoutModeration(\"tombstone\") passes" {
        val code =
            """
            package id.nearyou.app.post

            annotation class AllowContentWriteWithoutModeration(val reason: String)

            class T {
                @AllowContentWriteWithoutModeration("tombstone")
                fun replace() {
                    val sql = "INSERT INTO posts (id, content) VALUES (?, '[konten dihapus]')"
                }
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "annotation bypass: @AllowContentWriteWithoutModeration(\"admin_redaction\") passes" {
        val code =
            """
            package id.nearyou.app.chat

            annotation class AllowContentWriteWithoutModeration(val reason: String)

            class T {
                @AllowContentWriteWithoutModeration("admin_redaction")
                fun redact() {
                    val sql = "INSERT INTO chat_messages (id, content) VALUES (?, '[redacted]')"
                }
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "annotation bypass: @AllowContentWriteWithoutModeration(\"seed\") passes" {
        val code =
            """
            package id.nearyou.app.post

            annotation class AllowContentWriteWithoutModeration(val reason: String)

            class T {
                @AllowContentWriteWithoutModeration("seed")
                fun seed() {
                    val sql = "INSERT INTO posts (id, content) VALUES (?, 'seed content')"
                }
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Task 4.6 — Annotation-bypass empty-reason fails (11)
    // ============================================================

    "annotation bypass: empty-string reason still fires" {
        val code =
            """
            package id.nearyou.app.post

            annotation class AllowContentWriteWithoutModeration(val reason: String)

            class T {
                @AllowContentWriteWithoutModeration("")
                fun handler() {
                    val sql = "INSERT INTO posts (id, content) VALUES (?, ?)"
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Task 4.7 — Annotation-bypass non-enumerated reason fails (12)
    // ============================================================

    "annotation bypass: non-enumerated reason fires AND message names the allowed reasons" {
        val code =
            """
            package id.nearyou.app.post

            annotation class AllowContentWriteWithoutModeration(val reason: String)

            class T {
                @AllowContentWriteWithoutModeration("custom_reason")
                fun handler() {
                    val sql = "INSERT INTO posts (id, content) VALUES (?, ?)"
                }
            }
            """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        val message = findings[0].message
        message shouldContain "tombstone"
        message shouldContain "admin_redaction"
        message shouldContain "seed"
    }

    // ============================================================
    // Task 4.8 — Allowlist fixtures (13, 14)
    // ============================================================

    "allowlist: file under /src/test/ does NOT fire" {
        val code =
            """
            package id.nearyou.app.post

            class T {
                fun handler() {
                    val sql = "INSERT INTO posts (id, content) VALUES (?, ?)"
                }
            }
            """.trimIndent()
        val path = writeKtFile("PostFixture.kt", code, listOf("src", "test", "kotlin"))
        try {
            rule.lint(path).shouldBeEmpty()
        } finally {
            cleanupDir(path.parent.parent.parent.parent)
        }
    }

    "allowlist: package-FQN fallback id.nearyou.test.fixtures.* does NOT fire" {
        val code =
            """
            package id.nearyou.test.fixtures.contentwrite

            class T {
                fun handler() {
                    val sql = "INSERT INTO posts (id, content) VALUES (?, ?)"
                }
            }
            """.trimIndent()
        // No virtualFilePath via `lint(String)` overload — package-FQN fallback catches it.
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Task 4.9 — Non-content-table INSERT does NOT fire (15)
    // ============================================================

    "negative: INSERT INTO follows (non-content table) does NOT fire" {
        val code =
            """
            package id.nearyou.app.feature

            class T {
                fun handler() {
                    val sql = "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)"
                }
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Task 4.10 — Content-table INSERT without content column does NOT fire (16)
    // ============================================================

    "negative: INSERT INTO posts WITHOUT content column does NOT fire" {
        val code =
            """
            package id.nearyou.app.feature

            class T {
                fun handler() {
                    val sql = "INSERT INTO posts (id, view_count) VALUES (?, ?)"
                }
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Task 4.11 — Composition with BlockExclusionJoinRule (17): independent findings
    // ============================================================

    "composition: fixture triggers BOTH rules independently, no cross-suppression" {
        val blockRule = BlockExclusionJoinRule()
        val code =
            """
            package id.nearyou.app.feature

            class T {
                fun handler() {
                    val insertSql = "INSERT INTO posts (id, author_id, content) VALUES (?, ?, ?)"
                    val selectSql = "SELECT id, content FROM posts WHERE author_id = ?"
                }
            }
            """.trimIndent()
        // ContentWriteRequiresModerationRule fires once on the INSERT (no preceding moderate).
        rule.lint(code) shouldHaveSize 1
        // BlockExclusionJoinRule fires once on the SELECT FROM posts (no user_blocks join).
        blockRule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Task 4.12 — Provider registration (18)
    // ============================================================

    "rule registered in NearYouRuleSetProvider" {
        val provider = NearYouRuleSetProvider()
        val ruleSet = provider.instance(io.gitlab.arturbosch.detekt.api.Config.empty)
        val rules = ruleSet.rules.map { it::class.simpleName }
        rules.contains("ContentWriteRequiresModerationRule") shouldBe true
    }
})

private fun cleanupDir(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
}
