package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * Database smoke test for V20 (`visible_posts_shadow_ban_author`) — the
 * 2026-06-10 audit fix for finding 02-C1 (shadow-banned authors' posts stayed
 * visible in every `visible_posts` consumer) + 02-M1 (`posts_author_idx`
 * documented but never shipped).
 *
 * Pins the four author-side exclusions the redefined view carries, the live
 * un-shadow-ban flip, and the index presence. Timeline routes consume the view
 * directly, so view-level coverage IS the timeline coverage for this invariant
 * (per the visible-posts-view spec's "canonical read surface" requirement).
 */
@Tags("database")
class MigrationV20SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    fun connect(): Connection = DriverManager.getConnection(url, user, password)

    fun cleanupFixtures() {
        connect().use { conn ->
            conn.prepareStatement("DELETE FROM posts WHERE content LIKE 'v20-post-%'").use { it.executeUpdate() }
            conn.prepareStatement("DELETE FROM users WHERE username LIKE 'v20\\_%'").use { it.executeUpdate() }
        }
    }

    beforeSpec {
        // Defensive: a crashed/killed prior run can leave v20 fixtures behind
        // (afterSpec never ran). Stale rows poison OTHER specs' fixtures — e.g.
        // SignupFlowTest's users wipe hits posts_author_id_fkey on an orphan post.
        cleanupFixtures()
    }

    fun seedUser(conn: Connection): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        conn.prepareStatement(
            """
            INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "v20_$short")
            ps.setString(3, "V20 $short")
            ps.setObject(4, LocalDate.of(1990, 1, 1))
            ps.setString(5, "V20${short.take(5).uppercase()}")
            ps.executeUpdate()
        }
        return id
    }

    fun seedPost(
        conn: Connection,
        authorId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        conn.prepareStatement(
            """
            INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden)
            VALUES (?, ?, ?,
              ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
              ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
              FALSE)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, authorId)
            ps.setString(3, "v20-post-${id.toString().take(6)}")
            // Deep-Indian-Ocean coords per the project test-data convention —
            // outside every admin_regions polygon, so the V11 city trigger is inert.
            ps.setDouble(4, 105.0)
            ps.setDouble(5, -10.5)
            ps.setDouble(6, 105.0)
            ps.setDouble(7, -10.5)
            ps.executeUpdate()
        }
        return id
    }

    fun visibleCount(
        conn: Connection,
        postId: UUID,
    ): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM visible_posts WHERE id = ?").use { ps ->
            ps.setObject(1, postId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    fun setUserFlag(
        conn: Connection,
        userId: UUID,
        sql: String,
    ) {
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, userId)
            ps.executeUpdate()
        }
    }

    "shadow-banning an author hides their existing posts from visible_posts; un-shadow-banning restores them live" {
        connect().use { conn ->
            val author = seedUser(conn)
            val post = seedPost(conn, author)
            visibleCount(conn, post) shouldBe 1
            setUserFlag(conn, author, "UPDATE users SET is_shadow_banned = TRUE WHERE id = ?")
            visibleCount(conn, post) shouldBe 0
            setUserFlag(conn, author, "UPDATE users SET is_shadow_banned = FALSE WHERE id = ?")
            visibleCount(conn, post) shouldBe 1
        }
    }

    "a tombstoned (account-deleted) author's posts are surfaced anonymized (V28 account-deletion-tombstone)" {
        connect().use { conn ->
            val author = seedUser(conn)
            val post = seedPost(conn, author)
            visibleCount(conn, post) shouldBe 1
            setUserFlag(conn, author, "UPDATE users SET deleted_at = NOW() WHERE id = ?")
            // V28 (account-deletion-tombstone) SURFACES a tombstoned author's posts anonymized
            // instead of excluding them — only the AUTHOR-side deleted_at exclusion was dropped
            // (shadow-ban + post-soft-delete still exclude).
            visibleCount(conn, post) shouldBe 1
        }
    }

    "a soft-deleted post is excluded" {
        connect().use { conn ->
            val author = seedUser(conn)
            val post = seedPost(conn, author)
            conn.prepareStatement("UPDATE posts SET deleted_at = NOW() WHERE id = ?").use { ps ->
                ps.setObject(1, post)
                ps.executeUpdate()
            }
            visibleCount(conn, post) shouldBe 0
        }
    }

    "auto-hidden filter from V4 is preserved" {
        connect().use { conn ->
            val author = seedUser(conn)
            val post = seedPost(conn, author)
            conn.prepareStatement("UPDATE posts SET is_auto_hidden = TRUE WHERE id = ?").use { ps ->
                ps.setObject(1, post)
                ps.executeUpdate()
            }
            visibleCount(conn, post) shouldBe 0
        }
    }

    "view definition stays viewer-agnostic (V28 shape — author-deletion exclusion dropped, no self-arm)" {
        // The visible-posts-view delta scenario "View stays viewer-agnostic after the
        // self-visibility change": the own-content self arm lives in the FEED queries
        // (JdbcPostsTimelineRepository / JdbcPostsGlobalRepository), NOT in the view —
        // the pg_views definition keeps the V20 shape. Postgres normalizes the
        // rendered definition (whitespace, parens), so we pin the V20 predicates plus
        // the absence of any viewer-aware construct rather than raw migration bytes.
        connect().use { conn ->
            conn.prepareStatement(
                "SELECT definition FROM pg_views WHERE viewname = 'visible_posts'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString(1).replace("\\s+".toRegex(), " ").lowercase()
                    // The full V20 predicate set, all present.
                    (
                        def.contains("is_auto_hidden = false") ||
                            (def.contains("not") && def.contains("is_auto_hidden"))
                    ) shouldBe true
                    def shouldContain "p.deleted_at is null"
                    // V28 (account-deletion-tombstone) dropped the AUTHOR-side deleted_at exclusion so
                    // tombstoned authors surface anonymized; only the post-side p.deleted_at remains.
                    def.contains("u.deleted_at") shouldBe false
                    (
                        def.contains("is_shadow_banned = false") ||
                            (def.contains("not") && def.contains("is_shadow_banned"))
                    ) shouldBe true
                    // Viewer-agnostic: no viewer parameter, no session-GUC hack, no
                    // UNION self-arm.
                    def.contains("union") shouldBe false
                    def.contains("current_setting") shouldBe false
                }
            }
        }
    }

    "posts_author_idx exists (docs/05 § Posts Schema; finding 02-M1)" {
        connect().use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'posts' AND indexname = 'posts_author_idx'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }

    afterSpec {
        // The DB is shared across the per-JVM test run; leftover v20 fixtures with
        // visible (non-shadow-banned) authors would leak into exact-page-size
        // timeline assertions (Global surfaces EVERY visible author). Mirror the
        // other migration smoke tests' clean-up discipline.
        cleanupFixtures()
    }
})
