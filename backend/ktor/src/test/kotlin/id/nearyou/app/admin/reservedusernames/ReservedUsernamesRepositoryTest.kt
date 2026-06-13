package id.nearyou.app.admin.reservedusernames

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.ratelimit.ReservedUsernameActionRateLimiter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import java.util.UUID

/**
 * Repository-level integration tests for [ReservedUsernamesRepository] — the
 * read (list/filter/search), the four write transactions (add/bulk/edit/remove)
 * with their audit rows + rate-limit interaction + seed protection, and the V3
 * trigger backstop (`admin-reserved-usernames-editor` tasks.md Section 5).
 * DB-backed; tagged `database`. The HTTP gate (CSRF/role/400/dual-mode) is
 * covered by [AdminReservedUsernamesRouteTest].
 */
@Tags("database")
class ReservedUsernamesRepositoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val repo =
        ReservedUsernamesRepository(
            dataSource,
            AdminAuditLogger(dataSource),
            ReservedUsernameActionRateLimiter(dataSource),
        )

    val seededAdmins = mutableListOf<UUID>()
    val createdUsernames = mutableListOf<String>()
    afterEach {
        createdUsernames.forEach { ReservedUsernamesTestSupport.deleteAdminAdded(dataSource, it) }
        createdUsernames.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun newAdmin(): UUID = AdminAuthTestSupport.seedAdmin(dataSource).also { seededAdmins += it.id }.id

    fun track(username: String): String = username.also { createdUsernames += it }

    val ip = "127.0.0.1"
    val sup = ReservedUsernamesTestSupport

    fun reservedAudits(
        admin: UUID,
        type: String,
    ) = sup.auditRows(dataSource, admin).filter { it.actionType == type }

    // ---- Read --------------------------------------------------------------

    "5.1 — list renders newest-first (created_at DESC, username)" {
        val base = Instant.parse("2026-05-20T00:00:00Z")
        sup.seed(dataSource, track("rua_old"), "r", createdAt = base)
        sup.seed(dataSource, track("rua_new"), "r", createdAt = base.plusSeconds(60))
        repo.query(ReservedUsernamesQuery(search = "rua_")).rows.map { it.username } shouldContainExactly listOf("rua_new", "rua_old")
    }

    "5.2 — source filter narrows admin_added vs seed_system" {
        sup.seed(dataSource, track("rua_src"), "r")
        repo.query(ReservedUsernamesQuery(source = "admin_added", search = "rua_src")).rows.map {
            it.username
        } shouldContainExactly listOf("rua_src")
        repo.query(ReservedUsernamesQuery(source = "seed_system", search = "rua_src")).rows shouldHaveSize 0
        // the seed "admin" surfaces under the seed_system filter, not admin_added
        repo.query(ReservedUsernamesQuery(source = "seed_system", search = "admin")).rows.any { it.username == "admin" } shouldBe true
        repo.query(ReservedUsernamesQuery(source = "admin_added", search = "admin")).rows.none { it.username == "admin" } shouldBe true
    }

    "5.3 — substring search matches username case-insensitively (LIKE wildcards escaped)" {
        sup.seed(dataSource, track("kopibrand"), "r")
        repo.query(ReservedUsernamesQuery(search = "OPIBR")).rows.any { it.username == "kopibrand" } shouldBe true
        // an underscore in the term is a literal, not a wildcard
        sup.seed(dataSource, track("rua_under"), "r")
        repo.query(ReservedUsernamesQuery(search = "a_und")).rows.any { it.username == "rua_under" } shouldBe true
    }

    // ---- Add ---------------------------------------------------------------

    "5.6 — add valid → admin_added row + one reserved_username_added audit row" {
        val admin = newAdmin()
        repo.addSingle(track("rua_add"), "brand protection", admin, ip, null) shouldBe AddOutcome.Added
        sup.sourceOf(dataSource, "rua_add") shouldBe "admin_added"
        val rows = reservedAudits(admin, "reserved_username_added")
        rows shouldHaveSize 1
        rows[0].targetType shouldBe "reserved_username"
        rows[0].targetId shouldBe "rua_add"
        rows[0].afterState!! shouldContain "rua_add"
        rows[0].afterState!! shouldContain "brand protection"
    }

    "5.7 — add duplicate → AlreadyReserved, no mutation, no audit row" {
        sup.seed(dataSource, track("rua_dup"), "original")
        val admin = newAdmin()
        repo.addSingle("rua_dup", "different", admin, ip, null) shouldBe AddOutcome.AlreadyReserved
        sup.reasonOf(dataSource, "rua_dup") shouldBe "original"
        reservedAudits(admin, "reserved_username_added") shouldHaveSize 0
    }

    "5.24 — add at the cap → RateLimited, no mutation, no audit row" {
        val admin = newAdmin()
        repeat(100) { sup.seedReservedAudit(dataSource, admin) }
        repo.addSingle("rua_capped", "r", admin, ip, null) shouldBe AddOutcome.RateLimited
        sup.exists(dataSource, "rua_capped") shouldBe false
        reservedAudits(admin, "reserved_username_added") shouldHaveSize 100 // the 100 seeded; no new one
    }

    // ---- Bulk --------------------------------------------------------------

    "5.10 — bulk add inserts new rows + reports duplicates" {
        val admin = newAdmin()
        val outcome = repo.bulkAdd("rua_b1,reason one\nadmin,dup of seed\nrua_b2,reason two", admin, ip, null)
        track("rua_b1")
        track("rua_b2")
        outcome.shouldBeApplied().run {
            added.toSet() shouldBe setOf("rua_b1", "rua_b2")
            skippedDuplicate shouldContainExactly listOf("admin")
        }
        sup.exists(dataSource, "rua_b1") shouldBe true
    }

    "5.11 — bulk add reports malformed rows (with line no.) + still adds the valid row" {
        val admin = newAdmin()
        val outcome = repo.bulkAdd("rua_valid,good\nbad user,has space\n,blank username", admin, ip, null)
        track("rua_valid")
        outcome.shouldBeApplied().run {
            added shouldContainExactly listOf("rua_valid")
            skippedInvalid.map { it.line } shouldContainExactly listOf(2, 3)
        }
        sup.exists(dataSource, "rua_valid") shouldBe true
    }

    "5.12 — bulk add writes one audit row per inserted username" {
        val admin = newAdmin()
        repo.bulkAdd("rua_c1,r\nrua_c2,r\nrua_c3,r", admin, ip, null)
        track("rua_c1")
        track("rua_c2")
        track("rua_c3")
        reservedAudits(admin, "reserved_username_added") shouldHaveSize 3
    }

    "5.32 — a username repeated within one upload is added once" {
        val admin = newAdmin()
        val outcome = repo.bulkAdd("rua_same,first reason\nrua_same,second reason", admin, ip, null)
        track("rua_same")
        outcome.shouldBeApplied().run {
            added shouldContainExactly listOf("rua_same")
            skippedDuplicate shouldContainExactly listOf("rua_same")
        }
        reservedAudits(admin, "reserved_username_added") shouldHaveSize 1
        sup.reasonOf(dataSource, "rua_same") shouldBe "first reason" // first occurrence wins
    }

    "5.33 — empty / header-only upload returns an empty report" {
        val admin = newAdmin()
        repo.bulkAdd("", admin, ip, null).shouldBeApplied().run {
            added shouldHaveSize 0
            skippedDuplicate shouldHaveSize 0
            skippedInvalid shouldHaveSize 0
        }
        repo.bulkAdd("username,reason", admin, ip, null).shouldBeApplied().added shouldHaveSize 0
    }

    "5.14 — a bulk that would exceed the trailing-hour cap is rejected wholesale" {
        val admin = newAdmin()
        repeat(98) { sup.seedReservedAudit(dataSource, admin) }
        val outcome = repo.bulkAdd("rua_x1,r\nrua_x2,r\nrua_x3,r\nrua_x4,r\nrua_x5,r", admin, ip, null)
        (outcome as BulkOutcome.RateLimited).wouldAdd shouldBe 5
        outcome.currentCount shouldBe 98
        listOf("rua_x1", "rua_x2", "rua_x3", "rua_x4", "rua_x5").forEach { sup.exists(dataSource, it) shouldBe false }
        sup.auditRows(dataSource, admin) shouldHaveSize 98 // no new audit rows
    }

    // ---- Edit --------------------------------------------------------------

    "5.15 — edit reason on admin_added → updated + audit (before/after reason)" {
        sup.seed(dataSource, track("rua_edit"), "old reason")
        val admin = newAdmin()
        repo.editReason("rua_edit", "new reason", admin, ip, null) shouldBe EditOutcome.Applied
        sup.reasonOf(dataSource, "rua_edit") shouldBe "new reason"
        val rows = reservedAudits(admin, "reserved_username_edited")
        rows shouldHaveSize 1
        rows[0].targetId shouldBe "rua_edit"
        rows[0].beforeState!! shouldContain "old reason"
        rows[0].afterState!! shouldContain "new reason"
    }

    "5.16 — edit reason on seed_system → SeedProtected, no mutation, no audit row" {
        val admin = newAdmin()
        val originalReason = sup.reasonOf(dataSource, "admin")
        repo.editReason("admin", "hacked", admin, ip, null) shouldBe EditOutcome.SeedProtected
        sup.reasonOf(dataSource, "admin") shouldBe originalReason
        reservedAudits(admin, "reserved_username_edited") shouldHaveSize 0
    }

    "5.17 — edit reason on a nonexistent username → NotFound, no audit row" {
        val admin = newAdmin()
        repo.editReason("rua_nonexistent", "x", admin, ip, null) shouldBe EditOutcome.NotFound
        reservedAudits(admin, "reserved_username_edited") shouldHaveSize 0
    }

    "5.34 — a successful reason edit refreshes updated_at (V3 trigger)" {
        val past = Instant.parse("2026-01-01T00:00:00Z")
        sup.seed(dataSource, track("rua_ts"), "r", createdAt = past)
        val before = sup.updatedAtOf(dataSource, "rua_ts")!!
        repo.editReason("rua_ts", "touched", newAdmin(), ip, null) shouldBe EditOutcome.Applied
        sup.updatedAtOf(dataSource, "rua_ts")!!.isAfter(before) shouldBe true
    }

    // ---- Remove ------------------------------------------------------------

    "5.19 — remove admin_added → deleted + audit (before_state)" {
        sup.seed(dataSource, track("rua_rm"), "to be removed")
        val admin = newAdmin()
        repo.remove("rua_rm", admin, ip, null) shouldBe RemoveOutcome.Applied
        sup.exists(dataSource, "rua_rm") shouldBe false
        val rows = reservedAudits(admin, "reserved_username_removed")
        rows shouldHaveSize 1
        rows[0].targetId shouldBe "rua_rm"
        rows[0].beforeState!! shouldContain "rua_rm"
    }

    "5.20 — remove seed_system → SeedProtected, no mutation, no audit row" {
        val admin = newAdmin()
        repo.remove("admin", admin, ip, null) shouldBe RemoveOutcome.SeedProtected
        sup.exists(dataSource, "admin") shouldBe true
        reservedAudits(admin, "reserved_username_removed") shouldHaveSize 0
    }

    // ---- DB trigger backstop (docs/08 Pre-Launch "reserved_usernames trigger test") ----

    "5.21 — direct DELETE of a seed_system row is rejected by the trigger" {
        shouldThrow<Exception> {
            dataSource.connection.use { c ->
                c.prepareStatement("DELETE FROM reserved_usernames WHERE username = 'admin'").use { it.executeUpdate() }
            }
        }
        sup.exists(dataSource, "admin") shouldBe true
    }

    "5.22 — direct UPDATE of a seed_system row's source is rejected by the trigger" {
        shouldThrow<Exception> {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "UPDATE reserved_usernames SET source = 'admin_added' WHERE username = 'admin'",
                ).use { it.executeUpdate() }
            }
        }
        sup.sourceOf(dataSource, "admin") shouldBe "seed_system"
    }
})

private fun BulkOutcome.shouldBeApplied(): BulkAddReport {
    (this is BulkOutcome.Applied) shouldBe true
    return (this as BulkOutcome.Applied).report
}
