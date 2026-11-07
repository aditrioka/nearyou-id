package id.nearyou.app.admin.usermanagement

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.appealreview.AppealDecisionOutcome
import id.nearyou.app.admin.appealreview.AppealReviewRepository
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.moderation.UserModerationRepository
import id.nearyou.app.admin.ratelimit.DestructiveActionRateLimiter
import id.nearyou.app.admin.reportqueue.QueueResolution
import id.nearyou.app.admin.reportqueue.QueueResolutionOutcome
import id.nearyou.app.admin.reportqueue.ReportQueueTestSupport
import id.nearyou.app.admin.reportqueue.ReportResolutionRepository
import id.nearyou.app.admin.reportqueue.ReportResolutionTestSupport
import io.kotest.assertions.withClue
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
import java.util.UUID
import javax.sql.DataSource

/**
 * `admin-user-history-enforcement-rows` (#496): the admin profile history
 * (`GET /admin/users/{id}`) includes enforcement logged against a queue item or an
 * appeal whose `after_state.user_id` names the user — driven through the REAL
 * writers (`ReportResolutionRepository` / `AppealReviewRepository`) so the test
 * proves the writers record the key AND the read picks it up. Plus the negative
 * guards (content resolution, another user's rows) and the index-served plan.
 *
 * DB-backed; tagged `database`. Posts are seeded, so cleanup runs per test
 * (`afterEach`) on a pool closed LAST in `afterSpec` (no `autoClose` — the
 * cleanup needs the pool).
 */
