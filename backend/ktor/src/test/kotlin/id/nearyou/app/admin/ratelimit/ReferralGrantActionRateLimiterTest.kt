package id.nearyou.app.admin.ratelimit

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.subscriptiongrace.GraceTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * Repository-level tests for [ReferralGrantActionRateLimiter] — the
 * COUNT-over-`admin_actions_log` substrate of the manual-grant 10/hr cap
 * (`admin-referral-manual-grant` tasks.md 2.2). DB-backed; tagged `database`.
 * Seeds `admin_actions_log` rows with a controlled RELATIVE age (vs DB `NOW()`)
 * so the action-set + trailing-hour window are exercised without a host-clock
 * flake. The at-cap reject side effect is covered by [ReferralGrantRepositoryTest].
 */
@Tags("database")
class ReferralGrantActionRateLimiterTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val limiter = ReferralGrantActionRateLimiter(dataSource)
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun newAdmin(): UUID = AdminAuthTestSupport.seedAdmin(dataSource).also { seededAdmins += it.id }.id

    "2.2 — counts only in-window referral_manual_grant rows for the acting admin" {
        val admin = newAdmin()
        GraceTestSupport.seedAuditRow(dataSource, admin, "referral_manual_grant")
        GraceTestSupport.seedAuditRow(dataSource, admin, "referral_manual_grant")
        // A different action type in-window — must NOT count.
        GraceTestSupport.seedAuditRow(dataSource, admin, "user_suspended")
        // A grant OUTSIDE the trailing hour — must NOT count.
        GraceTestSupport.seedAuditRow(dataSource, admin, "referral_manual_grant", createdAt = Instant.now().minusSeconds(2 * 3600))

        limiter.countInTrailingHour(admin) shouldBe 2
    }

    "2.2 — isAtOrOverCap is false at cap-1 and true at cap" {
        val admin = newAdmin()
        repeat(ReferralGrantActionRateLimiter.REFERRAL_MANUAL_GRANT_CAP - 1) {
            GraceTestSupport.seedAuditRow(dataSource, admin, "referral_manual_grant")
        }
        limiter.isAtOrOverCap(admin) shouldBe false

        GraceTestSupport.seedAuditRow(dataSource, admin, "referral_manual_grant") // reach the cap
        limiter.isAtOrOverCap(admin) shouldBe true
    }

    "2.2 — the cap is per-admin: A at the cap does not constrain B" {
        val adminA = newAdmin()
        val adminB = newAdmin()
        repeat(ReferralGrantActionRateLimiter.REFERRAL_MANUAL_GRANT_CAP) {
            GraceTestSupport.seedAuditRow(dataSource, adminA, "referral_manual_grant")
        }
        limiter.isAtOrOverCap(adminA) shouldBe true
        limiter.isAtOrOverCap(adminB) shouldBe false
        limiter.countInTrailingHour(adminB) shouldBe 0
    }
})
