package id.nearyou.app.admin.routes

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.moderation.UserModerationTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Integration tests for the live landing stat cards (`admin-mockup-parity`
 * spec Req "Scaffold landing renders greeting and live stat cards" +
 * tasks.md 5.4). DB-backed; tagged `database`.
 *
 * The three aggregates are GLOBAL (no per-test scoping is possible), so each
 * test starts from a truncated `reports` / `rejected_identifiers` /
 * `admin_actions_log` slate — the same fresh-DB assumption the CI service
 * container provides (run locally against disposable containers per the
 * full-gate recipe).
 */
@Tags("database")
class AdminIndexStatsRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededAdmins = mutableListOf<UUID>()
    val seededUsers = mutableListOf<UUID>()
    afterEach {
        cleanStatTables(dataSource)
        seededUsers.forEach { id ->
            dataSource.connection.use { conn ->
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, id)
                    ps.executeUpdate()
                }
            }
        }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }
    beforeEach { cleanStatTables(dataSource) }

    fun seedAdmin(): AdminAuthTestSupport.SeededAdmin = AdminAuthTestSupport.seedAdmin(dataSource).also { seededAdmins.add(it.id) }

    "stat cards show live values from seeded data" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val reporter = UserModerationTestSupport.seedUser(dataSource).also { seededUsers.add(it) }
        val now = Instant.now()

        // 4 pending reports, oldest 2 h ago.
        seedReport(dataSource, reporter, createdAt = now.minusSeconds(2 * 3600))
        repeat(3) { seedReport(dataSource, reporter, createdAt = now.minusSeconds(600L * (it + 1))) }
        // 12 rejections in the last 24 h — age_under_18 the clear top reason.
        repeat(7) { seedRejected(dataSource, "age_under_18", now.minusSeconds(3600L * (it + 1))) }
        repeat(5) { seedRejected(dataSource, "attestation_persistent_fail", now.minusSeconds(3600L * (it + 1))) }
        // 9 audit actions today (UTC); newest is user_suspended.
        repeat(8) { seedAudit(dataSource, admin.id, "report_resolved", now.minusSeconds(60L * (it + 2))) }
        seedAudit(dataSource, admin.id, "user_suspended", now.minusSeconds(30))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "<dt>Pending</dt><dd>4</dd>"
            body shouldContain "<dt>Oldest</dt><dd>2 h ago</dd>"
            body shouldContain "<dt>Last 24 h</dt><dd>12</dd>"
            body shouldContain "<dt>Top reason</dt><dd>age_under_18</dd>"
            body shouldContain "<dt>Actions today</dt><dd>9</dd>"
            body shouldContain "<dt>Last</dt><dd>user_suspended</dd>"
        }
    }

    "top-reason tie breaks alphabetically" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val now = Instant.now()
        // Equal counts: age_under_18 wins the ORDER BY count DESC, reason ASC tie-break.
        repeat(3) { seedRejected(dataSource, "attestation_persistent_fail", now.minusSeconds(60L * (it + 1))) }
        repeat(3) { seedRejected(dataSource, "age_under_18", now.minusSeconds(60L * (it + 1))) }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }.bodyAsText()
            body shouldContain "<dt>Top reason</dt><dd>age_under_18</dd>"
        }
    }

    "empty stat tables render zero-state cards with placeholders" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // The session-validation refresh itself writes no audit row; the only
        // admin_actions_log content was cleaned in beforeEach.

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "<dt>Pending</dt><dd>0</dd>"
            body shouldContain "<dt>Oldest</dt><dd>—</dd>"
            body shouldContain "<dt>Last 24 h</dt><dd>0</dd>"
            body shouldContain "<dt>Top reason</dt><dd>—</dd>"
            body shouldContain "<dt>Actions today</dt><dd>0</dd>"
            body shouldContain "<dt>Last</dt><dd>—</dd>"
        }
    }
})

private fun cleanStatTables(dataSource: DataSource) {
    dataSource.connection.use { conn ->
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM reports")
            st.executeUpdate("DELETE FROM rejected_identifiers")
            st.executeUpdate("DELETE FROM admin_actions_log")
        }
    }
}

private fun seedReport(
    dataSource: DataSource,
    reporterId: UUID,
    createdAt: Instant,
    status: String = "pending",
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO reports (reporter_id, target_type, target_id, reason_category, status, created_at)
            VALUES (?, 'post', ?, 'spam', ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, reporterId)
            ps.setObject(2, UUID.randomUUID())
            ps.setString(3, status)
            ps.setTimestamp(4, Timestamp.from(createdAt))
            ps.executeUpdate()
        }
    }
}

private fun seedRejected(
    dataSource: DataSource,
    reason: String,
    rejectedAt: Instant,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO rejected_identifiers (identifier_hash, identifier_type, reason, rejected_at)
            VALUES (?, 'google', ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, "stats-test-${UUID.randomUUID()}")
            ps.setString(2, reason)
            ps.setTimestamp(3, Timestamp.from(rejectedAt))
            ps.executeUpdate()
        }
    }
}

private fun seedAudit(
    dataSource: DataSource,
    adminId: UUID,
    actionType: String,
    createdAt: Instant,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO admin_actions_log (admin_id, action_type, created_at) VALUES (?, ?, ?)",
        ).use { ps ->
            ps.setObject(1, adminId)
            ps.setString(2, actionType)
            ps.setTimestamp(3, Timestamp.from(createdAt))
            ps.executeUpdate()
        }
    }
}
