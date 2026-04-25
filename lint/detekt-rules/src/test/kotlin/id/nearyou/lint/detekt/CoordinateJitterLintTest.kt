package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

private fun writeKtFile(
    fileName: String,
    code: String,
    underTest: Boolean = false,
): Path {
    val root = Files.createTempDirectory("detekt-coord-")
    // Some rule allowlists match on `/src/test/` substring in the physical path.
    // Simulate that here when the test needs to exercise the test-path gate.
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
    return path
}

class CoordinateJitterLintTest : StringSpec({

    val rule = CoordinateJitterRule()

    // ---- positive-fail cases ----

    "SELECT actual_location in non-allowed file fires" {
        val code =
            """
            package id.nearyou.app.timeline

            class T {
                fun q(): String = "SELECT id, actual_location FROM posts WHERE author_id = ?"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "INSERT outside post write-path fires (rule does not distinguish read/write)" {
        // A hypothetical `JdbcRandomRepository` (not on the write-path allowlist) that
        // INSERTs a row referencing actual_location must still fire. Allowlist by filename
        // prefix is the only sanctioned escape.
        val code =
            """
            package id.nearyou.app.random

            class T {
                fun q(): String =
                    "INSERT INTO posts (id, actual_location) VALUES (?, ?)"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "multi-line concatenation is caught (leftmost literal reports once)" {
        val code =
            """
            package id.nearyou.app.worker

            val sql = "SELECT id\n" + "FROM posts WHERE actual_location IS NOT NULL"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "case-insensitive match — ACTUAL_LOCATION fires" {
        val code =
            """
            package id.nearyou.app.timeline

            class T {
                fun q(): String = "SELECT ACTUAL_LOCATION FROM posts"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "false-positive-by-design: user-facing message mentioning the token fires" {
        // The rule is a grep over the token; a Kotlin string literal that mentions the
        // column name in a comment-y way still fires. Documented in the rule KDoc —
        // annotation bypass is the escape hatch if the literal is legitimate.
        val code =
            """
            package id.nearyou.app.ui

            val msg = "The actual_location field is hidden from non-admin users"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ---- allowlist pass cases ----

    "admin module exempt via package fallback" {
        val code =
            """
            package id.nearyou.app.admin

            class AdminGeoAudit {
                fun q(): String = "SELECT actual_location FROM posts WHERE id = ?"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "admin sub-package exempt" {
        val code =
            """
            package id.nearyou.app.admin.tools

            class GeoAuditTool {
                fun q(): String = "SELECT actual_location FROM posts LIMIT 10"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "test file path exempt (via /src/test/ substring)" {
        val code =
            """
            package id.nearyou.app.infra.db

            class MigrationV12SmokeTest {
                fun q(): String =
                    "INSERT INTO posts (id, actual_location) VALUES (?, ST_MakePoint(?, ?)::geography)"
            }
            """.trimIndent()
        rule.lint(writeKtFile("MigrationV12SmokeTest.kt", code, underTest = true)).shouldBeEmpty()
    }

    "JdbcPostRepository file-name prefix exempt" {
        val code =
            """
            package id.nearyou.app.infra.repo

            class JdbcPostRepository {
                fun insert(): String =
                    "INSERT INTO posts (id, author_id, content, display_location, actual_location) VALUES (?, ?, ?, ?, ?)"
            }
            """.trimIndent()
        rule.lint(writeKtFile("JdbcPostRepository.kt", code)).shouldBeEmpty()
    }

    "CreatePostService file-name prefix exempt" {
        val code =
            """
            package id.nearyou.app.post

            class CreatePostService {
                fun q(): String = "INSERT INTO posts (actual_location) VALUES (?)"
            }
            """.trimIndent()
        rule.lint(writeKtFile("CreatePostService.kt", code)).shouldBeEmpty()
    }

    "PostOwnContent file-name prefix exempt" {
        val code =
            """
            package id.nearyou.app.post.repository

            class PostOwnContentRepository {
                fun q(): String =
                    "SELECT actual_location FROM posts WHERE author_id = ?"
            }
            """.trimIndent()
        rule.lint(writeKtFile("PostOwnContentRepository.kt", code)).shouldBeEmpty()
    }

    "V9 Report*.kt under moderation module exempt" {
        // Mirrors RawFromPostsRule exemption for report-submission point-lookup
        // existence checks that must NOT go through visible_posts and may touch
        // actual_location for moderation context snapshots.
        val code =
            """
            package id.nearyou.app.moderation

            class ReportTargetResolver {
                fun q(): String =
                    "SELECT actual_location FROM posts WHERE id = ? AND deleted_at IS NULL"
            }
            """.trimIndent()
        rule.lint(writeKtFile("ReportTargetResolver.kt", code)).shouldBeEmpty()
    }

    // ---- annotation bypass ----

    "@AllowActualLocationRead on function suppresses" {
        val code =
            """
            package id.nearyou.app.legacy

            annotation class AllowActualLocationRead(val reason: String)

            @AllowActualLocationRead("admin debug tool — bypass reviewed in PR #42")
            fun q(): String = "SELECT actual_location FROM posts"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "@AllowActualLocationRead on enclosing class suppresses" {
        val code =
            """
            package id.nearyou.app.legacy

            annotation class AllowActualLocationRead(val reason: String)

            @AllowActualLocationRead("admin geo audit — reviewed in PR #42")
            class AdhocGeoAudit {
                fun q(): String = "WHERE actual_location IS NULL"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ---- sibling-rule composition (locks "visible_posts projecting actual_location" case) ----

    "FROM visible_posts projecting actual_location still fires" {
        // RawFromPostsRule passes this (it's using the view). But CoordinateJitterRule
        // fires — visible_posts inherits all columns including actual_location.
        val code =
            """
            package id.nearyou.app.timeline

            class T {
                fun q(): String =
                    "SELECT actual_location FROM visible_posts WHERE author_id = ?"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ---- negative controls ----

    "unrelated SQL without actual_location does NOT fire" {
        val code =
            """
            package id.nearyou.app.timeline

            class T {
                fun q(): String = "SELECT display_location FROM visible_posts"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "word-boundary: actual_location_foo does NOT fire" {
        // If someone names a column actual_location_foo (hypothetical), the regex's
        // word-boundary anchors should NOT match it. Guards against over-broad firing.
        val code =
            """
            package id.nearyou.app.timeline

            class T {
                fun q(): String = "SELECT actual_location_foo FROM something"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})
