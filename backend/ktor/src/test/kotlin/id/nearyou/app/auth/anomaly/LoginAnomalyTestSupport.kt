package id.nearyou.app.auth.anomaly

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + cleanup helpers for the `auth-login-anomaly-detection` DB-tagged
 * tests. Every `users` row carries a unique `la_<8hex>` prefix so per-test
 * cleanup removes exactly the rows it created and never pollutes the shared
 * timeline / moderation suites in the full multi-spec gate (MEMORY: "New
 * post-creating DB tests pollute timeline suites"). The `moderation_queue`
 * anomaly rows are NOT FK-linked to `users` (polymorphic `target_id`), so cleanup
 * deletes them explicitly by `target_id`.
 *
 * Seed timestamps are bound as absolute [Instant]s via `Timestamp.from`; the
 * detector compares `occurred_at` against a service-captured `windowStart`
 * parameter (NOT the DB `NOW()`), so tests pass a FIXED `nowProvider` and seed at
 * absolute instants — fully deterministic, immune to the macOS-micros-vs-Linux-
 * nanos clock caveat (MEMORY: "DB timestamp assertions false-fail in CI only").
 */
object LoginAnomalyTestSupport {
    fun hikari(): HikariDataSource {
        val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD") ?: "postgres"
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 2
                initializationFailTimeout = -1
            },
        )
    }

    /** A unique-per-row 8-hex tag used to prefix usernames for scoped cleanup. */
    fun tag(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    /** Seed a minimal `users` row (the parent for login_events). */
    fun seedUser(
        dataSource: DataSource,
        tag: String,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "la_$tag")
                ps.setString(3, "LA $tag")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "l${tag.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /**
     * Seed one `login_events` row with an explicit `occurred_at` and `ip` (bound as
     * text + `?::inet`, so `null` stays NULL). `ip_subnet_24` is the column-generated
     * `/24` — pass distinct [ip] third octets (`10.0.<n>.1`) to produce distinct
     * subnets, or `null` to produce a NULL-subnet row.
     */
    fun seedLoginEvent(
        dataSource: DataSource,
        userId: UUID,
        occurredAt: Instant,
        ip: String?,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO login_events (id, user_id, occurred_at, event_type, ip) VALUES (?, ?, ?, 'signin', ?::inet)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setTimestamp(3, Timestamp.from(occurredAt))
                ps.setString(4, ip)
                ps.executeUpdate()
            }
        }
        return id
    }

    /** `10.0.<n>.1` — distinct [n] yields a distinct generated `/24` subnet. */
    fun subnetIp(n: Int): String = "10.0.$n.1"

    /** Number of `anomaly_detection` `moderation_queue` rows for a `user` target. */
    fun anomalyRowCount(
        dataSource: DataSource,
        userId: UUID,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM moderation_queue WHERE target_type = 'user' AND target_id = ? AND trigger = 'anomaly_detection'",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** The single anomaly row's `(status, notes)`, or `null` if none exists. */
    fun anomalyRow(
        dataSource: DataSource,
        userId: UUID,
    ): AnomalyRow? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT status, notes FROM moderation_queue WHERE target_type = 'user' AND target_id = ? AND trigger = 'anomaly_detection'",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) AnomalyRow(status = rs.getString("status"), notes = rs.getString("notes")) else null
                }
            }
        }

    data class AnomalyRow(
        val status: String,
        val notes: String?,
    )

    /** True if ANY `reports` row exists for this user as a `user` target (scope guard). */
    fun reportsRowExistsForTarget(
        dataSource: DataSource,
        userId: UUID,
    ): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM reports WHERE target_type = 'user' AND target_id = ?",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }

    /**
     * Per-test cleanup, scoped to the rows a test seeded: the polymorphic
     * `moderation_queue` anomaly rows (no FK cascade), then `login_events`, then the
     * parent `users`.
     */
    fun cleanup(
        dataSource: DataSource,
        userIds: List<UUID>,
    ) {
        if (userIds.isEmpty()) return
        dataSource.connection.use { conn ->
            val arr = conn.createArrayOf("uuid", userIds.toTypedArray())
            conn.prepareStatement(
                "DELETE FROM moderation_queue WHERE target_type = 'user' AND target_id = ANY(?)",
            ).use { ps ->
                ps.setArray(1, arr)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM login_events WHERE user_id = ANY(?)").use { ps ->
                ps.setArray(1, arr)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ANY(?)").use { ps ->
                ps.setArray(1, arr)
                ps.executeUpdate()
            }
        }
    }
}
