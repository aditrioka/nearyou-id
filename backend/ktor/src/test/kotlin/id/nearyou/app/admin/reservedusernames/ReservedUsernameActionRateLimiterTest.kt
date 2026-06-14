package id.nearyou.app.admin.reservedusernames

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.ratelimit.ReservedUsernameActionRateLimiter
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Repository-level integration tests for [ReservedUsernameActionRateLimiter] —
 * the COUNT-over-`admin_actions_log` substrate of the reserved-username 100/hr
 * cap (`admin-reserved-usernames-editor` tasks.md 5.23/5.25/5.26). DB-backed;
 * tagged `database`. Seeds `admin_actions_log` rows directly with controlled
 * action_type + age so the action-set + trailing-one-hour window are exercised
 * without driving the full repository transactions (the at-cap reject side is
 * covered at the repository level by `ReservedUsernamesRepositoryTest`).
 */
@Tags("database")
class ReservedUsernameActionRateLimiterTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val limiter = ReservedUsernameActionRateLimiter(dataSource)
    val seededAdmins = mutableListOf<UUID>()
    afterTest {
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun newAdmin(): UUID = AdminAuthTestSupport.seedAdmin(dataSource).also { seededAdmins += it.id }.id

    "5.25 — counts only the three reserved action types in the trailing hour" {
        val admin = newAdmin()
        ReservedUsernamesTestSupport.seedReservedAudit(dataSource, admin, "reserved_username_added")
        ReservedUsernamesTestSupport.seedReservedAudit(dataSource, admin, "reserved_username_edited")
        ReservedUsernamesTestSupport.seedReservedAudit(dataSource, admin, "reserved_username_removed")
        // Non-reserved action type in-window — must NOT count.
        ReservedUsernamesTestSupport.seedReservedAudit(dataSource, admin, "user_suspended")
        // A reserved action OUTSIDE the trailing hour — must NOT count.
        ReservedUsernamesTestSupport.seedReservedAudit(dataSource, admin, "reserved_username_added", ageMinutes = 120)

        limiter.countInTrailingHour(admin) shouldBe 3
    }

    "5.23 — isAtOrOverCap is false at 99 and true at 100" {
        val admin = newAdmin()
        repeat(99) { ReservedUsernamesTestSupport.seedReservedAudit(dataSource, admin) }
        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, admin) shouldBe false }

        ReservedUsernamesTestSupport.seedReservedAudit(dataSource, admin) // 100th
        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, admin) shouldBe true }
    }

    "5.26 — the cap is per-admin: A at the cap does not constrain B" {
        val adminA = newAdmin()
        val adminB = newAdmin()
        repeat(ReservedUsernameActionRateLimiter.RESERVED_USERNAME_ACTION_CAP) {
            ReservedUsernamesTestSupport.seedReservedAudit(dataSource, adminA)
        }

        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, adminA) shouldBe true }
        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, adminB) shouldBe false }
        limiter.countInTrailingHour(adminB) shouldBe 0
    }
})
