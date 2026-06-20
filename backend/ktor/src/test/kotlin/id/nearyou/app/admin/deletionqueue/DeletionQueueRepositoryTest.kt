package id.nearyou.app.admin.deletionqueue

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.ratelimit.DeletionQueueExpediteRateLimiter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.UUID

/**
 * Repository-level integration tests for `DeletionQueueRepository` — the keyset
 * pagination, the count summary, the ASCENDING (soonest-first) ordering, and the
 * expedite mutation + atomicity, which the fixed-page-size route cannot exercise
 * directly. DB-backed; tagged `database`. (admin-hard-delete-queue tasks.md 6.4,
 * 6.8, 6.9, 6.10.)
 */
@Tags("database")
class DeletionQueueRepositoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val repo =
        DeletionQueueRepository(
            dataSource,
            AdminAuditLogger(dataSource),
            DeletionQueueExpediteRateLimiter(dataSource),
        )

    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        seededUsers.forEach { DeletionQueueTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin() = AdminAuthTestSupport.seedAdmin(dataSource).also { seededAdmins.add(it.id) }

    fun seedRequest(
        username: String,
        scheduledHardDeleteAt: Instant,
        source: String = "user",
        cancelledAt: Instant? = null,
        executedAt: Instant? = null,
    ): UUID {
        val userId = DeletionQueueTestSupport.seedUser(dataSource, username).also { seededUsers.add(it) }
        return DeletionQueueTestSupport.seedDeletionRequest(
            dataSource,
            userId = userId,
            scheduledHardDeleteAt = scheduledHardDeleteAt,
            source = source,
            cancelledAt = cancelledAt,
            executedAt = executedAt,
        )
    }

    fun future30d() = Instant.now().plusSeconds(30 * 24 * 3600L)

    // ---- 6.9: keyset / count / ascending order (route cannot exercise) ----

    "6.9 — keyset pagination yields every row once across the page boundary (no drop / no dup)" {
        val r1 = seedRequest("page_a", Instant.now().plusSeconds(5 * 24 * 3600L))
        val r2 = seedRequest("page_b", Instant.now().plusSeconds(10 * 24 * 3600L))
        val r3 = seedRequest("page_c", Instant.now().plusSeconds(15 * 24 * 3600L))

        val first = repo.query(DeletionQuery(pageSize = 2))
        first.rows.size shouldBe 2 // full first page (≥3 pending exist)
        first.nextCursor shouldNotBe null

        val allIds = mutableListOf<UUID>()
        allIds += first.rows.map { it.id }
        var cursor = first.nextCursor
        var guard = 0
        while (cursor != null && guard++ < 100) {
            val p = repo.query(DeletionQuery(pageSize = 2, cursor = cursor))
            allIds += p.rows.map { it.id }
            cursor = p.nextCursor
        }
        setOf(r1, r2, r3).forEach { id -> allIds.count { it == id } shouldBe 1 }
        allIds.size shouldBe allIds.toSet().size
    }

    "6.9 — summary counts the pending population independent of the page limit" {
        val before = repo.summary(DeletionQuery())
        seedRequest("sum_a", future30d())
        seedRequest("sum_b", future30d())
        seedRequest("sum_c", future30d())

        repo.summary(DeletionQuery()) shouldBe before + 3
        // A tiny page does not change the count.
        repo.query(DeletionQuery(pageSize = 1)).rows.size shouldBe 1
        repo.summary(DeletionQuery(pageSize = 1)) shouldBe before + 3
    }

    "6.9 — rows are ordered by scheduled_hard_delete_at ascending (soonest-deadline-first)" {
        val soon = seedRequest("ord_soon", Instant.now().plusSeconds(2 * 24 * 3600L))
        val mid = seedRequest("ord_mid", Instant.now().plusSeconds(10 * 24 * 3600L))
        val far = seedRequest("ord_far", Instant.now().plusSeconds(40 * 24 * 3600L))

        val ids = repo.query(DeletionQuery(pageSize = 500)).rows.map { it.id }
        // Among the seeded three, the nearest deadline comes first.
        ids.indexOf(soon) shouldNotBe -1
        (ids.indexOf(soon) < ids.indexOf(mid)) shouldBe true
        (ids.indexOf(mid) < ids.indexOf(far)) shouldBe true
    }

    // ---- 6.4: expedite advances the deadline; route does not erase ----

    "6.4 — expedite advances scheduled_hard_delete_at to ~now, logs one row, and does not erase" {
        val admin = seedAdmin()
        val req = seedRequest("exp_me", future30d())
        val originalDeadline = DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req)!!
        val userId = seededUsers.last()

        val outcome = repo.expedite(req, admin.id, "support ticket SUP-1", "127.0.0.1", "test-agent")
        outcome shouldBe ExpediteOutcome.Applied

        val advanced = DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req)!!
        (advanced.isBefore(originalDeadline)) shouldBe true // brought forward
        DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 1
        // The route schedules; the worker erases. Nothing is erased here.
        DeletionQueueTestSupport.executedAtOf(dataSource, req) shouldBe null
        DeletionQueueTestSupport.deletionLogCountFor(dataSource, userId) shouldBe 0

        // before/after snapshots capture the deadline change (differing values).
        val logged =
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .first { it.actionType == "deletion_request_expedited" }
        logged.beforeState!! shouldNotBe logged.afterState
        logged.reason!! shouldBe "support ticket SUP-1"
    }

    // ---- 6.8: eligibility rejections (the guarded SELECT … FOR UPDATE WHERE) ----

    "6.8 — expedite of an already-executed / cancelled / already-due / unknown request is rejected" {
        val admin = seedAdmin()
        val executed = seedRequest("exec_req", future30d(), executedAt = Instant.now().minusSeconds(60))
        val cancelled = seedRequest("canc_req", future30d(), cancelledAt = Instant.now().minusSeconds(60))
        val due = seedRequest("due_req", Instant.now().minusSeconds(60), source = "apple_s2s_account_delete")
        val unknown = UUID.randomUUID()

        repo.expedite(executed, admin.id, "r", "127.0.0.1", null) shouldBe ExpediteOutcome.Ineligible
        repo.expedite(cancelled, admin.id, "r", "127.0.0.1", null) shouldBe ExpediteOutcome.Ineligible
        repo.expedite(due, admin.id, "r", "127.0.0.1", null) shouldBe ExpediteOutcome.Ineligible
        repo.expedite(unknown, admin.id, "r", "127.0.0.1", null) shouldBe ExpediteOutcome.Ineligible

        listOf(executed, cancelled, due, unknown).forEach {
            DeletionQueueTestSupport.countExpeditesFor(dataSource, it) shouldBe 0
        }
        // The already-due row's deadline is untouched (nothing to accelerate).
        DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, due)!!
            .isBefore(Instant.now()) shouldBe true
    }

    // ---- 6.10: atomicity (the deadline advance + cap-count + audit are one txn) ----

    "6.10 — a rate-limit-cap rejection leaves the deadline unchanged and writes no audit row" {
        val admin = seedAdmin()
        val req = seedRequest("capped", future30d())
        val original = DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req)!!
        // 10 prior expedites in the trailing hour → at the cap.
        repeat(10) { DeletionQueueTestSupport.seedAuditRow(dataSource, admin.id, "deletion_request_expedited") }

        repo.expedite(req, admin.id, "r", "127.0.0.1", null) shouldBe ExpediteOutcome.RateLimited

        DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req) shouldBe original
        DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 0
    }

    "6.10 — an audit-write failure rolls back the deadline advance (no orphaned advance)" {
        val req = seedRequest("rollback_req", future30d())
        val original = DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req)!!
        // Fault injection: an unknown acting admin id makes the audit INSERT
        // FK-violate (admin_actions_log.admin_id → admin_users). The eligibility
        // check + rate-count (0 rows for the unknown admin) pass, the deadline
        // UPDATE runs, then the audit insert throws → the transaction MUST roll
        // back so the deadline advance does not persist.
        val ghostAdmin = UUID.randomUUID()

        shouldThrow<Exception> {
            repo.expedite(req, ghostAdmin, "r", "127.0.0.1", null)
        }

        // The deadline advance did NOT persist — atomicity held.
        DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req) shouldBe original
        DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 0
    }
})
