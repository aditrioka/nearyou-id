package id.nearyou.app.admin.appealreview

import id.nearyou.app.admin.actionslog.ActionLogCursor
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `admin-appeal-review` integration tests (Section 4). Repository-level decision
 * behavior (approve → unban + audit; reject → keep + audit; idempotency; keyset
 * queue pagination) + the route-level admin-auth / CSRF / owner-admin role gates,
 * decision validation, and the no-proactive-notification negative guard. Tagged
 * `database`; per-test `try/finally` cleanup so `autoClose` (pool) is safe.
 */
@Tags("database")
class AppealReviewTest : StringSpec({

    val dataSource = autoClose(AdminAuthTestSupport.hikari())
    val repo = AppealReviewRepository(dataSource, AdminAuditLogger(dataSource))

    // Tests asserting a row lands on the FIRST 50-row page seed it this old, so leftover pending
    // appeals on a shared dev DB can't push it off page 1 (the queue is oldest-first). Kept apart
    // from the 1971 keyset-test timestamps.
    val firstPage = Instant.parse("1970-06-01T00:00:00Z")

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
        text: String = "Mohon ditinjau kembali.",
        createdAt: Instant = Instant.now(),
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO appeals (id, user_id, action_type, appeal_text, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setString(3, actionType)
                ps.setString(4, text)
                ps.setString(5, status)
                ps.setTimestamp(6, Timestamp.from(createdAt))
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

    /** `notifications` rows addressed to [userId] — the proactive-delivery observable (FCM dispatch keys off these rows). */
    fun countNotifications(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun formBody(vararg pairs: Pair<String, String>): String = pairs.toList().formUrlEncode()

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
        val pendingId = seedAppeal(pendingUser, "suspension", status = "pending", createdAt = firstPage)
        seedAppeal(decidedUser, "permanent_ban", status = "approved", createdAt = firstPage)
        try {
            val ids = repo.listPending(pageSize = 50, cursor = null).rows.map { it.id }
            ids.contains(pendingId) shouldBe true
            ids.none { it != pendingId && loadAppeal(it).status != "pending" } shouldBe true
        } finally {
            cleanupUser(pendingUser)
            cleanupUser(decidedUser)
        }
    }

    "listPending keyset-paginates oldest-first over (created_at, id) — no gaps or duplicates across a created_at tie" {
        // 1971 timestamps sort these ahead of every other pending appeal, so they own the first page.
        val t0 = Instant.parse("1971-01-01T00:00:00Z")
        val t1 = Instant.parse("1971-01-02T00:00:00Z")
        val users = List(3) { seedUser(banned = true, suspendedUntil = null) }
        val oldest = seedAppeal(users[0], "permanent_ban", createdAt = t0)
        val tieA = seedAppeal(users[1], "permanent_ban", createdAt = t1)
        val tieB = seedAppeal(users[2], "permanent_ban", createdAt = t1)
        try {
            val page1 = repo.listPending(pageSize = 2, cursor = null)
            page1.rows.size shouldBe 2
            page1.rows[0].id shouldBe oldest
            val cursor = page1.nextCursor.shouldNotBeNull()
            cursor.createdAt shouldBe t1
            val page2 = repo.listPending(pageSize = 2, cursor = cursor)
            // The id tiebreaker splits the created_at tie across the page boundary exactly once each
            // (set-compared: PG orders uuid unsigned, so the Java-side order is not asserted).
            setOf(page1.rows[1].id, page2.rows[0].id) shouldBe setOf(tieA, tieB)
            page2.rows.map { it.id } shouldNotContain page1.rows[1].id
            page2.rows.map { it.id } shouldNotContain oldest
        } finally {
            users.forEach { cleanupUser(it) }
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

    "GET /admin/appeals renders the pending queue (pending text shown, decided excluded)" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        val sessionToken = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val pendingUser = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        val approvedUser = seedUser(banned = false, suspendedUntil = null)
        // Distinct, plain-text needles so the rendered queue (appeals.peb + toViewMap projection) is asserted precisely.
        seedAppeal(pendingUser, "suspension", status = "pending", text = "PendingNeedleAlfa", createdAt = firstPage)
        seedAppeal(approvedUser, "permanent_ban", status = "approved", text = "ApprovedNeedleBravo", createdAt = firstPage)
        try {
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                val resp =
                    client.get("/admin/appeals") {
                        header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$sessionToken")
                    }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain "PendingNeedleAlfa" // the pending appeal is listed with its text
                (body.contains("ApprovedNeedleBravo")) shouldBe false // the approved appeal is excluded
            }
        } finally {
            cleanupUser(pendingUser)
            cleanupUser(approvedUser)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    "GET /admin/appeals without a session is denied (302 → /admin/login)" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/appeals").status shouldBe HttpStatusCode.Found
        }
    }

    "GET /admin/appeals honors the keyset cursor; a malformed cursor falls back to the first page" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(banned = true, suspendedUntil = null)
        val createdAt = Instant.parse("1971-01-01T00:00:00Z")
        val appealId = seedAppeal(uid, "permanent_ban", text = "CursorNeedleCharlie", createdAt = createdAt)
        try {
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                val pastIt = ActionLogCursor(createdAt, appealId).encode()
                val afterCursor = client.get("/admin/appeals?cursor=$pastIt") { header(HttpHeaders.Cookie, cookie(token)) }
                afterCursor.status shouldBe HttpStatusCode.OK
                afterCursor.bodyAsText().contains("CursorNeedleCharlie") shouldBe false // strictly after the cursor
                val garbage = client.get("/admin/appeals?cursor=not-a-cursor") { header(HttpHeaders.Cookie, cookie(token)) }
                garbage.status shouldBe HttpStatusCode.OK
                garbage.bodyAsText() shouldContain "CursorNeedleCharlie" // first page
            }
        } finally {
            cleanupUser(uid)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    // Role matrix (spec: owner/admin only). A moderator approving a PERMANENT-ban appeal is the
    // #491 finding — it would bypass the admin-user-moderation permanent-unban tier.
    listOf("moderator", "read_only").forEach { role ->
        "$role is 403 on GET /admin/appeals, approve and reject — no state change, no audit row" {
            val admin = AdminAuthTestSupport.seedAdmin(dataSource, role = role)
            val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
            val uid = seedUser(banned = true, suspendedUntil = null)
            val appealId = seedAppeal(uid, "permanent_ban", text = "RoleNeedleDelta")
            try {
                AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                    val get = client.get("/admin/appeals") { header(HttpHeaders.Cookie, cookie(token)) }
                    get.status shouldBe HttpStatusCode.Forbidden
                    get.bodyAsText().contains("RoleNeedleDelta") shouldBe false
                    for (action in listOf("approve", "reject")) {
                        client.post("/admin/appeals/$appealId/$action") {
                            header(HttpHeaders.Cookie, cookie(token))
                            contentType(ContentType.Application.FormUrlEncoded)
                            setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "x"))
                        }.status shouldBe HttpStatusCode.Forbidden
                    }
                }
                loadAppeal(appealId).status shouldBe "pending"
                loadUserBanned(uid).first shouldBe true // permanent ban intact
                AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe 0
            } finally {
                cleanupUser(uid)
                AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
            }
        }
    }

    "moderator POST with no CSRF token trips the CSRF gate first (403 + admin_csrf_violation, not a silent role-403)" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource, role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(banned = true, suspendedUntil = null)
        val appealId = seedAppeal(uid, "permanent_ban")
        try {
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                client.post("/admin/appeals/$appealId/approve") { header(HttpHeaders.Cookie, cookie(token)) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).map { it.actionType } shouldBe listOf("admin_csrf_violation")
            loadAppeal(appealId).status shouldBe "pending"
        } finally {
            cleanupUser(uid)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    listOf("owner", "admin").forEach { role ->
        "$role may view the queue, approve (lifts the permanent ban) and reject (keeps the ban) via the routes" {
            val admin = AdminAuthTestSupport.seedAdmin(dataSource, role = role)
            val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
            val approveUser = seedUser(banned = true, suspendedUntil = null)
            val rejectUser = seedUser(banned = true, suspendedUntil = null)
            val approveId = seedAppeal(approveUser, "permanent_ban", text = "AllowedNeedleEcho", createdAt = firstPage)
            val rejectId = seedAppeal(rejectUser, "permanent_ban")
            try {
                AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                    val get = client.get("/admin/appeals") { header(HttpHeaders.Cookie, cookie(token)) }
                    get.status shouldBe HttpStatusCode.OK
                    get.bodyAsText() shouldContain "AllowedNeedleEcho"

                    val approve =
                        client.post("/admin/appeals/$approveId/approve") {
                            header(HttpHeaders.Cookie, cookie(token))
                            contentType(ContentType.Application.FormUrlEncoded)
                            setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token)))
                        }
                    approve.status shouldBe HttpStatusCode.SeeOther
                    approve.headers[HttpHeaders.Location] shouldBe "/admin/appeals"

                    val reject =
                        client.post("/admin/appeals/$rejectId/reject") {
                            header(HttpHeaders.Cookie, cookie(token))
                            contentType(ContentType.Application.FormUrlEncoded)
                            setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "  Bukti tidak cukup.  "))
                        }
                    reject.status shouldBe HttpStatusCode.SeeOther
                    reject.headers[HttpHeaders.Location] shouldBe "/admin/appeals"
                }
                val approved = loadAppeal(approveId)
                approved.status shouldBe "approved"
                approved.reviewedBy shouldBe admin.id
                loadUserBanned(approveUser).first shouldBe false
                countAudit(admin.id, "appeal_approved", approveId) shouldBe 1

                val rejected = loadAppeal(rejectId)
                rejected.status shouldBe "rejected"
                rejected.decisionReason shouldBe "Bukti tidak cukup." // trimmed
                loadUserBanned(rejectUser).first shouldBe true // ban intact
                countAudit(admin.id, "appeal_rejected", rejectId) shouldBe 1
            } finally {
                cleanupUser(approveUser)
                cleanupUser(rejectUser)
                AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
            }
        }
    }

    "POST approve / reject with a malformed appeal id → 400, no write" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        try {
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                for (action in listOf("approve", "reject")) {
                    client.post("/admin/appeals/not-a-uuid/$action") {
                        header(HttpHeaders.Cookie, cookie(token))
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token)))
                    }.status shouldBe HttpStatusCode.BadRequest
                }
            }
            AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe 0
        } finally {
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    "POST reject with a decision reason over 1000 chars → 400, appeal stays pending, no audit row" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(banned = true, suspendedUntil = null)
        val appealId = seedAppeal(uid, "permanent_ban")
        try {
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                client.post("/admin/appeals/$appealId/reject") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "x".repeat(1001)))
                }.status shouldBe HttpStatusCode.BadRequest
            }
            loadAppeal(appealId).status shouldBe "pending"
            countAudit(admin.id, "appeal_rejected", appealId) shouldBe 0
        } finally {
            cleanupUser(uid)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }

    // content-moderation-appeal § "Decision outcome surfaced via status read, proactive notification
    // deferred": FCM dispatch fires only off a `notifications` row, so zero rows ⇒ no push either.
    "deciding an appeal (approve or reject) inserts no notifications row for the appellant" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource)
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val approveUser = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        val rejectUser = seedUser(banned = true, suspendedUntil = null)
        val approveId = seedAppeal(approveUser, "suspension")
        val rejectId = seedAppeal(rejectUser, "permanent_ban")
        try {
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                for ((id, action) in listOf(approveId to "approve", rejectId to "reject")) {
                    client.post("/admin/appeals/$id/$action") {
                        header(HttpHeaders.Cookie, cookie(token))
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token)))
                    }.status shouldBe HttpStatusCode.SeeOther
                }
            }
            loadAppeal(approveId).status shouldBe "approved"
            loadAppeal(rejectId).status shouldBe "rejected"
            countNotifications(approveUser) shouldBe 0
            countNotifications(rejectUser) shouldBe 0
        } finally {
            cleanupUser(approveUser)
            cleanupUser(rejectUser)
            AdminAuthTestSupport.cleanupAdmin(dataSource, admin.id)
        }
    }
})
