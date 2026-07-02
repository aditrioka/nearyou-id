package id.nearyou.app.admin.referralgrants

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.ratelimit.ReferralGrantActionRateLimiter
import id.nearyou.app.admin.subscriptiongrace.GraceTestSupport
import id.nearyou.app.infra.revenuecatapi.GrantRequest
import id.nearyou.app.infra.revenuecatapi.GrantResult
import id.nearyou.app.infra.revenuecatapi.NoOpReferralEntitlementGranter
import id.nearyou.app.infra.revenuecatapi.ReferralEntitlementGranter
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeTypeOf
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository-level integration tests for [AdminReferralGrantRepository]
 * (`admin-referral-manual-grant` tasks.md 7.3/7.4/7.6/7.7/7.8/7.12/7.13). DB-backed;
 * tagged `database`. A fake [ReferralEntitlementGranter] captures the dispatched
 * [GrantRequest] (so stacking math is asserted exactly) and returns a configured
 * outcome; a FIXED clock makes `endTimeMs` deterministic.
 *
 * Covers the grant flow's spec invariants that need a configured granter or a
 * fixed clock — stacking (free / active / expired), fail-soft dispatch, the
 * `granted_entitlements` / `subscription_events` / `subscription_status`
 * no-write invariants, the distinct rate-limit cap (no dispatch, no audit row),
 * dispatch failure (audit written, not activated), and the per-grant +7d
 * double-extend behavior. The auth/CSRF/role/HTMX surface is covered by
 * [AdminReferralGrantsRouteTest].
 */
@Tags("database")
class AdminReferralGrantRepositoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        seededUsers.forEach { GraceTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun admin() = AdminAuthTestSupport.seedAdmin(dataSource).also { seededAdmins.add(it.id) }.id

    fun user(status: String = "free") =
        GraceTestSupport.seedUser(dataSource, "rg_${UUID.randomUUID().toString().take(8)}", status).also { seededUsers.add(it) }

    fun repo(
        granter: ReferralEntitlementGranter,
        now: Instant,
    ) = AdminReferralGrantRepository(
        dataSource,
        granter,
        id.nearyou.app.admin.auth.AdminAuditLogger(dataSource),
        ReferralGrantActionRateLimiter(dataSource),
        clock = { now },
    )

    // ---- 7.3 stacking math (extend-if-active / fresh-if-free / GREATEST floor) ----

    "7.3 — a free user is granted a fresh NOW()+7d entitlement" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val granter = RecordingGranter()
        val outcome = repo(granter, now).grant(user(), admin(), "SUP-1", "127.0.0.1", null)