@Tags("database")
class UserProfileHistoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val auditLogger = AdminAuditLogger(dataSource)
    val rateLimiter = DestructiveActionRateLimiter(dataSource)
    val queueRepo =
        ReportResolutionRepository(dataSource, auditLogger, UserModerationRepository(dataSource, auditLogger, rateLimiter), rateLimiter)
    val appealRepo = AppealReviewRepository(dataSource, auditLogger)

    val seededIds = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        // appeals + notifications cascade with the users.
        ReportQueueTestSupport.cleanup(dataSource, seededIds)
        seededIds.clear()
        // Sweeps every audit row these admins wrote (queue / appeal / hand-inserted).
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(): UUID = AdminAuthTestSupport.seedAdmin(dataSource, role = "owner").id.also { seededAdmins += it }

    fun seedUser(isBanned: Boolean = false): UUID =
        ReportResolutionTestSupport.seedUser(dataSource, isBanned = isBanned).also { seededIds += it }

    /** Seed a post by [author] + a pending queue row for it, then resolve it through the real writer. */
    fun resolveOnPostOf(
        author: UUID,
        resolution: QueueResolution,
        admin: UUID,
    ) {
        val post = ReportQueueTestSupport.seedPost(dataSource, author).also { seededIds += it }
        val queue = ReportQueueTestSupport.seedQueueRow(dataSource, "post", post, trigger = "admin_flag")
        queueRepo.resolveQueueItem(queue, resolution, admin, "owner", IP, UA) shouldBe QueueResolutionOutcome.Applied
    }

    suspend fun profileBody(
        admin: UUID,
        user: UUID,
    ): String {
        val token = AdminAuthTestSupport.seedSession(dataSource, admin)
        var body = ""
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$user") { header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token") }
            res.status shouldBe HttpStatusCode.OK
            body = res.bodyAsText()
        }
        return body
    }

    "a ban applied through the report queue appears in the author's history" {
        val admin = seedAdmin()
        val author = seedUser()
        resolveOnPostOf(author, QueueResolution.BAN_AUTHOR, admin)

        val body = profileBody(admin, author)
        body shouldContain """class="st err">moderation_queue_resolved"""
        // The escaped JSON pair, so "shadow_ban_author" can't satisfy it.
        body shouldContain "&quot;resolution&quot;: &quot;ban_author&quot;"
        // before/after render on separate lines (Pebble trims a newline after a print tag).
        body shouldContain "<br>after:"
    }

    "an appeal approval (the unban) appears in the appellant's history" {
        val admin = seedAdmin()
        val appellant = seedUser(isBanned = true)
        appealRepo.approve(seedAppeal(dataSource, appellant), admin, IP, UA) shouldBe AppealDecisionOutcome.Applied

        profileBody(admin, appellant) shouldContain "appeal_approved"
    }

    "an appeal rejection appears in the appellant's history" {
        val admin = seedAdmin()
        val appellant = seedUser(isBanned = true)
        appealRepo.reject(seedAppeal(dataSource, appellant), admin, "Insufficient grounds.", IP, UA) shouldBe
            AppealDecisionOutcome.Applied

        profileBody(admin, appellant) shouldContain "appeal_rejected"
    }

    "any audit row naming the user in after_state.user_id appears, exactly once (also when both arms match)" {
        val admin = seedAdmin()
        val user = seedUser()
        val expediteReason = "expedite-${UUID.randomUUID()}"
        val bothArmsReason = "both-arms-${UUID.randomUUID()}"
        insertAudit(dataSource, admin, "deletion_request_expedited", "deletion_request", UUID.randomUUID().toString(), expediteReason, user)
        insertAudit(dataSource, admin, "user_warned", "user", user.toString(), bothArmsReason, user)

        val body = profileBody(admin, user)
        body shouldContain "deletion_request_expedited"
        Regex(Regex.escape(expediteReason)).findAll(body).count() shouldBe 1
        Regex(Regex.escape(bothArmsReason)).findAll(body).count() shouldBe 1
    }

    "a content-only queue resolution (hide) does not appear in the author's history" {
        val admin = seedAdmin()
        val author = seedUser()
        resolveOnPostOf(author, QueueResolution.HIDE, admin)

        val body = profileBody(admin, author)
        body shouldNotContain "moderation_queue_resolved"
        body shouldContain "No admin actions or username changes"
    }

    "another user's queue ban and appeal approval do not leak into the history" {
        val admin = seedAdmin()
        val a = seedUser()
        val b = seedUser(isBanned = true)
        resolveOnPostOf(b, QueueResolution.BAN_AUTHOR, admin)
        appealRepo.approve(seedAppeal(dataSource, b), admin, IP, UA) shouldBe AppealDecisionOutcome.Applied

        val body = profileBody(admin, a)
        body shouldNotContain "moderation_queue_resolved"
        body shouldNotContain "appeal_approved"
        body shouldContain "No admin actions or username changes"
        // Positive control: the same rows DO render on B's own profile.
        val bBody = profileBody(admin, b)
        bBody shouldContain "moderation_queue_resolved"
        bBody shouldContain """class="st ok">appeal_approved"""
    }

    "the widened history predicate is index-served: both OR arms use an index, no seq scan" {
        val user = UUID.randomUUID().toString()
        // Plans the production predicate against admin_actions_log ALONE. Seq scans and
        // plain index scans off leave only bitmap paths, which need an index qual on EACH
        // OR arm: a full walk of admin_actions_created_idx can't pass as index-served, and
        // without a usable after_state index the planner is forced back to a (penalised)
        // Seq Scan. The admin_users join is left out on purpose: with those toggles the
        // join partner's access is penalised too, and on a near-empty, analyzed admin_users
        // the planner then drives from admin_users and probes admin_actions_log by admin_id,
        // which hides the OR plan (observed in CI). SET LOCAL + rollback keeps the settings
        // off the pooled connection (MigrationV13SmokeTest pattern).
        val plan =
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    conn.createStatement().use {
                        it.execute("SET LOCAL enable_seqscan = off")
                        it.execute("SET LOCAL enable_indexscan = off")
                    }
                    conn.prepareStatement(
                        "EXPLAIN SELECT 1 FROM admin_actions_log l WHERE ${UserProfileRepository.ACTION_HISTORY_PREDICATE}",
                    ).use { ps ->
                        ps.setString(1, user)
                        ps.setString(2, user)
                        ps.executeQuery().use { rs -> buildString { while (rs.next()) appendLine(rs.getString(1)) } }
                    }
                } finally {
                    conn.rollback()
                    conn.autoCommit = true
                }
            }
        withClue("EXPLAIN:\n$plan") {
            plan shouldNotContain "Seq Scan on admin_actions_log"
            plan shouldContain "BitmapOr"
            plan shouldContain "admin_actions_after_user_idx"
            plan shouldContain "admin_actions_target_idx"
        }
    }
})

private const val IP = "203.0.113.7"
private const val UA = "profile-history-test/1.0"

/** Insert one pending `permanent_ban` appeal for [userId]. */
private fun seedAppeal(
    dataSource: DataSource,
    userId: UUID,
): UUID {
    val id = UUID.randomUUID()
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO appeals (id, user_id, action_type, appeal_text, status) VALUES (?, ?, 'permanent_ban', ?, 'pending')",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, userId)
            ps.setString(3, "Mohon ditinjau kembali.")
            ps.executeUpdate()
        }
    }
    return id
}

/** Insert one audit row whose `after_state.user_id` names [affectedUser]. */
@Suppress("LongParameterList")
private fun insertAudit(
    dataSource: DataSource,
    adminId: UUID,
    actionType: String,
    targetType: String,
    targetId: String,
    reason: String,
    affectedUser: UUID,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, reason, after_state) " +
                "VALUES (?, ?, ?, ?, ?, jsonb_build_object('user_id', ?::text))",
        ).use { ps ->
            ps.setObject(1, adminId)
            ps.setString(2, actionType)
            ps.setString(3, targetType)
            ps.setString(4, targetId)
            ps.setString(5, reason)
            ps.setString(6, affectedUser.toString())
            ps.executeUpdate()
        }
    }
}
