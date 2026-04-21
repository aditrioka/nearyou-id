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
 * Database-dependent smoke test for V6 (follows). Boots Flyway against the running dev
 * Postgres (dev/docker-compose.yml) and asserts the five invariants from the
 * `migration-pipeline` and `users-schema` spec deltas:
 *  - V6 lands cleanly from V5 + is idempotent on a second run.
 *  - Both directional indexes exist with the documented column orders.
 *  - PK `(follower_id, followee_id)` enforces uniqueness (duplicate INSERT → 23505).
 *  - CHECK rejects self-follow (INSERT with follower = followee → 23514).
 *  - FK cascade on user hard-delete works in BOTH directions (follower side + followee side).
 */
@Tags("database")
class MigrationV6SmokeTest : StringSpec({

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
            ps.setString(2, "fol_$short")
            ps.setString(3, "Follow Tester")
            ps.setDate(4, java.sql.Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "f${short.take(7)}")
            ps.executeUpdate()
        }
        return id
    }

    "V6 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '6'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "re-running flywayMigrate against a DB already at V6 is a no-op" {
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

    "follows table has every canonical column" {
        val expected = setOf("follower_id", "followee_id", "created_at")
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'follows'",
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
                     WHERE t.relname = 'follows'
                    """.trimIndent(),
                ).use { rs ->
                    val byName = mutableMapOf<String, String>()
                    while (rs.next()) byName[rs.getString(1)] = rs.getString(2)
                    // PK pkey lists (follower_id, followee_id).
                    val pkCols = byName.entries.firstOrNull { it.key.endsWith("_pkey") }?.value
                    pkCols shouldBe "follower_id,followee_id"
                    byName.keys shouldContainAll setOf("follows_follower_idx", "follows_followee_idx")
                    byName["follows_follower_idx"] shouldBe "follower_id,created_at"
                    byName["follows_followee_idx"] shouldBe "followee_id,created_at"
                }
            }
        }
    }

    "duplicate (follower_id, followee_id) INSERT rejected by PK" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val a = seedUser(conn)
                val b = seedUser(conn)
                conn.prepareStatement(
                    "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeUpdate()
                }
                var threw = false
                try {
                    conn.prepareStatement(
                        "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)",
                    ).use { ps ->
                        ps.setObject(1, a)
                        ps.setObject(2, b)
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

    "self-follow INSERT rejected by CHECK constraint" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val a = seedUser(conn)
                var threw = false
                try {
                    conn.prepareStatement(
                        "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)",
                    ).use { ps ->
                        ps.setObject(1, a)
                        ps.setObject(2, a)
                        ps.executeUpdate()
                    }
                } catch (ex: SQLException) {
                    threw = ex.sqlState == "23514"
                }
                threw shouldBe true
            } finally {
                conn.rollback()
            }
        }
    }

    "FK cascade on user hard-delete (follower side)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val a = seedUser(conn)
                val b = seedUser(conn)
                conn.prepareStatement(
                    "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, a)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM follows WHERE follower_id = ? OR followee_id = ?",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, a)
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

    "FK cascade on user hard-delete (followee side)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val a = seedUser(conn)
                val b = seedUser(conn)
                conn.prepareStatement(
                    "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, b)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM follows WHERE follower_id = ? OR followee_id = ?",
                ).use { ps ->
                    ps.setObject(1, b)
                    ps.setObject(2, b)
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
