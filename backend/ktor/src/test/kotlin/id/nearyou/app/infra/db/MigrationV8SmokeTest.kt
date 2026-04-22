package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Database-dependent smoke test for V8 (`post_replies`). Boots Flyway against the
 * running dev Postgres (dev/docker-compose.yml) and asserts the eight invariants from
 * the `migration-pipeline` + `users-schema` spec deltas:
 *  - V8 lands cleanly from V7 + is idempotent on a second run.
 *  - Both partial indexes exist with the documented column orders AND the
 *    `deleted_at IS NULL` predicate.
 *  - `post_id → posts(id)` FK delete rule is CASCADE.
 *  - `author_id → users(id)` FK delete rule is RESTRICT (or NO ACTION — the V4
 *    convention; both encode the same blocking semantics).
 *  - `post_id` CASCADE: hard-deleting a `posts` row removes its replies.
 *  - `author_id` RESTRICT: hard-deleting a `users` row with ≥1 reply fails with
 *    SQLSTATE 23503, INCLUDING when the reply is already soft-deleted (RESTRICT
 *    inspects row existence, not `deleted_at`).
 *  - `author_id` RESTRICT: hard-deleting the same user after all replies are
 *    hard-deleted (not soft-deleted) succeeds.
 */
@Tags("database")
class MigrationV8SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    fun seedUser(conn: Connection): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        conn.prepareStatement(
            """
            INSERT INTO users (
                id, username, display_name, date_of_birth, invite_code_prefix
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "rp_$short")
            ps.setString(3, "Reply Tester")
            ps.setDate(4, java.sql.Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "r${short.take(7)}")
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
            ps.setString(3, "post-${id.toString().take(6)}")
            ps.setDouble(4, 106.8)
            ps.setDouble(5, -6.2)
            ps.setDouble(6, 106.8)
            ps.setDouble(7, -6.2)
            ps.executeUpdate()
        }
        return id
    }

    fun insertReply(
        conn: Connection,
        postId: UUID,
        authorId: UUID,
        deletedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        conn.prepareStatement(
            """
            INSERT INTO post_replies (id, post_id, author_id, content, deleted_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, postId)
            ps.setObject(3, authorId)
            ps.setString(4, "reply-${id.toString().take(6)}")
            if (deletedAt != null) ps.setTimestamp(5, Timestamp.from(deletedAt)) else ps.setNull(5, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            ps.executeUpdate()
        }
        return id
    }

    "V8 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '8'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "re-running flywayMigrate against a DB already at V8 is a no-op" {
        val before =
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        Flyway
            .configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val after =
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        before shouldBe after
    }

    "post_replies has every canonical column" {
        val expected = setOf("id", "post_id", "author_id", "content", "is_auto_hidden", "created_at", "updated_at", "deleted_at")
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'post_replies'",
                ).use { rs ->
                    val present = mutableSetOf<String>()
                    while (rs.next()) present += rs.getString(1)
                    present.containsAll(expected) shouldBe true
                }
            }
        }
    }

    "both partial indexes exist with documented column orders and deleted_at IS NULL predicate" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT i.relname,
                           pg_get_indexdef(ix.indexrelid) AS def
                      FROM pg_class i
                      JOIN pg_index ix ON ix.indexrelid = i.oid
                      JOIN pg_class t  ON ix.indrelid = t.oid
                     WHERE t.relname = 'post_replies'
                    """.trimIndent(),
                ).use { rs ->
                    val byName = mutableMapOf<String, String>()
                    while (rs.next()) byName[rs.getString(1)] = rs.getString(2)
                    byName.keys shouldContainAll setOf("post_replies_post_idx", "post_replies_author_idx")
                    val postDef = byName["post_replies_post_idx"]!!
                    postDef.contains("(post_id, created_at DESC)") shouldBe true
                    postDef.contains("WHERE (deleted_at IS NULL)") shouldBe true
                    val authorDef = byName["post_replies_author_idx"]!!
                    authorDef.contains("(author_id, created_at DESC)") shouldBe true
                    authorDef.contains("WHERE (deleted_at IS NULL)") shouldBe true
                }
            }
        }
    }

    "post_id FK delete rule is CASCADE" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT rc.delete_rule
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                  JOIN information_schema.referential_constraints rc
                    ON tc.constraint_name = rc.constraint_name
                 WHERE tc.table_name = 'post_replies'
                   AND tc.constraint_type = 'FOREIGN KEY'
                   AND kcu.column_name = 'post_id'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldBe "CASCADE"
                }
            }
        }
    }

    "author_id FK delete rule is RESTRICT or NO ACTION" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT rc.delete_rule
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                  JOIN information_schema.referential_constraints rc
                    ON tc.constraint_name = rc.constraint_name
                 WHERE tc.table_name = 'post_replies'
                   AND tc.constraint_type = 'FOREIGN KEY'
                   AND kcu.column_name = 'author_id'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val rule = rs.getString(1)
                    (rule == "RESTRICT" || rule == "NO ACTION") shouldBe true
                }
            }
        }
    }

    "post_id CASCADE: hard-deleting a post removes its replies" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val author = seedUser(conn)
                val replier = seedUser(conn)
                val p = seedPost(conn, author)
                insertReply(conn, p, replier)
                insertReply(conn, p, replier)
                conn.prepareStatement("DELETE FROM posts WHERE id = ?").use { ps ->
                    ps.setObject(1, p)
                    ps.executeUpdate()
                }
                conn.prepareStatement("SELECT COUNT(*) FROM post_replies WHERE post_id = ?").use { ps ->
                    ps.setObject(1, p)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 0
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    "author_id RESTRICT: user hard-delete blocked while a live reply exists" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val author = seedUser(conn)
                val replier = seedUser(conn)
                val p = seedPost(conn, author)
                insertReply(conn, p, replier)
                var sqlState: String? = null
                try {
                    // Savepoint so the outer transaction can be rolled back cleanly.
                    val sp = conn.setSavepoint()
                    try {
                        conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                            ps.setObject(1, replier)
                            ps.executeUpdate()
                        }
                    } catch (ex: SQLException) {
                        sqlState = ex.sqlState
                        conn.rollback(sp)
                    }
                } catch (ex: SQLException) {
                    sqlState = ex.sqlState
                }
                sqlState shouldBe "23503"
            } finally {
                conn.rollback()
            }
        }
    }

    "author_id RESTRICT: user hard-delete blocked even when replies are only soft-deleted" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val author = seedUser(conn)
                val replier = seedUser(conn)
                val p = seedPost(conn, author)
                insertReply(conn, p, replier, deletedAt = Instant.now())
                var sqlState: String? = null
                val sp = conn.setSavepoint()
                try {
                    conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                        ps.setObject(1, replier)
                        ps.executeUpdate()
                    }
                } catch (ex: SQLException) {
                    sqlState = ex.sqlState
                    conn.rollback(sp)
                }
                sqlState shouldBe "23503"
            } finally {
                conn.rollback()
            }
        }
    }

    "author_id RESTRICT releases after replies are hard-deleted" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val author = seedUser(conn)
                val replier = seedUser(conn)
                val p = seedPost(conn, author)
                insertReply(conn, p, replier)
                // Worker-style hard delete of all the user's replies (this is the single
                // legitimate hard-delete site — not reachable from the app code, only
                // from the tombstone worker which is out of scope for V8).
                conn.prepareStatement("DELETE FROM post_replies WHERE author_id = ?").use { ps ->
                    ps.setObject(1, replier)
                    ps.executeUpdate()
                }
                // Replier still has no posts, so V4 RESTRICT doesn't fire; V5/V6/V7
                // cascades run silently. User delete now succeeds.
                val affected =
                    conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                        ps.setObject(1, replier)
                        ps.executeUpdate()
                    }
                affected shouldBe 1
            } finally {
                conn.rollback()
            }
        }
    }
})
