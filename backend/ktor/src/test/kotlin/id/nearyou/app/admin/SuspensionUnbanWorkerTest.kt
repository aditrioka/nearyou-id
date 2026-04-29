package id.nearyou.app.admin

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

@Tags("database")
class SuspensionUnbanWorkerTest : StringSpec({

    val dataSource = hikari()
    val worker = SuspensionUnbanWorker(dataSource)

    fun seedUser(
        isBanned: Boolean,
        suspendedUntil: Instant?,
        deletedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    is_banned, suspended_until, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ub_$short")
                ps.setString(3, "Unban Test User")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "u${short.take(7)}")
                ps.setBoolean(6, isBanned)
                if (suspendedUntil != null) {
                    ps.setTimestamp(7, Timestamp.from(suspendedUntil))
                } else {
                    ps.setNull(7, java.sql.Types.TIMESTAMP)
                }
                if (deletedAt != null) {
                    ps.setTimestamp(8, Timestamp.from(deletedAt))
                } else {
                    ps.setNull(8, java.sql.Types.TIMESTAMP)
                }
                ps.executeUpdate()
            }
        }
        return id
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM users WHERE id = ANY(?)").use { ps ->
                val arr = conn.createArrayOf("uuid", ids.toList().toTypedArray())
                ps.setArray(1, arr)
                ps.executeUpdate()
            }
        }
    }

    data class UserRow(val isBanned: Boolean, val suspendedUntil: Instant?, val deletedAt: Instant?)

    fun loadUser(id: UUID): UserRow {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT is_banned, suspended_until, deleted_at FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    UserRow(
                        isBanned = rs.getBoolean("is_banned"),
                        suspendedUntil = rs.getTimestamp("suspended_until")?.toInstant(),
                        deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
                    )
                }
            }
        }
    }

    "9.2 elapsed 7-day suspension is flipped" {
        val uid =
            seedUser(
                isBanned = true,
                suspendedUntil = Instant.now().minusSeconds(3600),
            )
        try {
            val result = runBlocking { worker.execute() }
            result.unbannedCount shouldBe 1
            result.unbannedUserIds shouldContain uid
            val row = loadUser(uid)
            row.isBanned shouldBe false
            row.suspendedUntil shouldBe null
        } finally {
            cleanup(uid)
        }
    }

    "9.3 future-dated suspension is untouched" {
        val futureExpiry = Instant.now().plusSeconds(3600)
        val uid =
            seedUser(
                isBanned = true,
                suspendedUntil = futureExpiry,
            )
        try {
            val result = runBlocking { worker.execute() }
            result.unbannedUserIds.contains(uid) shouldBe false
            val row = loadUser(uid)
            row.isBanned shouldBe true
            row.suspendedUntil.shouldNotBeNull()
        } finally {
            cleanup(uid)
        }
    }

    "9.4 permanent ban (suspended_until IS NULL) is untouched" {
        val uid = seedUser(isBanned = true, suspendedUntil = null)
        try {
            val result = runBlocking { worker.execute() }
            result.unbannedUserIds.contains(uid) shouldBe false
            val row = loadUser(uid)
            row.isBanned shouldBe true
            row.suspendedUntil shouldBe null
        } finally {
            cleanup(uid)
        }
    }

    "9.5 soft-deleted user with elapsed suspension is untouched" {
        val uid =
            seedUser(
                isBanned = true,
                suspendedUntil = Instant.now().minusSeconds(86_400),
                deletedAt = Instant.now().minusSeconds(7 * 86_400),
            )
        try {
            val result = runBlocking { worker.execute() }
            result.unbannedUserIds.contains(uid) shouldBe false
            val row = loadUser(uid)
            row.isBanned shouldBe true
            row.suspendedUntil.shouldNotBeNull()
            row.deletedAt.shouldNotBeNull()
        } finally {
            cleanup(uid)
        }
    }

    "9.6 already-active user is untouched" {
        val uid = seedUser(isBanned = false, suspendedUntil = null)
        try {
            val result = runBlocking { worker.execute() }
            result.unbannedUserIds.contains(uid) shouldBe false
            val row = loadUser(uid)
            row.isBanned shouldBe false
            row.suspendedUntil shouldBe null
        } finally {
            cleanup(uid)
        }
    }

    "9.7 idempotency — second invocation flips zero rows" {
        val uid =
            seedUser(
                isBanned = true,
                suspendedUntil = Instant.now().minusSeconds(3600),
            )
        try {
            val first = runBlocking { worker.execute() }
            first.unbannedUserIds shouldContain uid
            val second = runBlocking { worker.execute() }
            second.unbannedUserIds.contains(uid) shouldBe false
            val row = loadUser(uid)
            row.isBanned shouldBe false
        } finally {
            cleanup(uid)
        }
    }

    "9.8 EXPLAIN plan uses users_suspended_idx (not seq scan)" {
        // The planner picks seq scan over index scan on tiny tables (cost-based
        // optimizer). Disable seqscan at the session level (NOT `SET LOCAL`,
        // which would require an explicit transaction) so the EXPLAIN proves
        // the query CAN use the partial index — i.e., the index is usable, and
        // production planning at scale will choose it once the table is large.
        //
        // ANALYZE before EXPLAIN: when other database-tagged tests in the same
        // CI pass have inserted users (e.g., the V14 fcm-token-registration
        // tests + the V15+ fcm-push-dispatch JDBC tests bulk-seed users for
        // their own setup), the `users` table statistics drift relative to
        // the partial-index selectivity. A stale-stats planner — even with
        // seqscan disabled — can prefer a different index path (e.g., a PK
        // bitmap scan) over `users_suspended_idx`. Refreshing stats keeps
        // the assertion deterministic regardless of test-ordering. See
        // PR #60 CI run 25084748242 for the failure that motivated this.
        val plan =
            dataSource.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.execute("ANALYZE users")
                    st.execute("SET enable_seqscan = off")
                    try {
                        st.executeQuery(
                            "EXPLAIN ${SuspensionUnbanWorker.SQL.trim()}",
                        ).use { rs ->
                            buildString {
                                while (rs.next()) {
                                    appendLine(rs.getString(1))
                                }
                            }
                        }
                    } finally {
                        st.execute("SET enable_seqscan = on")
                    }
                }
            }
        plan shouldContain "users_suspended_idx"
    }
})
