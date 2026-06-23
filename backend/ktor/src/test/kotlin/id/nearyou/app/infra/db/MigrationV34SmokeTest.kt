package id.nearyou.app.infra.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * Database smoke test for V34 (`login_events`) — the durable login-history store
 * (`login-history-tracking`). Pins the table shape: the `event_type` CHECK
 * vocabulary, the `ON DELETE CASCADE` user FK, the STORED generated `ip_subnet_24`
 * /24 column, and the `(user_id, occurred_at)` workhorse index.
 */
@Tags("database")
class MigrationV34SmokeTest : StringSpec({

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
            conn.prepareStatement(
                "DELETE FROM login_events WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'v34\\_%')",
            ).use { it.executeUpdate() }
            conn.prepareStatement("DELETE FROM users WHERE username LIKE 'v34\\_%'").use { it.executeUpdate() }
        }
    }

    beforeSpec { cleanupFixtures() }
    afterSpec { cleanupFixtures() }

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
            ps.setString(2, "v34_$short")
            ps.setString(3, "V34 $short")
            ps.setObject(4, LocalDate.of(1990, 1, 1))
            ps.setString(5, "V34${short.take(5).uppercase()}")
            ps.executeUpdate()
        }
        return id
    }

    fun insertEvent(
        conn: Connection,
        userId: UUID,
        eventType: String,
        ip: String?,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO login_events (user_id, event_type, ip, device_fingerprint_hash, identifier_hash)
            VALUES (?, ?, ?::inet, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, eventType)
            ps.setString(3, ip)
            ps.setString(4, "fp_${userId.toString().take(8)}")
            ps.setString(5, "id_${userId.toString().take(8)}")
            ps.executeUpdate()
        }
    }

    "a valid signin login event inserts" {
        connect().use { conn ->
            val u = seedUser(conn)
            insertEvent(conn, u, "signin", "203.0.113.45")
            conn.prepareStatement("SELECT COUNT(*) FROM login_events WHERE user_id = ?").use { ps ->
                ps.setObject(1, u)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }

    "event_type CHECK rejects an out-of-vocabulary value" {
        connect().use { conn ->
            val u = seedUser(conn)
            shouldThrow<PSQLException> {
                insertEvent(conn, u, "logout", "203.0.113.45")
            }
        }
    }

    "ip_subnet_24 is generated as the /24 network (host octet masked)" {
        connect().use { conn ->
            val u = seedUser(conn)
            insertEvent(conn, u, "signin", "203.0.113.45")
            conn.prepareStatement("SELECT ip_subnet_24::text FROM login_events WHERE user_id = ?").use { ps ->
                ps.setObject(1, u)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1) shouldBe "203.0.113.0/24"
                }
            }
        }
    }

    "ip_subnet_24 is NULL when ip is NULL" {
        connect().use { conn ->
            val u = seedUser(conn)
            insertEvent(conn, u, "refresh", null)
            conn.prepareStatement("SELECT ip_subnet_24 FROM login_events WHERE user_id = ?").use { ps ->
                ps.setObject(1, u)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1).shouldBeNull()
                }
            }
        }
    }

    "the user FK is ON DELETE CASCADE — deleting the user removes the login events" {
        connect().use { conn ->
            val u = seedUser(conn)
            insertEvent(conn, u, "signin", "198.51.100.7")
            insertEvent(conn, u, "refresh", "198.51.100.7")
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, u)
                ps.executeUpdate()
            }
            conn.prepareStatement("SELECT COUNT(*) FROM login_events WHERE user_id = ?").use { ps ->
                ps.setObject(1, u)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 0
                }
            }
        }
    }

    "the (user_id, occurred_at) workhorse index exists" {
        connect().use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'login_events' AND indexname = ?",
            ).use { ps ->
                ps.setString(1, "login_events_user_occurred_idx")
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }
})
