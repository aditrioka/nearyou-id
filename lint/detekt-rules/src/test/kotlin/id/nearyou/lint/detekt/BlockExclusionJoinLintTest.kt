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
): Path {
    val dir = Files.createTempDirectory("detekt-blockex-")
    val path = dir.resolve(fileName)
    path.writeText(code)
    return path
}

class BlockExclusionJoinLintTest : StringSpec({

    val rule = BlockExclusionJoinRule()

    // ---- positive-fail cases (one per protected table) ----

    "FROM posts in non-allowed file fires" {
        val code =
            """
            package id.nearyou.app.timeline

            class T {
                fun q(): String = "SELECT id FROM posts WHERE author_id = ?"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "FROM visible_posts in non-allowed file fires" {
        val code =
            """
            package id.nearyou.app.timeline

            class T {
                fun q(): String = "SELECT id FROM visible_posts WHERE author_id = ?"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "FROM users in non-allowed file fires" {
        val code =
            """
            package id.nearyou.app.profile

            class T {
                fun q(): String = "SELECT username FROM users WHERE id = ?"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "FROM chat_messages in non-allowed file fires" {
        val code =
            """
            package id.nearyou.app.chat

            class T {
                fun q(): String = "SELECT id FROM chat_messages WHERE channel_id = ?"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "FROM post_replies in non-allowed file fires" {
        val code =
            """
            package id.nearyou.app.replies

            class T {
                fun q(): String = "SELECT id FROM post_replies WHERE post_id = ?"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "JOIN against a protected table fires" {
        val code =
            """
            package id.nearyou.app.social

            class T {
                fun q(): String = "SELECT u.* FROM something s JOIN users u ON s.user_id = u.id"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "multi-line concatenation is caught" {
        val code =
            """
            package id.nearyou.app.worker

            val sql = "SELECT id\n" + "FROM posts WHERE author_id = ?"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "single-direction join (only blocker_id) fires" {
        val code =
            """
            package id.nearyou.app.timeline

            class T {
                fun q(): String =
                    "SELECT id FROM visible_posts WHERE author_id NOT IN " +
                        "(SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "single-direction join (only blocked_id) fires" {
        val code =
            """
            package id.nearyou.app.timeline

            class T {
                fun q(): String =
                    "SELECT id FROM visible_posts WHERE author_id NOT IN " +
                        "(SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ---- positive-pass cases ----

    "query with both block-exclusion fragments passes" {
        val code =
            """
            package id.nearyou.app.timeline

            class T {
                fun q(): String =
                    "SELECT id FROM visible_posts WHERE " +
                        "author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?) " +
                        "AND author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "admin module exempt (via package fallback)" {
        // Path-based exemption requires `/app/admin/` in virtualFilePath; in lint(String)
        // tests the path is synthetic. The rule's package-name fallback handles this case.
        val code =
            """
            package id.nearyou.app.admin

            class AdminPostsRepository {
                fun q(): String = "SELECT id FROM visible_posts LIMIT 100"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "PostOwnContent file name pattern exempts the file" {
        val code =
            """
            package id.nearyou.app.post.repository

            class PostOwnContentRepository {
                fun q(): String = "SELECT * FROM posts WHERE author_id = ?"
            }
            """.trimIndent()
        rule.lint(writeKtFile("PostOwnContentRepository.kt", code)).shouldBeEmpty()
    }

    "UserOwn file name pattern exempts the file" {
        val code =
            """
            package id.nearyou.app.user.repository

            class UserOwnSettingsRepository {
                fun q(): String = "SELECT username FROM users WHERE id = ?"
            }
            """.trimIndent()
        rule.lint(writeKtFile("UserOwnSettingsRepository.kt", code)).shouldBeEmpty()
    }

    "@AllowMissingBlockJoin annotation suppresses the check" {
        val code =
            """
            package id.nearyou.app.legacy

            annotation class AllowMissingBlockJoin(val reason: String)

            @AllowMissingBlockJoin("aggregate analytics — no per-user surface")
            fun q(): String = "SELECT COUNT(*) FROM posts"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "annotation on enclosing class suppresses" {
        val code =
            """
            package id.nearyou.app.legacy

            annotation class AllowMissingBlockJoin(val reason: String)

            @AllowMissingBlockJoin("internal worker runs as system")
            class WorkerRepository {
                fun q(): String = "SELECT id FROM users WHERE token_version = 0"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "unrelated string mentioning posts and user_blocks does not fire" {
        val code =
            """
            package id.nearyou.app.something

            val msg = "User can manage posts and user_blocks via settings."
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "follows_is_deliberately_not_protected_table — SELECT passes" {
        // Encoding for the block-exclusion-lint ADDED requirement: `follows` is deliberately
        // NOT in the protected-table set. This fixture + the rule's KDoc together guard
        // against a future contributor adding `follows` out of misplaced completeness.
        val code =
            """
            package id.nearyou.app.follow.repository

            class T {
                fun q(): String = "SELECT follower_id FROM follows WHERE followee_id = ?"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "follows_is_deliberately_not_protected_table — INSERT passes" {
        val code =
            """
            package id.nearyou.app.follow.repository

            class T {
                fun q(): String =
                    "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT (follower_id, followee_id) DO NOTHING"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "follows_is_deliberately_not_protected_table — DELETE passes" {
        val code =
            """
            package id.nearyou.app.follow.repository

            class T {
                fun q(): String = "DELETE FROM follows WHERE follower_id = ? AND followee_id = ?"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "post_likes_is_deliberately_not_protected_table — count query passes" {
        // Encoding for the block-exclusion-lint ADDED requirement (V7): `post_likes` is
        // deliberately NOT in the protected-table set. The count query JOINs
        // `visible_users` for shadow-ban exclusion; viewer-block exclusion is
        // deliberately omitted as a privacy tradeoff (see post-likes spec).
        val code =
            """
            package id.nearyou.app.engagement

            class T {
                fun q(): String = "SELECT COUNT(*) FROM post_likes pl JOIN visible_users vu ON vu.id = pl.user_id WHERE pl.post_id = ?"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "post_likes_is_deliberately_not_protected_table — INSERT passes" {
        val code =
            """
            package id.nearyou.app.engagement

            class T {
                fun q(): String =
                    "INSERT INTO post_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT (post_id, user_id) DO NOTHING"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "post_likes_is_deliberately_not_protected_table — DELETE passes" {
        val code =
            """
            package id.nearyou.app.engagement

            class T {
                fun q(): String = "DELETE FROM post_likes WHERE post_id = ? AND user_id = ?"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ---- V8: reply-list reader fixtures (post-replies-v8 spec ADDED requirements) ----

    "reply_list_query_with_bidirectional_block_exclusion — passes" {
        // Canonical V8 reply-list literal from JdbcPostReplyRepository. Carries all four
        // lint tokens — `post_replies`, `user_blocks`, `blocker_id =`, `blocked_id =` —
        // AND joins `visible_users` for shadow-ban exclusion. Must NOT trip the rule.
        val code =
            """
            package id.nearyou.app.infra.repo

            class T {
                fun q(): String =
                    "SELECT pr.id, pr.post_id, pr.author_id, pr.content, pr.is_auto_hidden, pr.created_at, pr.updated_at " +
                        "FROM post_replies pr " +
                        "JOIN visible_users vu ON vu.id = pr.author_id " +
                        "WHERE pr.post_id = ? AND pr.deleted_at IS NULL " +
                        "AND (pr.is_auto_hidden = FALSE OR pr.author_id = ?) " +
                        "AND pr.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?) " +
                        "AND pr.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?) " +
                        "ORDER BY pr.created_at DESC, pr.id DESC LIMIT 31"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "reply_list_query_missing_blocked_id_fails — rule fires" {
        // Same canonical literal with the second NOT-IN subquery removed (`blocker_id =`
        // present, `blocked_id =` absent). Must trip BlockExclusionJoinRule.
        val code =
            """
            package id.nearyou.app.infra.repo

            class T {
                fun q(): String =
                    "SELECT pr.id FROM post_replies pr " +
                        "JOIN visible_users vu ON vu.id = pr.author_id " +
                        "WHERE pr.post_id = ? AND pr.deleted_at IS NULL " +
                        "AND pr.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?) " +
                        "ORDER BY pr.created_at DESC LIMIT 31"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "ReplyOwnContent file name pattern exempts the file (own-content bypass)" {
        val code =
            """
            package id.nearyou.app.engagement.repository

            class ReplyOwnContentRepository {
                fun q(): String = "SELECT id FROM post_replies WHERE author_id = ?"
            }
            """.trimIndent()
        rule.lint(writeKtFile("ReplyOwnContentRepository.kt", code)).shouldBeEmpty()
    }

    "FROM postscategory does not match (word boundary)" {
        val code =
            """
            package id.nearyou.app.category

            val sql = "SELECT * FROM postscategory"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ---- V11: Global-timeline + admin_regions fixtures
    //          (block-exclusion-lint spec delta in global-timeline-with-region-polygons) ----

    "global_timeline_query_with_bidirectional_block_exclusion — passes" {
        // Canonical V11 Global literal from JdbcPostsGlobalRepository. FROM visible_posts,
        // no follows filter (Global is every visible author), both bidirectional
        // user_blocks NOT-IN subqueries, and the V7 + V8 LEFT JOIN pattern. Must NOT trip.
        val code =
            """
            package id.nearyou.app.infra.repo

            class T {
                fun q(): String =
                    "SELECT p.id, p.author_id, p.content, " +
                        "ST_Y(p.display_location::geometry) AS lat, " +
                        "ST_X(p.display_location::geometry) AS lng, " +
                        "p.city_name, p.created_at, " +
                        "(pl.user_id IS NOT NULL) AS liked_by_viewer, c.n AS reply_count " +
                        "FROM visible_posts p " +
                        "LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = ? " +
                        "LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr " +
                        "JOIN visible_users vu ON vu.id = pr.author_id " +
                        "WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE " +
                        "WHERE p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?) " +
                        "AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?) " +
                        "ORDER BY p.created_at DESC, p.id DESC LIMIT 31"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "global_timeline_query_missing_blocked_id_fails — rule fires" {
        // Same Global-shaped literal with the second NOT-IN (blocked_id side) removed.
        // blocker_id = is present; blocked_id = is absent. Rule MUST fire.
        val code =
            """
            package id.nearyou.app.infra.repo

            class T {
                fun q(): String =
                    "SELECT p.id FROM visible_posts p " +
                        "WHERE p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?) " +
                        "ORDER BY p.created_at DESC LIMIT 31"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "global_timeline_query_missing_blocker_id_fails — rule fires" {
        // Same Global-shaped literal with the first NOT-IN (blocker_id side) removed.
        // blocked_id = is present; blocker_id = is absent. Rule MUST fire.
        val code =
            """
            package id.nearyou.app.infra.repo

            class T {
                fun q(): String =
                    "SELECT p.id FROM visible_posts p " +
                        "WHERE p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?) " +
                        "ORDER BY p.created_at DESC LIMIT 31"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "admin_regions_is_deliberately_not_protected_table — SELECT passes" {
        // Encoding for the V11 block-exclusion-lint ADDED requirement: admin_regions is
        // deliberately NOT in the protected-table set. Reference-data table, no user-
        // visible content, no per-viewer surface. Must NOT trip the rule — even though
        // the literal mentions no user_blocks at all.
        val code =
            """
            package id.nearyou.app.reference

            class T {
                fun q(): String = "SELECT id FROM admin_regions WHERE level = 'kabupaten_kota'"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})