        outcome.shouldBeTypeOf<GrantOutcome.Dispatched>()
        granter.requests.single().endTimeMs shouldBe now.plus(Duration.ofDays(7)).toEpochMilli()
    }

    "7.3 — an active-premium user is extended by 7d (stacked, not reset)" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val u = user("premium_active")
        val currentEnd = now.plus(Duration.ofDays(30))
        seedEventEnd(dataSource, u, currentEnd)
        val granter = RecordingGranter()

        repo(granter, now).grant(u, admin(), "SUP-2", "127.0.0.1", null)

        granter.requests.single().endTimeMs shouldBe currentEnd.plus(Duration.ofDays(7)).toEpochMilli()
    }

    "7.3 — an EXPIRED-but-recent entitlement grants fresh NOW()+7d (GREATEST floor, not stacked onto the stale end)" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val u = user("free")
        seedEventEnd(dataSource, u, now.minus(Duration.ofDays(10))) // expired
        val granter = RecordingGranter()

        repo(granter, now).grant(u, admin(), "SUP-3", "127.0.0.1", null)

        granter.requests.single().endTimeMs shouldBe now.plus(Duration.ofDays(7)).toEpochMilli()
    }

    // ---- 7.3 dispatch request shape ----

    "7.3 — the dispatched request uses the user UUID as appUserId and the premium entitlement id" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val u = user()
        val granter = RecordingGranter()

        repo(granter, now).grant(u, admin(), "SUP-4", "127.0.0.1", null)

        val req = granter.requests.single()
        req.appUserId shouldBe u.toString()
        req.entitlementId shouldBe "premium"
    }

    // ---- 7.4 fail-soft (RC unconfigured) ----

    "7.4 — RC unconfigured fails soft: no throw, audit row still written, outcome is skipped" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val a = admin()
        val outcome = repo(NoOpReferralEntitlementGranter, now).grant(user(), a, "SUP-5", "127.0.0.1", null)

        outcome.shouldBeTypeOf<GrantOutcome.DispatchSkipped>()
        referralGrantAuditCount(dataSource, a) shouldBe 1
    }

    // ---- 7.6 invariant: never writes granted_entitlements ----

    "7.6 — a grant inserts no granted_entitlements row (inviter lifetime-cap untouched)" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val u = user()
        repo(RecordingGranter(), now).grant(u, admin(), "SUP-6", "127.0.0.1", null)

        countRows(dataSource, "granted_entitlements", u) shouldBe 0
    }

    // ---- 7.7 ownership: writes no subscription_events / no subscription_status ----

    "7.7 — the admin path writes no subscription_events row and no subscription_status update" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val u = user("free")
        repo(RecordingGranter(), now).grant(u, admin(), "SUP-7", "127.0.0.1", null)

        countRows(dataSource, "subscription_events", u) shouldBe 0
        GraceTestSupport.subscriptionStatusOf(dataSource, u) shouldBe "free"
    }

    // ---- 7.8 rate-limit: over cap → no dispatch, no audit row ----

    "7.8 — at/over the per-hour cap the grant is rejected with no dispatch and no audit row" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val a = admin()
        repeat(ReferralGrantActionRateLimiter.REFERRAL_MANUAL_GRANT_CAP) {
            GraceTestSupport.seedAuditRow(dataSource, a, "referral_manual_grant")
        }
        val granter = RecordingGranter()

        val outcome = repo(granter, now).grant(user(), a, "SUP-8", "127.0.0.1", null)

        outcome shouldBe GrantOutcome.RateLimited
        granter.requests.size shouldBe 0
        referralGrantAuditCount(dataSource, a) shouldBe ReferralGrantActionRateLimiter.REFERRAL_MANUAL_GRANT_CAP // no new row
    }

    "7.8 — under the cap the grant proceeds" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val a = admin()
        repeat(ReferralGrantActionRateLimiter.REFERRAL_MANUAL_GRANT_CAP - 1) {
            GraceTestSupport.seedAuditRow(dataSource, a, "referral_manual_grant")
        }
        val outcome = repo(RecordingGranter(), now).grant(user(), a, "SUP-8b", "127.0.0.1", null)

        outcome.shouldBeTypeOf<GrantOutcome.Dispatched>()
    }

    // ---- 7.12 dispatch failure (RC configured, rejected) ----

    "7.12 — a Failed dispatch still writes the audit row, does not activate Premium, and counts against the cap" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val a = admin()
        val u = user("free")
        val granter = RecordingGranter(GrantResult.Failed("rc 422"))

        val outcome = repo(granter, now).grant(u, a, "SUP-12", "127.0.0.1", null)

        outcome.shouldBeTypeOf<GrantOutcome.DispatchFailed>()
        referralGrantAuditCount(dataSource, a) shouldBe 1 // recorded → counts against the cap
        GraceTestSupport.subscriptionStatusOf(dataSource, u) shouldBe "free" // not activated
        AdminAuthTestSupport.latestAuditRows(dataSource, a)
            .first { it.actionType == "referral_manual_grant" }
            .afterState!! shouldContain "failed"
    }

    // ---- 7.13 double-extend: two grants stack +7d each (via the webhook echo) ----

    "7.13 — two grants within the window stack +7d each (bounded, recoverable)" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val a = admin()
        val u = user("free")
        val granter = RecordingGranter()
        val r = repo(granter, now)

        r.grant(u, a, "SUP-13a", "127.0.0.1", null)
        // Simulate the GRANT webhook echo landing (the admin path itself writes no
        // subscription_events row — activation is the echo's job, design D3).
        seedEventEnd(dataSource, u, Instant.ofEpochMilli(granter.requests[0].endTimeMs))
        r.grant(u, a, "SUP-13b", "127.0.0.1", null)

        granter.requests[0].endTimeMs shouldBe now.plus(Duration.ofDays(7)).toEpochMilli()
        granter.requests[1].endTimeMs shouldBe now.plus(Duration.ofDays(14)).toEpochMilli()
        referralGrantAuditCount(dataSource, a) shouldBe 2
    }

    // ---- soft-deleted target: rejected pre-dispatch, nothing written ----

    "a soft-deleted target is rejected — no dispatch, no audit row" {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val a = admin()
        val u = user()
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET deleted_at = NOW() WHERE id = ?").use { ps ->
                ps.setObject(1, u)
                ps.executeUpdate()
            }
        }
        val granter = RecordingGranter()

        val outcome = repo(granter, now).grant(u, a, "SUP-TOMB", "127.0.0.1", null)

        outcome shouldBe GrantOutcome.DeletedUser
        granter.requests.size shouldBe 0
        referralGrantAuditCount(dataSource, a) shouldBe 0
    }

    // ---- 7.10 cursor codec (the keyset round-trip substrate) ----

    "7.10 — a GrantsCursor encode/decode round-trip is lossless (micros precision)" {
        val cursor = GrantsCursor(Instant.parse("2026-06-10T10:11:12.123456Z"), UUID.randomUUID())
        GrantsCursor.decode(cursor.encode()) shouldBe cursor
    }

    "7.10 — a malformed cursor token decodes to null (first page), never throws" {
        GrantsCursor.decode(null) shouldBe null
        GrantsCursor.decode("") shouldBe null
        GrantsCursor.decode("%%%not-base64%%%") shouldBe null
        GrantsCursor.decode("bm8tcGlwZS1oZXJl") shouldBe null // valid base64, no "|" separator
    }
})

/** Fake port: captures every dispatched [GrantRequest] and returns a fixed [result]. */
private class RecordingGranter(
    private val result: GrantResult = GrantResult.Dispatched,
) : ReferralEntitlementGranter {
    val requests = mutableListOf<GrantRequest>()

    override fun isConfigured(): Boolean = result != GrantResult.NotConfigured

    override suspend fun grant(request: GrantRequest): GrantResult {
        requests += request
        return result
    }
}

/** Seed one `subscription_events` row carrying [end] as `entitlement_end` (the stacking floor). */
private fun seedEventEnd(
    dataSource: DataSource,
    userId: UUID,
    end: Instant,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO subscription_events (user_id, event_type, source, entitlement_end) VALUES (?, 'grant', 'referral', ?)",
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setObject(2, Timestamp.from(end))
            ps.executeUpdate()
        }
    }
}

private fun referralGrantAuditCount(
    dataSource: DataSource,
    adminId: UUID,
): Int = AdminAuthTestSupport.latestAuditRows(dataSource, adminId).count { it.actionType == "referral_manual_grant" }

private fun countRows(
    dataSource: DataSource,
    table: String,
    userId: UUID,
): Int =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT count(*) FROM $table WHERE user_id = ?").use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
