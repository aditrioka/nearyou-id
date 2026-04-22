package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * Database-dependent smoke test for V7 (post_likes). Boots Flyway against the running
 * dev Postgres (dev/docker-compose.yml) and asserts the five invariants from the
 * `migration-pipeline` and `users-schema` spec deltas:
 *  - V7 lands cleanly from V6 + is idempotent on a second run.
 *  - Both directional indexes exist with the documented column orders.
 *  - PK `(post_id, user_id)` enforces uniqueness (duplicate INSERT → 23505).
 *  - FK cascade on user hard-delete removes the liker's rows.
 *  - FK cascade on post hard-delete removes every like on that post.
 */
@Tags("database")
class MigrationV7SmokeTest : StringSpec({

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
            ps.setString(2, "lk_$short")
            ps.setString(3, "Like Tester")
            ps.setDate(4, java.sql.Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "l${short.take(7)}")
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

    "V7 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '7'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "re-running flywayMigrate against a DB already at V7 is a no-op" {
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

    "post_likes table has every canonical column" {
        val expected = setOf("post_id", "user_id", "created_at")
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'post_likes'",
                ).use { rs ->
                    val present = mutableSetOf<String>()
                    while (rs.next()) present += rs.getString(1)
                    present.containsAll(expected) shouldBe true
                }
            }
        }
    }

    "both directional indexes exist with documented column orders" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT i.relname,
                           array_to_string(
                               ARRAY(
                                   SELECT pg_get_indexdef(ix.indexrelid, k + 1, true)
                                     FROM generate_subscripts(ix.indkey, 1) k
                                    ORDER BY k
                               ), ','
                           ) AS cols
                      FROM pg_class i
                      JOIN pg_index ix ON ix.indexrelid = i.oid
                      JOIN pg_class t  ON ix.indrelid = t.oid
                     WHERE t.relname = 'post_likes'
                    """.trimIndent(),
                ).use { rs ->
                    val byName = mutableMapOf<String, String>()
                    while (rs.next()) byName[rs.getString(1)] = rs.getString(2)
                    val pkCols = byName.entries.firstOrNull { it.key.endsWith("_pkey") }?.value
                    pkCols shouldBe "post_id,user_id"
                    byName.keys shouldContainAll setOf("post_likes_user_idx", "post_likes_post_idx")
                    byName["post_likes_user_idx"] shouldBe "user_id,created_at"
                    byName["post_likes_post_idx"] shouldBe "post_id,created_at"
                }
            }
        }
    }

    "duplicate (post_id, user_id) INSERT rejected by PK" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val a = seedUser(conn)
                val p = seedPost(conn, a)
                conn.prepareStatement(
                    "INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)",
                ).use { ps ->
                    ps.setObject(1, p)
                    ps.setObject(2, a)
                    ps.executeUpdate()
                }
                var threw = false
                try {
                    conn.prepareStatement(
                        "INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)",
                    ).use { ps ->
                        ps.setObject(1, p)
                        ps.setObject(2, a)
                        ps.executeUpdate()
                    }
                } catch (ex: SQLException) {
                    threw = ex.sqlState == "23505"
                }
                threw shouldBe true
            } finally {
                conn.rollback()
            }
        }
    }

    "FK cascade on user hard-delete (liker side)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val author = seedUser(conn)
                val liker = seedUser(conn)
                val p = seedPost(conn, author)
                conn.prepareStatement(
                    "INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)",
                ).use { ps ->
                    ps.setObject(1, p)
                    ps.setObject(2, liker)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, liker)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM post_likes WHERE user_id = ?",
                ).use { ps ->
                    ps.setObject(1, liker)
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

    "FK cascade on post hard-delete (post side)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val author = seedUser(conn)
                val liker = seedUser(conn)
                val p = seedPost(conn, author)
                conn.prepareStatement(
                    "INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)",
                ).use { ps ->
                    ps.setObject(1, p)
                    ps.setObject(2, liker)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM posts WHERE id = ?").use { ps ->
                    ps.setObject(1, p)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM post_likes WHERE post_id = ?",
                ).use { ps ->
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
})
