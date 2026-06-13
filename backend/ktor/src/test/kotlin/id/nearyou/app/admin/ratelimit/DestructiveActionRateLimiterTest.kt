package id.nearyou.app.admin.ratelimit

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository-level integration tests for [DestructiveActionRateLimiter] — the
 * COUNT-over-`admin_actions_log` substrate of the `admin-destructive-action-
 * rate-limit` capability (tasks.md 7.10 count math, 7.11 at/over-cap boundary,
 * 7.12 per-admin scope). DB-backed; tagged `database`.
 *
 * Seeds `admin_actions_log` rows directly with controlled `action_type` /
 * `after_state` / `created_at` age so the disjoint destructive predicate (the
 * two arms: `user_warned`/`user_suspended` vs `moderation_queue_resolved` +
 * `after_state->>'resolution'`) and the trailing-one-hour window are exercised
 * without driving the full suspend/warn/resolve transactions. The reject-side
 * (a 21st action returns the quota-exceeded outcome with no mutation) is covered
 * at the repository level by `UserModerationRepositoryTest` /
 * `ReportResolutionRepositoryTest`.
 */
@Tags("database")
class DestructiveActionRateLimiterTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val limiter = DestructiveActionRateLimiter(dataSource)
    val seededAdmins = mutableListOf<UUID>()
    afterTest {
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun newAdmin(): UUID = AdminAuthTestSupport.seedAdmin(dataSource).also { seededAdmins += it.id }.id

    "counts only the in-window destructive actions (3 suspend + 2 warn + 1 queue-ban = 6)" {
        val admin = newAdmin()
        repeat(3) { insertAudit(dataSource, admin, "user_suspended") }
        repeat(2) { insertAudit(dataSource, admin, "user_warned") }
        insertAudit(dataSource, admin, "moderation_queue_resolved", afterState = """{"resolution":"ban_author"}""")

        limiter.countInTrailingHour(admin) shouldBe 6
    }

    "excludes non-destructive action types AND out-of-window rows (count 0)" {
        val admin = newAdmin()
        // Non-destructive types in-window — must NOT count.
        insertAudit(dataSource, admin, "user_unbanned")
        insertAudit(dataSource, admin, "moderation_queue_resolved", afterState = """{"resolution":"hide"}""")
        insertAudit(dataSource, admin, "report_resolved")
        insertAudit(dataSource, admin, "admin_login_success")
        // Destructive types but OUTSIDE the trailing hour — must NOT count.
        repeat(5) { insertAudit(dataSource, admin, "user_suspended", ageMinutes = 120) }

        limiter.countInTrailingHour(admin) shouldBe 0
    }

    "isAtOrOverCap is false at 19 and true at 20" {
        val admin = newAdmin()
        repeat(19) { insertAudit(dataSource, admin, "user_suspended") }
        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, admin) shouldBe false }

        insertAudit(dataSource, admin, "user_suspended") // 20th
        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, admin) shouldBe true }
    }

    "the cap is per-admin: A at the cap does not constrain B" {
        val adminA = newAdmin()
        val adminB = newAdmin()
        repeat(DestructiveActionRateLimiter.DESTRUCTIVE_ACTION_CAP) { insertAudit(dataSource, adminA, "user_warned") }

        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, adminA) shouldBe true }
        dataSource.connection.use { c -> limiter.isAtOrOverCap(c, adminB) shouldBe false }
        limiter.countInTrailingHour(adminB) shouldBe 0
    }
})

/** Seed one `admin_actions_log` row with a controlled type / after_state / age. */
private fun insertAudit(
    dataSource: DataSource,
    adminId: UUID,
    actionType: String,
    afterState: String? = null,
    ageMinutes: Int = 1,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, after_state, created_at) " +
                "VALUES (?, ?, 'user', ?, ?::jsonb, NOW() - (? * INTERVAL '1 minute'))",
        ).use { ps ->
            ps.setObject(1, adminId)
            ps.setString(2, actionType)
            ps.setString(3, UUID.randomUUID().toString())
            ps.setString(4, afterState)
            ps.setInt(5, ageMinutes)
            ps.executeUpdate()
        }
    }
}
