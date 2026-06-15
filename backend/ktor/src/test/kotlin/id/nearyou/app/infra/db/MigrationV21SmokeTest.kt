package id.nearyou.app.infra.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * Database smoke test for V21 (`subscription_events`) — the event-level billing
 * ledger created by the `revenuecat-subscription-webhook` change. Pins the table
 * shape that `docs/05` § Subscription Event-Level Tracking specifies: the two
 * CHECK vocabularies, the `revenuecat_event_id` UNIQUE idempotency key, and the
 * two indexes.
 */
@Tags("database")
class MigrationV21SmokeTest : StringSpec({

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
                "DELETE FROM subscription_events WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'v21\\_%')",
            ).use { it.executeUpdate() }
            conn.prepareStatement("DELETE FROM users WHERE username LIKE 'v21\\_%'").use { it.executeUpdate() }
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
            ps.setString(2, "v21_$short")
            ps.setString(3, "V21 $short")
            ps.setObject(4, LocalDate.of(1990, 1, 1))
            ps.setString(5, "V21${short.take(5).uppercase()}")
            ps.executeUpdate()
        }
        return id
    }

    fun insertEvent(
        conn: Connection,
        userId: UUID,
        eventType: String,
        source: String,
        rcEventId: String?,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO subscription_events (user_id, event_type, source, revenuecat_event_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, eventType)
            ps.setString(3, source)
            ps.setString(4, rcEventId)
            ps.executeUpdate()
        }
    }

    "a valid paid subscription event inserts" {
        connect().use { conn ->
            val u = seedUser(conn)
            insertEvent(conn, u, "initial_purchase", "paid", "rc_evt_${u.toString().take(8)}")
            conn.prepareStatement("SELECT COUNT(*) FROM subscription_events WHERE user_id = ?").use { ps ->
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
                insertEvent(conn, u, "bogus_type", "paid", "rc_evt_bad_type_${u.toString().take(8)}")
            }
        }
    }

    "source CHECK rejects an out-of-vocabulary value" {
        connect().use { conn ->
            val u = seedUser(conn)
            shouldThrow<PSQLException> {
                insertEvent(conn, u, "renewal", "bogus_source", "rc_evt_bad_src_${u.toString().take(8)}")
            }
        }
    }

    "source CHECK accepts the reserved referral / manual_admin values (used by the future referral change)" {
        connect().use { conn ->
            val u = seedUser(conn)
            insertEvent(conn, u, "grant", "referral", "rc_evt_ref_${u.toString().take(8)}")
            insertEvent(conn, u, "grant", "manual_admin", "rc_evt_man_${u.toString().take(8)}")
        }
    }

    "revenuecat_event_id is UNIQUE — a re-delivered id is rejected at the DB" {
        connect().use { conn ->
            val u = seedUser(conn)
            val rcId = "rc_evt_dupe_${u.toString().take(8)}"
            insertEvent(conn, u, "initial_purchase", "paid", rcId)
            shouldThrow<PSQLException> {
                insertEvent(conn, u, "renewal", "paid", rcId)
            }
        }
    }

    "both indexes exist" {
        connect().use { conn ->
            listOf("subscription_events_user_idx", "subscription_events_source_idx").forEach { idx ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'subscription_events' AND indexname = ?",
                ).use { ps ->
                    ps.setString(1, idx)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        }
    }
})
