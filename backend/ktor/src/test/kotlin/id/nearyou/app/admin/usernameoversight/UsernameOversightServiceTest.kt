package id.nearyou.app.admin.usernameoversight

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.ratelimit.UsernameOversightActionRateLimiter
import id.nearyou.app.infra.repo.JdbcUsernameFlagOverrideRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * Service-layer transaction tests for [UsernameOversightService]
 * (`admin-premium-username-oversight` §8.7 atomicity + the §8.4-adjacent override
 * write semantics, exercised directly so the rollback boundary is asserted without
 * the HTTP shell). DB-backed; tagged `database`. Bounded Hikari pool
 * (`autoClose` + size 2, per §8.8 / the CI connection budget).
 *
 * The atomicity fault is injected via a temporary `NOT VALID CHECK` on
 * `admin_actions_log` (the same DB-layer fault-injection `ReportResolutionRepositoryTest`
 * 4.8 + `UserModerationRepositoryTest` 8.4 use — the repository SQL has no app-level
 * injection seam): the audit INSERT trips the constraint, so the queue + override
 * writes in the SAME transaction must roll back.
 */
@Tags("database")
class UsernameOversightServiceTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val auditLogger = AdminAuditLogger(dataSource)
    val rateLimiter = UsernameOversightActionRateLimiter(dataSource)
    val repository = UsernameOversightRepository(dataSource)
    val flagOverrides = JdbcUsernameFlagOverrideRepository()
    val service = UsernameOversightService(dataSource, repository, flagOverrides, rateLimiter, auditLogger)

    val seededAdmins = mutableListOf<UUID>()
    val seededUsers = mutableListOf<UUID>()
    afterEach {
        UsernameOversightTestSupport.cleanup(dataSource, seededUsers)
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(): UUID = AdminAuthTestSupport.seedAdmin(dataSource).id.also { seededAdmins += it }

    fun seedUser(): UUID = UsernameOversightTestSupport.seedUser(dataSource).also { seededUsers += it }

    "8.7 — an injected audit-write failure rolls back the accept resolution AND the override" {
        val admin = seedAdmin()
        val u = seedUser()
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "atomiccand")

        withFailingConstraint(
            dataSource,
            "admin_actions_log",
            "zzz_fail_username_flag_resolved_audit",
            "action_type <> 'username_flag_resolved'",
        ) {
            shouldThrow<Exception> {
                service.resolveFlag(flagId, FlagResolution.ACCEPT, admin, "127.0.0.1", null)
            }
        }
        // Full rollback: the queue stays pending, NO override row, NO audit row.
        UsernameOversightTestSupport.queueState(dataSource, flagId)!!.status shouldBe "pending"
        UsernameOversightTestSupport.overrideCount(dataSource, u) shouldBe 0
        UsernameOversightTestSupport.auditCount(dataSource, admin, "username_flag_resolved") shouldBe 0
    }

    "8.7 — an injected audit-write failure rolls back the hold release" {
        val admin = seedAdmin()
        val u = seedUser()
        val histId =
            UsernameOversightTestSupport.seedHistory(
                dataSource,
                u,
                oldUsername = "atomic_held",
                newUsername = "x",
                releasedAt = Instant.now().plusSeconds(10L * 24 * 3600),
            )

        withFailingConstraint(
            dataSource,
            "admin_actions_log",
            "zzz_fail_username_hold_released_audit",
            "action_type <> 'username_hold_released'",
        ) {
            shouldThrow<Exception> {
                service.releaseHold(histId, admin, "127.0.0.1", null)
            }
        }
        // Full rollback: the hold is intact, no audit row.
        UsernameOversightTestSupport.isHeld(dataSource, histId) shouldBe true
        UsernameOversightTestSupport.auditCount(dataSource, admin, "username_hold_released") shouldBe 0
    }

    "8.3-svc — accept re-arms a previously-consumed override (ON CONFLICT resets consumed_at)" {
        val admin = seedAdmin()
        val u = seedUser()
        // Pre-seed a CONSUMED override for the candidate, then accept the flag again.
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO username_flag_overrides (user_id, candidate, approved_by, consumed_at) VALUES (?, ?, ?, NOW())",
            ).use { ps ->
                ps.setObject(1, u)
                ps.setString(2, "rearmcand")
                ps.setObject(3, admin)
                ps.executeUpdate()
            }
        }
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "rearmcand")
        service.resolveFlag(flagId, FlagResolution.ACCEPT, admin, "127.0.0.1", null) shouldBe FlagResolveOutcome.Applied
        val overrides = UsernameOversightTestSupport.overridesFor(dataSource, u)
        overrides.size shouldBe 1 // UNIQUE(user_id, candidate) → still one row, re-armed
        overrides[0].consumed shouldBe false // consumed_at reset to NULL
    }

    "8.3-svc — a not-found queue id is NotFound (no write)" {
        val admin = seedAdmin()
        service.resolveFlag(UUID.randomUUID(), FlagResolution.REJECT, admin, "127.0.0.1", null) shouldBe FlagResolveOutcome.NotFound
    }

    "8.5-svc — a not-found history id is NotFound (no write)" {
        val admin = seedAdmin()
        service.releaseHold(UUID.randomUUID(), admin, "127.0.0.1", null) shouldBe HoldReleaseOutcome.NotFound
    }
})

/**
 * Install a `NOT VALID CHECK` [constraintName] on [table] enforcing [checkExpr] for
 * the duration of [block], then drop it. NOT VALID skips existing rows but enforces
 * new INSERTs — the same DB-layer fault-injection `ReportResolutionRepositoryTest`
 * 4.8 uses (the repository SQL has no app-level injection seam).
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
