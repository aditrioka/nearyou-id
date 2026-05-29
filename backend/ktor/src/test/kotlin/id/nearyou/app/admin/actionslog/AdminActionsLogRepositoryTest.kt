package id.nearyou.app.admin.actionslog

import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Integration tests for [AdminActionsLogRepository] — the read/filter/paginate
 * core of the `admin-actions-log-viewer` capability (tasks.md Section 6).
 *
 * DB-backed; tagged `database` (runs against the CI service-container
 * Postgres / local dev compose). Seeds one admin per test + controlled audit
 * rows, asserts ordering, keyset pagination (incl. the boundary + tiebreaker
 * edge cases from proposal-review), filtering, the admin join, injection
 * safety, and the empty-result path.
 */
@Tags("database")
class AdminActionsLogRepositoryTest : StringSpec({

    val dataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        seeded.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seeded.clear()
    }

    fun newAdmin(): UUID = AdminAuthTestSupport.seedAdmin(dataSource).id.also { seeded.add(it) }

    val repo = AdminActionsLogRepository(dataSource)
    val base = Instant.parse("2026-05-20T00:00:00Z")

    "6.2 — rows are returned newest-first (created_at DESC)" {
        val admin = newAdmin()
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "a_old", base)
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "a_mid", base.plusSeconds(60))
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "a_new", base.plusSeconds(120))

        val rows = repo.query(ActionLogQuery(adminId = admin)).rows

        rows.map { it.actionType } shouldContainExactly listOf("a_new", "a_mid", "a_old")
    }

    "6.3 — keyset: first page full + has-next; cursor returns strictly-older, non-overlapping rows" {
        val admin = newAdmin()
        repeat(5) { i -> ActionsLogTestSupport.seedActionLog(dataSource, admin, "a$i", base.plusSeconds(i.toLong())) }

        val page1 = repo.query(ActionLogQuery(adminId = admin, pageSize = 2))
        page1.rows.size shouldBe 2
        page1.nextCursor.shouldNotBeNull()

        val page2 = repo.query(ActionLogQuery(adminId = admin, pageSize = 2, cursor = page1.nextCursor))
        // strictly older than the last row of page 1
        val p1LastCreated = page1.rows.last().createdAt
        page2.rows.forEach { (it.createdAt <= p1LastCreated) shouldBe true }
        // no overlap
        val p1Ids = page1.rows.map { it.id }.toSet()
        page2.rows.forEach { p1Ids.contains(it.id) shouldBe false }
    }

    "6.3a — exact page-size boundary: 50 rows => no next cursor; 51 rows => has next" {
        val admin = newAdmin()
        repeat(AdminActionsLogRepository.PAGE_SIZE) { i ->
            ActionsLogTestSupport.seedActionLog(dataSource, admin, "row", base.plusSeconds(i.toLong()))
        }
        val exact = repo.query(ActionLogQuery(adminId = admin))
        exact.rows.size shouldBe AdminActionsLogRepository.PAGE_SIZE
        exact.nextCursor.shouldBeNull()

        // one more → 51 total
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "row", base.plusSeconds(1000))
        val overflow = repo.query(ActionLogQuery(adminId = admin))
        overflow.rows.size shouldBe AdminActionsLogRepository.PAGE_SIZE
        overflow.nextCursor.shouldNotBeNull()
    }

    "6.3b — id tiebreaker on identical created_at: no row skipped or duplicated across the cursor" {
        val admin = newAdmin()
        val ts = base.plusSeconds(500)
        // three rows sharing an IDENTICAL created_at, distinct ids
        repeat(3) { ActionsLogTestSupport.seedActionLog(dataSource, admin, "collide", ts) }

        val page1 = repo.query(ActionLogQuery(adminId = admin, pageSize = 2))
        page1.rows.size shouldBe 2
        page1.nextCursor.shouldNotBeNull()
        val page2 = repo.query(ActionLogQuery(adminId = admin, pageSize = 2, cursor = page1.nextCursor))
        page2.rows.size shouldBe 1

        val all = (page1.rows + page2.rows).map { it.id }
        all.toSet().size shouldBe 3 // no duplicates
    }

    "6.4 — filters narrow + compose with AND, incl target_type+target_id; ordering preserved within a filter" {
        val admin = newAdmin()
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "login", base.plusSeconds(1), targetType = "user", targetId = "A")
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "login", base.plusSeconds(2), targetType = "user", targetId = "B")
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "logout", base.plusSeconds(3), targetType = "post", targetId = "A")

        // action_type narrows
        repo.query(ActionLogQuery(adminId = admin, actionType = "login")).rows
            .all { it.actionType == "login" } shouldBe true

        // target_type narrows
        repo.query(ActionLogQuery(adminId = admin, targetType = "user")).rows.size shouldBe 2

        // target_type + target_id two-param composition
        val byTarget = repo.query(ActionLogQuery(adminId = admin, targetType = "user", targetId = "A")).rows
        byTarget.size shouldBe 1
        byTarget.first().targetId shouldBe "A"

        // AND composition: action_type=login AND target_id=B
        val anded = repo.query(ActionLogQuery(adminId = admin, actionType = "login", targetId = "B")).rows
        anded.size shouldBe 1
        anded.first().targetId shouldBe "B"

        // ordering preserved within a filtered set (newest-first)
        val logins = repo.query(ActionLogQuery(adminId = admin, actionType = "login")).rows
        logins.map { it.targetId } shouldContainExactly listOf("B", "A")
    }

    "6.5 — date range bounds created_at with inclusive whole-day upper bound (tz-deterministic UTC instants)" {
        val admin = newAdmin()
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "before", Instant.parse("2026-05-20T12:00:00Z"))
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "edge", Instant.parse("2026-05-25T23:59:59Z"))
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "after", Instant.parse("2026-05-26T00:00:00Z"))

        val day = LocalDate.parse("2026-05-25")
        val q =
            ActionLogQuery(
                adminId = admin,
                fromInclusive = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                toExclusive = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
            )
        val types = repo.query(q).rows.map { it.actionType }
        types shouldContainExactly listOf("edge")
    }

    "6.6 — admin_users join resolves display_name + email" {
        val admin = newAdmin()
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "login", base)
        val row = repo.query(ActionLogQuery(adminId = admin)).rows.single()
        row.adminDisplayName shouldBe "Test Admin"
        row.adminEmail.contains("@") shouldBe true
    }

    "6.7 — SQL-metacharacter and over-long action_type are treated as literals (0 rows, no error, table survives)" {
        val admin = newAdmin()
        ActionsLogTestSupport.seedActionLog(dataSource, admin, "login", base)

        val injection = repo.query(ActionLogQuery(adminId = admin, actionType = "'; DROP TABLE admin_actions_log;--"))
        injection.rows.size shouldBe 0

        val overLong = repo.query(ActionLogQuery(adminId = admin, actionType = "x".repeat(100)))
        overLong.rows.size shouldBe 0

        // table still exists + queryable
        repo.query(ActionLogQuery(adminId = admin)).rows.size shouldBe 1
    }

    "6.8 — empty result returns an empty page, not an error" {
        val admin = newAdmin()
        val page = repo.query(ActionLogQuery(adminId = admin, actionType = "matches_nothing"))
        page.rows.size shouldBe 0
        page.nextCursor.shouldBeNull()
    }
})
