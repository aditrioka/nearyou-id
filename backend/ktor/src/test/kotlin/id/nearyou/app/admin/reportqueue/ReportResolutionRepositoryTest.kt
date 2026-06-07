package id.nearyou.app.admin.reportqueue

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.moderation.UserModerationRepository
import id.nearyou.app.admin.moderation.UserModerationTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Repository-level integration tests for `ReportResolutionRepository` — the
 * atomic report-decision + queue-resolution transactions, the enforcement
 * (content visibility toggle / suspend-reuse / permanent-ban / shadow-ban), the
 * audit-row + notification writes, the role tier, idempotency, and the no-
 * cascade guard (`admin-report-queue-resolution-actions`, tasks.md Section 4:
 * 4.1–4.6, 4.8, the idempotent half of 4.9, 4.10, 4.14).
 *
 * DB-backed; tagged `database`. Calls the repository directly (the HTTP auth /
 * CSRF / role-gate + malformed-input + in-row-control wiring is exercised by
 * [AdminReportResolutionRouteTest]). The atomicity scenario (4.8) injects a
 * DB-layer `NOT VALID CHECK` fault — mirroring `UserModerationRepositoryTest`
 * 8.4 — since the repository SQL has no app-level injection seam.
 */
@Tags("database")
class ReportResolutionRepositoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val auditLogger = AdminAuditLogger(dataSource)
    val userModerationRepository = UserModerationRepository(dataSource, auditLogger)
    val repo = ReportResolutionRepository(dataSource, auditLogger, userModerationRepository)

    val seededIds = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        ReportQueueTestSupport.cleanup(dataSource, seededIds)
        seededIds.clear()
        // cleanupAdmin also sweeps the admin's admin_actions_log rows (target_id
        // is the report/queue id, but admin_id is this admin) — so report_resolved
        // + moderation_queue_resolved audit rows are cleaned here.
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner"): UUID = AdminAuthTestSupport.seedAdmin(dataSource, role = role).id.also { seededAdmins += it }

    fun seedUser(
        isBanned: Boolean = false,
        suspendedUntil: Instant? = null,
        isShadowBanned: Boolean = false,
        deletedAt: Instant? = null,
    ): UUID = ReportResolutionTestSupport.seedUser(dataSource, isBanned, suspendedUntil, isShadowBanned, deletedAt).also { seededIds += it }

    fun seedPost(authorId: UUID): UUID = ReportQueueTestSupport.seedPost(dataSource, authorId).also { seededIds += it }

    fun seedReply(
        postId: UUID,
        authorId: UUID,
    ): UUID = ReportQueueTestSupport.seedReply(dataSource, postId, authorId).also { seededIds += it }

    fun seedQueue(
        targetType: String,
        targetId: UUID,
        trigger: String = "admin_flag",
    ): UUID = ReportQueueTestSupport.seedQueueRow(dataSource, targetType, targetId, trigger = trigger)

    fun seedReport(
        reporterId: UUID,
        targetType: String,
        targetId: UUID,
    ): UUID = ReportQueueTestSupport.seedReport(dataSource, reporterId, targetType, targetId, BASE)

    val ip = "203.0.113.9"
    val ua = "resolution-test-agent/1.0"

    fun resolveQueue(
        queueId: UUID,
        resolution: QueueResolution,
        admin: UUID,
        role: String = "owner",
    ): QueueResolutionOutcome = repo.resolveQueueItem(queueId, resolution, admin, role, ip, ua)

    fun queueAudit(queueId: UUID) = UserModerationTestSupport.auditRowsForTarget(dataSource, queueId, "moderation_queue_resolved")

    fun reportAudit(reportId: UUID) = UserModerationTestSupport.auditRowsForTarget(dataSource, reportId, "report_resolved")

    // ===================== 4.1 report status (repo) ============================

    "4.1a pending report → actioned sets status/reviewed_by/reviewed_at + one report_resolved audit" {
        val admin = seedAdmin()
        val reporter = seedUser()
        val target = seedUser()
        val report = seedReport(reporter, "user", target)

        repo.resolveReport(report, ReportDecision.ACTIONED, admin, ip, ua) shouldBe ReportDecisionOutcome.Applied

        val state = ReportResolutionTestSupport.loadReport(dataSource, report)
        state.status shouldBe "actioned"
        state.reviewedBy shouldBe admin
        state.reviewedAt.shouldNotBeNull()
        reportAudit(report) shouldHaveSize 1
    }

    "4.1b pending report → dismissed sets status + reviewed_by/reviewed_at + one report_resolved audit" {
        val admin = seedAdmin()
        val reporter = seedUser()
        val target = seedUser()
        val report = seedReport(reporter, "user", target)

        repo.resolveReport(report, ReportDecision.DISMISSED, admin, ip, ua) shouldBe ReportDecisionOutcome.Applied

        val state = ReportResolutionTestSupport.loadReport(dataSource, report)
        state.status shouldBe "dismissed"
        state.reviewedBy shouldBe admin
        state.reviewedAt.shouldNotBeNull()
        reportAudit(report) shouldHaveSize 1
    }

    // ===================== 4.2 queue resolution recorded =======================

    "4.2 pending queue item → resolved + resolution + resolved_by/resolved_at + one audit whose after_state records the resolution" {
        val admin = seedAdmin()
        val author = seedUser()
        val post = seedPost(author)
        val queue = seedQueue("post", post)

        resolveQueue(queue, QueueResolution.HIDE, admin) shouldBe QueueResolutionOutcome.Applied

        val state = ReportResolutionTestSupport.loadQueue(dataSource, queue)
        state.status shouldBe "resolved"
        state.resolution shouldBe "hide"
        state.resolvedBy shouldBe admin
        state.resolvedAt.shouldNotBeNull()
        val audit = queueAudit(queue).single()
        afterStateField(audit.afterStateJson, "resolution") shouldBe "hide"
    }

    // ===================== 4.3 content enforcement =============================

    "4.3a keep un-hides an auto-hidden post (is_auto_hidden=FALSE)" {
        val admin = seedAdmin()
        val author = seedUser()
        val post = seedPost(author)
        ReportResolutionTestSupport.setAutoHidden(dataSource, "post", post, hidden = true)
        val queue = seedQueue("post", post)

        resolveQueue(queue, QueueResolution.KEEP, admin) shouldBe QueueResolutionOutcome.Applied

        ReportResolutionTestSupport.loadAutoHidden(dataSource, "post", post) shouldBe false
    }

    "4.3b hide hides a reply target (post_replies.is_auto_hidden=TRUE)" {
        val admin = seedAdmin()
        val author = seedUser()
        val post = seedPost(author)
        val reply = seedReply(post, author)
        val queue = seedQueue("reply", reply)

        resolveQueue(queue, QueueResolution.HIDE, admin) shouldBe QueueResolutionOutcome.Applied

        ReportResolutionTestSupport.loadAutoHidden(dataSource, "reply", reply) shouldBe true
    }

    "4.3c keep/hide on a user target → RejectedContentNotApplicable, queue stays pending, no audit" {
        val admin = seedAdmin()
        val user = seedUser()
        val queue = seedQueue("user", user)

        resolveQueue(queue, QueueResolution.KEEP, admin) shouldBe QueueResolutionOutcome.RejectedContentNotApplicable

        ReportResolutionTestSupport.loadQueue(dataSource, queue).status shouldBe "pending"
        queueAudit(queue) shouldHaveSize 0
    }

    // ===================== 4.4 author enforcement ==============================

    "4.4a suspend_author_7d suspends the post's author (is_banned + suspended_until) + account_action_applied" {
        val admin = seedAdmin()
        val author = seedUser()
        val post = seedPost(author)
        val queue = seedQueue("post", post)

        resolveQueue(queue, QueueResolution.SUSPEND_AUTHOR_7D, admin, role = "moderator") shouldBe QueueResolutionOutcome.Applied

        val state = ReportResolutionTestSupport.loadUserModeration(dataSource, author)
        state.isBanned shouldBe true
        state.suspendedUntil.shouldNotBeNull()
        isRoughlySevenDaysOut(state.suspendedUntil) shouldBe true
        UserModerationTestSupport.notificationsForUser(dataSource, author).single().type shouldBe "account_action_applied"
        ReportResolutionTestSupport.loadQueue(dataSource, queue).resolution shouldBe "suspend_author_7d"
        queueAudit(queue) shouldHaveSize 1
    }

    "4.4b ban_author (as admin) permanently bans the author (is_banned=TRUE, suspended_until=NULL) + notification" {
        val admin = seedAdmin(role = "admin")
        val author = seedUser()
        val post = seedPost(author)
        val queue = seedQueue("post", post)

        resolveQueue(queue, QueueResolution.BAN_AUTHOR, admin, role = "admin") shouldBe QueueResolutionOutcome.Applied

        val state = ReportResolutionTestSupport.loadUserModeration(dataSource, author)
        state.isBanned shouldBe true
        state.suspendedUntil.shouldBeNull()
        UserModerationTestSupport.notificationsForUser(dataSource, author).single().type shouldBe "account_action_applied"
    }

    "4.4c shadow_ban_author sets is_shadow_banned=TRUE" {
        val admin = seedAdmin()
        val author = seedUser(isShadowBanned = false)
        val post = seedPost(author)
        val queue = seedQueue("post", post)

        resolveQueue(queue, QueueResolution.SHADOW_BAN_AUTHOR, admin) shouldBe QueueResolutionOutcome.Applied

        ReportResolutionTestSupport.loadUserModeration(dataSource, author).isShadowBanned shouldBe true
    }

    "4.4d an author action on a user target resolves to that user itself (self-resolution)" {
        val admin = seedAdmin()
        val user = seedUser(isShadowBanned = false)
        val queue = seedQueue("user", user)

        resolveQueue(queue, QueueResolution.SHADOW_BAN_AUTHOR, admin) shouldBe QueueResolutionOutcome.Applied

        ReportResolutionTestSupport.loadUserModeration(dataSource, user).isShadowBanned shouldBe true
    }

    "4.4e a hard-deleted target whose author cannot be resolved → RejectedAuthorUnresolvable, no write" {
        val admin = seedAdmin()
        // Queue row points at a post id that was never seeded (hard-deleted target):
        // resolveAuthor's `SELECT author_id FROM posts WHERE id = ?` finds no row.
        val missingPost = UUID.randomUUID().also { seededIds += it }
        val queue = seedQueue("post", missingPost)

        resolveQueue(queue, QueueResolution.BAN_AUTHOR, admin, role = "admin") shouldBe QueueResolutionOutcome.RejectedAuthorUnresolvable

        ReportResolutionTestSupport.loadQueue(dataSource, queue).status shouldBe "pending"
        queueAudit(queue) shouldHaveSize 0
    }

    "4.4f suspend on an already-permanently-banned author → RejectedSuspendGuard, queue stays pending, no audit" {
        val admin = seedAdmin()
        val author = seedUser(isBanned = true, suspendedUntil = null)
        val post = seedPost(author)
        val queue = seedQueue("post", post)

        resolveQueue(queue, QueueResolution.SUSPEND_AUTHOR_7D, admin) shouldBe QueueResolutionOutcome.RejectedSuspendGuard

        ReportResolutionTestSupport.loadQueue(dataSource, queue).status shouldBe "pending"
        // The permanent ban is untouched (no downgrade).
        val state = ReportResolutionTestSupport.loadUserModeration(dataSource, author)
        state.isBanned shouldBe true
        state.suspendedUntil.shouldBeNull()
        queueAudit(queue) shouldHaveSize 0
    }

    // ===================== 4.5 ban tier ========================================

    "4.5a moderator + ban_author → ForbiddenBanTier, no users write, queue stays pending, no audit" {
        val admin = seedAdmin(role = "moderator")
        val author = seedUser()
        val post = seedPost(author)
        val queue = seedQueue("post", post)

        resolveQueue(queue, QueueResolution.BAN_AUTHOR, admin, role = "moderator") shouldBe QueueResolutionOutcome.ForbiddenBanTier

        ReportResolutionTestSupport.loadUserModeration(dataSource, author).isBanned shouldBe false
        ReportResolutionTestSupport.loadQueue(dataSource, queue).status shouldBe "pending"
        queueAudit(queue) shouldHaveSize 0
    }

    "4.5b admin + ban_author → applied" {
        val admin = seedAdmin(role = "admin")
        val author = seedUser()
        val post = seedPost(author)
        val queue = seedQueue("post", post)

        resolveQueue(queue, QueueResolution.BAN_AUTHOR, admin, role = "admin") shouldBe QueueResolutionOutcome.Applied

        ReportResolutionTestSupport.loadUserModeration(dataSource, author).isBanned shouldBe true
    }

    // ===================== 4.6 shadow-ban stealth ==============================

    "4.6 shadow_ban_author writes NO notification + is_shadow_banned + one audit; suspend inserts one account_action_applied" {
        val admin = seedAdmin()
        val shadowAuthor = seedUser()
        val shadowPost = seedPost(shadowAuthor)
        val shadowQueue = seedQueue("post", shadowPost)

        resolveQueue(shadowQueue, QueueResolution.SHADOW_BAN_AUTHOR, admin) shouldBe QueueResolutionOutcome.Applied

        UserModerationTestSupport.notificationsForUser(dataSource, shadowAuthor) shouldHaveSize 0 // stealth: no notification
        ReportResolutionTestSupport.loadUserModeration(dataSource, shadowAuthor).isShadowBanned shouldBe true
        queueAudit(shadowQueue) shouldHaveSize 1

        // Contrast: suspend DOES insert exactly one account_action_applied.
        val suspendAuthor = seedUser()
        val suspendPost = seedPost(suspendAuthor)
        val suspendQueue = seedQueue("post", suspendPost)
        resolveQueue(suspendQueue, QueueResolution.SUSPEND_AUTHOR_7D, admin) shouldBe QueueResolutionOutcome.Applied
        UserModerationTestSupport.notificationsForUser(dataSource, suspendAuthor) shouldHaveSize 1
    }

    // ===================== 4.8 atomicity =======================================

    "4.8 an audit-write failure rolls back the enforcement and the resolution (keep on an auto-hidden post)" {
        val admin = seedAdmin()
        val author = seedUser()
        val post = seedPost(author)
        ReportResolutionTestSupport.setAutoHidden(dataSource, "post", post, hidden = true)
        val queue = seedQueue("post", post)

        withFailingConstraint(
            dataSource,
            "admin_actions_log",
            "zzz_fail_mod_queue_resolved_audit",
            "action_type <> 'moderation_queue_resolved'",
        ) {
            shouldThrow<Exception> { resolveQueue(queue, QueueResolution.KEEP, admin) }
            // Full rollback: the post stays hidden, the queue stays pending, no audit.
            ReportResolutionTestSupport.loadAutoHidden(dataSource, "post", post) shouldBe true
            ReportResolutionTestSupport.loadQueue(dataSource, queue).status shouldBe "pending"
            queueAudit(queue) shouldHaveSize 0
        }
    }

    // ===================== 4.9 idempotent re-resolve (repo) ====================

    "4.9-repo re-resolving an already-resolved row is a safe no-op (no enforcement, no 2nd audit, resolution/resolved_by unchanged)" {
        val admin = seedAdmin()
        val author = seedUser()
        val post = seedPost(author)
        ReportResolutionTestSupport.setAutoHidden(dataSource, "post", post, hidden = false)
        val queue = seedQueue("post", post)

        resolveQueue(queue, QueueResolution.HIDE, admin) shouldBe QueueResolutionOutcome.Applied
        ReportResolutionTestSupport.loadAutoHidden(dataSource, "post", post) shouldBe true

        // Second resolution (keep) on the now-resolved row: a benign no-op.
        resolveQueue(queue, QueueResolution.KEEP, admin) shouldBe QueueResolutionOutcome.NoOpAlreadyResolved

        // No enforcement re-performed (the post is still hidden, NOT un-hidden by keep).
        ReportResolutionTestSupport.loadAutoHidden(dataSource, "post", post) shouldBe true
        val state = ReportResolutionTestSupport.loadQueue(dataSource, queue)
        state.resolution shouldBe "hide" // unchanged
        state.resolvedBy shouldBe admin // unchanged
        queueAudit(queue) shouldHaveSize 1 // no second audit row
    }

    // ===================== 4.10 no cascade to siblings =========================

    "4.10 resolving an auto_hide_3_reports queue (hide) leaves its three pending sibling reports pending" {
        val admin = seedAdmin()
        val author = seedUser()
        val post = seedPost(author)
        val r1 = seedUser()
        val r2 = seedUser()
        val r3 = seedUser()
        seedReport(r1, "post", post)
        seedReport(r2, "post", post)
        seedReport(r3, "post", post)
        val queue = seedQueue("post", post, trigger = "auto_hide_3_reports")

        resolveQueue(queue, QueueResolution.HIDE, admin) shouldBe QueueResolutionOutcome.Applied

        ReportResolutionTestSupport.loadQueue(dataSource, queue).status shouldBe "resolved"
        ReportResolutionTestSupport.countReportsForTarget(dataSource, "post", post, "pending") shouldBe 3
    }

    // ===================== 4.14 sequential-decision race =======================

    "4.14 resolve a report actioned then dismissed → the second is a no-op (the first decision wins)" {
        val admin = seedAdmin()
        val reporter = seedUser()
        val target = seedUser()
        val report = seedReport(reporter, "user", target)

        repo.resolveReport(report, ReportDecision.ACTIONED, admin, ip, ua) shouldBe ReportDecisionOutcome.Applied
        repo.resolveReport(report, ReportDecision.DISMISSED, admin, ip, ua) shouldBe ReportDecisionOutcome.NoOpAlreadyResolved

        ReportResolutionTestSupport.loadReport(dataSource, report).status shouldBe "actioned"
        reportAudit(report) shouldHaveSize 1 // only the first decision wrote an audit row
    }
})

