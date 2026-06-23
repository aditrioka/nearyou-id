package id.nearyou.app.admin.appealreview

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `admin-appeal-review` integration tests (Section 4). Repository-level decision
 * behavior (approve → unban + audit; reject → keep + audit; idempotency) + the
 * route-level admin-auth / CSRF gates. Tagged `database`; per-test `try/finally`
 * cleanup so `autoClose` (pool) is safe.
 */
@Tags("database")
class AppealReviewTest : StringSpec({

    val dataSource = autoClose(AdminAuthTestSupport.hikari())
    val repo = AppealReviewRepository(dataSource, AdminAuditLogger(dataSource))

    fun seedUser(
        banned: Boolean,
        suspendedUntil: Instant?,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, is_banned, suspended_until) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "arv_$short")
                ps.setString(3, "Appeal Review")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "v${short.take(7)}")
                ps.setBoolean(6, banned)
                if (suspendedUntil != null) {
                    ps.setTimestamp(
                        7,
                        Timestamp.from(suspendedUntil),
                    )
                } else {
                    ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE)
                }
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedAppeal(
        userId: UUID,
        actionType: String,
        status: String = "pending",
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO appeals (id, user_id, action_type, appeal_text, status) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setString(3, actionType)
                ps.setString(4, "Mohon ditinjau kembali.")
                ps.setString(5, status)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun loadUserBanned(id: UUID): Pair<Boolean, Instant?> =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT is_banned, suspended_until FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean("is_banned") to rs.getTimestamp("suspended_until")?.toInstant()
                }
            }
        }

    data class AppealState(val status: String, val reviewedBy: UUID?, val decisionReason: String?)

    fun loadAppeal(id: UUID): AppealState =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status, reviewed_by, decision_reason FROM appeals WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    AppealState(rs.getString("status"), rs.getObject("reviewed_by", UUID::class.java), rs.getString("decision_reason"))
                }
            }
        }

    fun countAudit(
        adminId: UUID,
        actionType: String,
        targetId: UUID,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM admin_actions_log WHERE admin_id = ? AND action_type = ? AND target_id = ?",
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.setString(2, actionType)
                ps.setString(3, targetId.toString())
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun cleanupUser(userId: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM appeals WHERE user_id = ?").use {
                it.setObject(1, userId)
                it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use {
                it.setObject(1, userId)
                it.executeUpdate()
            }
        }
    }

    "listPending returns only pending appeals" {
        val pendingUser = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        val decidedUser = seedUser(banned = true, suspendedUntil = null)
        val pendingId = seedAppeal(pendingUser, "suspension", status = "pending")
        seedAppeal(decidedUser, "permanent_ban", status = "approved")
        try {
            val ids = repo.listPending(limit = 50, offset = 0).map { it.id }
            ids.contains(pendingId) shouldBe true
            ids.none { it != pendingId && loadAppeal(it).status != "pending" } shouldBe true
        } finally {
            cleanupUser(pendingUser)
            cleanupUser(decidedUser)
        }
    }

    "approve lifts a suspension, marks approved, and writes exactly one appeal_approved audit row" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        val uid = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        val appealId = seedAppeal(uid, "suspension")
        try {
            val outcome = repo.approve(appealId, admin.id, ip = "127.0.0.1", userAgent = "t")
            outcome shouldBe AppealDecisionOutcome.Applied
            val (banned, suspendedUntil) = loadUserBanned(uid)
            banned shouldBe false
            (suspendedUntil == null) shouldBe true
            val appeal = loadAppeal(appealId)
            appeal.status shouldBe "approved"
            appeal.reviewedBy shouldBe admin.id
            countAudit(admin.id, "appeal_approved", appealId) shouldBe 1
        } finally {
            cleanupUser(uid)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    "approve a permanently-banned appellant clears is_banned" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        val uid = seedUser(banned = true, suspendedUntil = null)
        val appealId = seedAppeal(uid, "permanent_ban")
        try {
            repo.approve(appealId, admin.id, ip = "127.0.0.1", userAgent = "t") shouldBe AppealDecisionOutcome.Applied
            loadUserBanned(uid).first shouldBe false
        } finally {
            cleanupUser(uid)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    "approve when the unban worker already cleared is_banned still transitions the appeal to approved" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        // The daily unban worker already lifted the suspension (is_banned = FALSE) before the admin acts.
        val uid = seedUser(banned = false, suspendedUntil = null)
        val appealId = seedAppeal(uid, "suspension")
        try {
            repo.approve(appealId, admin.id, ip = "127.0.0.1", userAgent = "t") shouldBe AppealDecisionOutcome.Applied
            loadAppeal(appealId).status shouldBe "approved"
            loadUserBanned(uid).first shouldBe false
        } finally {
            cleanupUser(uid)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    "re-approve is idempotent — the second decision is a no-op and writes no additional audit row" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        val uid = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        val appealId = seedAppeal(uid, "suspension")
        try {
            repo.approve(appealId, admin.id, ip = "127.0.0.1", userAgent = "t") shouldBe AppealDecisionOutcome.Applied
            repo.approve(appealId, admin.id, ip = "127.0.0.1", userAgent = "t") shouldBe AppealDecisionOutcome.NoOpAlreadyResolved
            countAudit(admin.id, "appeal_approved", appealId) shouldBe 1 // NOT 2
        } finally {
            cleanupUser(uid)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    "reject keeps the ban intact, marks rejected with the reason, and writes one appeal_rejected audit row" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        val uid = seedUser(banned = true, suspendedUntil = null)
        val appealId = seedAppeal(uid, "permanent_ban")
        try {
            repo.reject(appealId, admin.id, decisionReason = "Insufficient grounds.", ip = "127.0.0.1", userAgent = "t") shouldBe
                AppealDecisionOutcome.Applied
            loadUserBanned(uid).first shouldBe true // ban INTACT
            val appeal = loadAppeal(appealId)
            appeal.status shouldBe "rejected"
            appeal.decisionReason shouldBe "Insufficient grounds."
            countAudit(admin.id, "appeal_rejected", appealId) shouldBe 1
        } finally {
            cleanupUser(uid)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    "POST approve with a session but no CSRF token is rejected 403 (queue stays pending)" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        val sessionToken = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(banned = true, suspendedUntil = null)
        val appealId = seedAppeal(uid, "permanent_ban")
        try {
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                val resp =
                    client.post("/admin/appeals/$appealId/approve") {
                        header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$sessionToken")
                        // NO X-CSRF-Token header and no _csrf form field.
                    }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
            loadAppeal(appealId).status shouldBe "pending" // unchanged
        } finally {
            cleanupUser(uid)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    "POST approve without a session is denied (302 → /admin/login)" {
        val uid = seedUser(banned = true, suspendedUntil = null)
        val appealId = seedAppeal(uid, "permanent_ban")
        try {
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                val resp = client.post("/admin/appeals/$appealId/approve")
                resp.status shouldBe HttpStatusCode.Found // session-auth challenge → /admin/login
            }
            loadAppeal(appealId).status shouldBe "pending"
        } finally {
            cleanupUser(uid)
        }
    }
})
