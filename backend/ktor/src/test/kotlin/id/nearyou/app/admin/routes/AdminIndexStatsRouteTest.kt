package id.nearyou.app.admin.routes

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.moderation.UserModerationTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
        // Anchor "today" seeds at max(now − 10 min, UTC day start) and step
        // FORWARD from there, so a run in the minutes after 00:00 UTC can't
        // spill rows onto yesterday AND the newest-row ordering stays
        // deterministic (later offset = newer; may run seconds ahead of
        // `now`, which the >= day-start count is indifferent to).
        val utcDayStart =
            java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        val todayBase = maxOf(now.minusSeconds(600), utcDayStart)

        fun todayAt(offsetSeconds: Long): Instant = todayBase.plusSeconds(offsetSeconds)

        // 4 pending reports, oldest 2 h ago — plus one ACTIONED report that
        // must NOT count toward the pending stat.
        seedReport(dataSource, reporter, createdAt = now.minusSeconds(2 * 3600))
        repeat(3) { seedReport(dataSource, reporter, createdAt = now.minusSeconds(600L * (it + 1))) }
        seedReport(dataSource, reporter, createdAt = now.minusSeconds(5 * 3600), status = "actioned")
        // 12 rejections in the last 24 h — age_under_18 the clear top reason —
        // plus one 25-h-old row that must NOT count toward the 24-h window.
        repeat(7) { seedRejected(dataSource, "age_under_18", now.minusSeconds(3600L * (it + 1))) }
        repeat(5) { seedRejected(dataSource, "attestation_persistent_fail", now.minusSeconds(3600L * (it + 1))) }
        seedRejected(dataSource, "age_under_18", now.minusSeconds(25 * 3600))
        // 9 audit actions today (UTC); newest is user_suspended (largest offset).
        repeat(8) { seedAudit(dataSource, admin.id, "report_resolved", todayAt(10L * (it + 1))) }
        seedAudit(dataSource, admin.id, "user_suspended", todayAt(300))

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

    "audit card with only-yesterday rows shows zero count but the real last action" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val now = Instant.now()
        val utcDayStart =
            java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        // Two rows strictly before the current UTC day; newest is user_unbanned.
        seedAudit(dataSource, admin.id, "report_resolved", utcDayStart.minusSeconds(7200))
        seedAudit(dataSource, admin.id, "user_unbanned", utcDayStart.minusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }.bodyAsText()
            body shouldContain "<dt>Actions today</dt><dd>0</dd>"
            body shouldContain "<dt>Last</dt><dd>user_unbanned</dd>"
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

    "operational dashboard renders the new widget sections" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "Operational dashboard"
            body shouldContain "Quick links"
            body shouldContain "Activity"
            body shouldContain ">Posts<"
            body shouldContain ">Signups<"
            body shouldContain "Top cities"
            // pg_database_size is callable by the test role → the System card renders.
            body shouldContain "Database size"
            // Deferred-cluster widgets are NOT rendered (spec negative guard).
            // ("Subscription" AND "CSAM" now legitimately appear in the nav — the
            // shipped admin-subscription-grace-monitor + admin-csam-detection-log
            // items — so neither bare substring is a valid dashboard-widget-absence
            // signal any longer; RevenueCat remains fully absent. The CSAM nav item
            // is a link-out, NOT a dashboard metric widget — guard on the widget that
            // never shipped instead.)
            body shouldNotContain "RevenueCat"
            body shouldNotContain "CSAM detections (24h)"
        }
    }

    "any authenticated admin role can view the read-only dashboard" {
        val moderator =
            AdminAuthTestSupport.seedAdmin(dataSource, role = "moderator").also { seededAdmins.add(it.id) }
        val token = AdminAuthTestSupport.seedSession(dataSource, moderator.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "Welcome back"
        }
    }

    "viewing the dashboard writes no admin_actions_log row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // beforeEach truncated admin_actions_log → start at 0.
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/") {
                header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
            }
        }
        auditRowCount(dataSource) shouldBe 0L
    }

    "unauthenticated dashboard request redirects to the login page" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/")
            res.status shouldBe HttpStatusCode.Found
            (res.headers[HttpHeaders.Location]?.contains("/admin/login") ?: false) shouldBe true
        }
    }
})

private fun auditRowCount(dataSource: DataSource): Long =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM admin_actions_log").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

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
