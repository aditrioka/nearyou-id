package id.nearyou.app.admin.rejectedidentifiers

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.ratelimit.RejectedIdentifierClearRateLimiter
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Repository-level integration tests for [RejectedIdentifierClearRateLimiter] —
 * the COUNT-over-`admin_actions_log` substrate of the rejected-identifier clear
 * 10/hr cap (`admin-rejected-identifiers-clear-action` tasks.md 4.6). DB-backed;
 * tagged `database`. Seeds `admin_actions_log` rows directly with a controlled
 * RELATIVE age (vs DB `NOW()`, not an absolute client-side `Instant`) so the
 * action-set + trailing-one-hour window are exercised without a host-clock-
 * precision flake; the at-cap reject side is covered at the repository level by
 * [AdminRejectedIdentifiersRepositoryTest].
 */
@Tags("database")
class RejectedIdentifierClearRateLimiterTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val limiter = RejectedIdentifierClearRateLimiter(dataSource)
    val seededAdmins = mutableListOf<UUID>()
    afterTest {
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun newAdmin(): UUID = AdminAuthTestSupport.seedAdmin(dataSource).also { seededAdmins += it.id }.id

    "4.6 — counts only in-window rejected_identifier_cleared rows for the acting admin" {
        val admin = newAdmin()
        RejectedIdentifiersTestSupport.seedClearAudit(dataSource, admin)
        RejectedIdentifiersTestSupport.seedClearAudit(dataSource, admin)
        // A different (punitive) action type in-window — must NOT count.
        NonClearActionSeed.seedOtherAction(dataSource, admin, "user_suspended")
        // A clear OUTSIDE the trailing hour — must NOT count.
        RejectedIdentifiersTestSupport.seedClearAudit(dataSource, admin, ageMinutes = 120)

        limiter.countInTrailingHour(admin) shouldBe 2
    }

    "4.6 — isAtOrOverCap is false at 9 and true at 10" {
        val admin = newAdmin()
        repeat(9) { RejectedIdentifiersTestSupport.seedClearAudit(dataSource, admin) }
        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, admin) shouldBe false }

        RejectedIdentifiersTestSupport.seedClearAudit(dataSource, admin) // 10th
        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, admin) shouldBe true }
    }

    "4.6 — the cap is per-admin: A at the cap does not constrain B" {
        val adminA = newAdmin()
        val adminB = newAdmin()
        repeat(RejectedIdentifierClearRateLimiter.REJECTED_IDENTIFIER_CLEAR_CAP) {
            RejectedIdentifiersTestSupport.seedClearAudit(dataSource, adminA)
        }

        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, adminA) shouldBe true }
        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, adminB) shouldBe false }
        limiter.countInTrailingHour(adminB) shouldBe 0
    }
})

/** Tiny local helper to seed a non-clear `admin_actions_log` row (for the
 *  "other action types don't count" assertion) without widening the shared
 *  test-support surface. Relative age vs DB NOW(), parameterized. */
private object NonClearActionSeed {
    fun seedOtherAction(
        dataSource: javax.sql.DataSource,
        adminId: UUID,
        actionType: String,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, created_at) " +
                    "VALUES (?, ?, 'user', ?, NOW() - INTERVAL '1 minute')",
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.setString(2, actionType)
                ps.setString(3, UUID.randomUUID().toString())
                ps.executeUpdate()
            }
        }
    }
}
