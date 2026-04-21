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
 * Database-dependent smoke test for V5 (user_blocks). Boots Flyway against the running
 * dev Postgres (dev/docker-compose.yml) and asserts:
 *  - V5 lands cleanly + idempotently
 *  - both directional indexes exist
 *  - UNIQUE / PRIMARY KEY composite enforced (duplicate INSERT fails)
 *  - CHECK enforced (self-block INSERT fails)
 *  - FK cascade on user hard-delete works in BOTH directions
 *
 * Tagged `database` so CI excludes it by default. Run locally with:
 *   `./gradlew :backend:ktor:test -Dkotest.tags=database`
 */
@Tags("database")
class MigrationV5SmokeTest : StringSpec({

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
            ps.setString(2, "blk_$short")
            ps.setString(3, "Block Tester")
            ps.setDate(4, java.sql.Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "b${short.take(7)}")
            ps.executeUpdate()
        }
        return id
    }

    "V5 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '5'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "user_blocks table has every canonical column" {
        val expected = setOf("blocker_id", "blocked_id", "created_at")
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'user_blocks'",
                ).use { rs ->
                    val present = mutableSetOf<String>()
                    while (rs.next()) present += rs.getString(1)
                    present.containsAll(expected) shouldBe true
                }
            }
        }
    }

    "both directional indexes exist (PK on blocker, secondary on blocked)" {
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
                     WHERE t.relname = 'user_blocks'
                    """.trimIndent(),
                ).use { rs ->
                    val byName = mutableMapOf<String, String>()
                    while (rs.next()) byName[rs.getString(1)] = rs.getString(2)
                    // PK -> "user_blocks_pkey" with columns (blocker_id, blocked_id)
                    val pkCols = byName.entries.firstOrNull { it.key.endsWith("_pkey") }?.value
                    pkCols shouldBe "blocker_id,blocked_id"
                    byName.keys shouldContainAll setOf("user_blocks_blocked_idx")
                    byName["user_blocks_blocked_idx"] shouldBe "blocked_id,blocker_id"
                }
            }
        }
    }

    "duplicate (blocker, blocked) INSERT rejected by PK" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val a = seedUser(conn)
                val b = seedUser(conn)
                conn.prepareStatement(
                    "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeUpdate()
                }
                var threw = false
                try {
                    conn.prepareStatement(
                        "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)",
                    ).use { ps ->
                        ps.setObject(1, a)
                        ps.setObject(2, b)
                        ps.executeUpdate()
                    }
                } catch (ex: SQLException) {
                    // 23505 = unique_violation
                    threw = ex.sqlState == "23505"
                }
                threw shouldBe true
            } finally {
                conn.rollback()
            }
        }
    }

    "self-block INSERT rejected by CHECK constraint" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val a = seedUser(conn)
                var threw = false
                try {
                    conn.prepareStatement(
                        "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)",
                    ).use { ps ->
                        ps.setObject(1, a)
                        ps.setObject(2, a)
                        ps.executeUpdate()
                    }
                } catch (ex: SQLException) {
                    // 23514 = check_violation
                    threw = ex.sqlState == "23514"
                }
                threw shouldBe true
            } finally {
                conn.rollback()
            }
        }
    }

    "FK cascade on user hard-delete (blocker direction)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val a = seedUser(conn)
                val b = seedUser(conn)
                conn.prepareStatement(
                    "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeUpdate()
                }
                // Hard-delete A; cascade should remove the row.
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, a)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? OR blocked_id = ?",
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

    "FK cascade on user hard-delete (blocked direction)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val a = seedUser(conn)
                val b = seedUser(conn)
                conn.prepareStatement(
                    "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeUpdate()
                }
                // Hard-delete B; cascade should remove the row.
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, b)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? OR blocked_id = ?",
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