private val BASE: Instant = Instant.parse("2091-01-01T00:00:00Z")

private val parseJson = Json { ignoreUnknownKeys = true }

/** Extract a string field from a before/after-state JSON. */
private fun afterStateField(
    stateJson: String?,
    field: String,
): String? = parseJson.parseToJsonElement(stateJson!!).jsonObject[field]?.jsonPrimitive?.content

private fun isRoughlySevenDaysOut(instant: Instant): Boolean {
    val now = Instant.now()
    return instant.isAfter(now.plus(6, ChronoUnit.DAYS)) && instant.isBefore(now.plus(8, ChronoUnit.DAYS))
}

/**
 * Install a `NOT VALID CHECK` [constraintName] on [table] enforcing [checkExpr]
 * for the duration of [block], then drop it. NOT VALID skips existing rows but
 * enforces new INSERTs — the same DB-layer fault-injection
 * `UserModerationRepositoryTest` 8.4 uses, since the repository SQL has no
 * app-level injection seam.
 */
private inline fun withFailingConstraint(
    dataSource: javax.sql.DataSource,
    table: String,
    constraintName: String,
    checkExpr: String,
    block: () -> Unit,
) {
    dataSource.connection.use { conn ->
        conn.createStatement().use { st ->
            st.execute("ALTER TABLE $table ADD CONSTRAINT $constraintName CHECK ($checkExpr) NOT VALID")
        }
    }
    try {
        block()
    } finally {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("ALTER TABLE $table DROP CONSTRAINT IF EXISTS $constraintName")
            }
        }
    }
}
