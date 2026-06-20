package id.nearyou.app.admin.moderation

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.SuspensionUnbanWorker
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.ratelimit.DestructiveActionRateLimiter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Repository-level integration tests for `UserModerationRepository` — the
 * atomic suspend / unban transactions, the audit-row + notification writes,
 * and the lookup resolver (`admin-user-moderation` capability, tasks.md
 * Section 8: 8.1–8.6 + the repo half of 8.9).
 *
 * DB-backed; tagged `database`. Calls the repository directly (the HTTP auth /
 * CSRF / role gate + `clientIp` wiring is exercised by
 * [AdminUserModerationRouteTest]). The atomicity scenarios (8.4) inject a
 * DB-layer `NOT VALID CHECK` fault — mirroring `SuspensionUnbanWorkerTest`
 * "4.2 failed audit INSERT rolls back the unban" — because the repository SQL
 * has no app-level injection seam.
 */
@Tags("database")
class UserModerationRepositoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val auditLogger = AdminAuditLogger(dataSource)
    val repo = UserModerationRepository(dataSource, auditLogger, DestructiveActionRateLimiter(dataSource))

    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        UserModerationTestSupport.cleanupUser(dataSource, *seededUsers.toTypedArray())
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedUser(
        isBanned: Boolean = false,
        suspendedUntil: Instant? = null,
        deletedAt: Instant? = null,
        username: String? = null,
        isShadowBanned: Boolean = false,
    ): UUID =
        UserModerationTestSupport.seedUser(dataSource, isBanned, suspendedUntil, deletedAt, username, isShadowBanned)
            .also { seededUsers += it }

    fun seedAdmin(role: String = "owner"): UUID = AdminAuthTestSupport.seedAdmin(dataSource, role = role).id.also { seededAdmins += it }

    val ip = "203.0.113.7"
    val ua = "moderation-test-agent/1.0"

    // Reasonless unban with the spec-scope ip/ua — keeps the 8.3 assertions short.
    fun unbanOutcome(
        uid: UUID,
        role: String,
        admin: UUID,
    ): UnbanOutcome = repo.unban(uid, actingAdminRole = role, actingAdminId = admin, reason = null, ip = ip, userAgent = ua)

    // ============================ 8.1 suspend apply ============================

    "8.1a active user is suspended ~7 days out (duration is server-fixed)" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        val outcome = repo.suspend(uid, admin, reason = null, ip = ip, userAgent = ua)

        val applied = outcome.shouldBeInstanceOf<SuspendOutcome.Applied>()
        isRoughlySevenDaysOut(applied.suspendedUntil) shouldBe true
        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe true
        row.suspendedUntil.shouldNotBeNull()
        isRoughlySevenDaysOut(row.suspendedUntil) shouldBe true
    }

    "8.1b re-suspending a future-dated suspension resets the clock + before_state records prior expiry" {
        val admin = seedAdmin()
        val priorExpiry = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
        val uid = seedUser(isBanned = true, suspendedUntil = priorExpiry)

        repo.suspend(uid, admin, reason = null, ip = ip, userAgent = ua).shouldBeInstanceOf<SuspendOutcome.Applied>()

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        isRoughlySevenDaysOut(row.suspendedUntil!!) shouldBe true
        // before_state.suspended_until == the prior expiry by parsed-Instant
        // value-equality (NOT string match), per design D6.
        val audit = UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended").single()
        val beforeExpiry = parseSuspendedUntil(audit.beforeStateJson)
        beforeExpiry.shouldNotBeNull()
        beforeExpiry.truncatedTo(ChronoUnit.SECONDS) shouldBe priorExpiry.truncatedTo(ChronoUnit.SECONDS)
    }

    "8.1c re-suspending an elapsed-but-unswept window is allowed (guard keys on IS NULL, not > NOW())" {
        val admin = seedAdmin()
        val elapsed = Instant.now().minus(1, ChronoUnit.HOURS)
        val uid = seedUser(isBanned = true, suspendedUntil = elapsed, deletedAt = null)

        repo.suspend(uid, admin, reason = null, ip = ip, userAgent = ua).shouldBeInstanceOf<SuspendOutcome.Applied>()

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe true
        isRoughlySevenDaysOut(row.suspendedUntil!!) shouldBe true
    }

    // ============================ 8.2 suspend reject ===========================

    "8.2a suspending a soft-deleted user is rejected — no state change, no audit, no notification" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false, deletedAt = Instant.now().minus(1, ChronoUnit.DAYS))

        repo.suspend(uid, admin, reason = "x", ip = ip, userAgent = ua) shouldBe SuspendOutcome.RejectedSoftDeleted

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe false
        row.suspendedUntil.shouldBeNull()
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid) shouldHaveSize 0
        UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
    }

    "8.2b suspending a permanently-banned user is rejected — no downgrade, no audit" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = true, suspendedUntil = null)

        repo.suspend(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe SuspendOutcome.RejectedPermanentBan

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe true
        row.suspendedUntil.shouldBeNull()
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid) shouldHaveSize 0
        UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
    }

    // ============================ 8.3 unban ====================================

    "8.3a time-bound-suspended user is unbanned (owner)" {
        val admin = seedAdmin(role = "owner")
        val uid = seedUser(isBanned = true, suspendedUntil = Instant.now().plus(5, ChronoUnit.DAYS))

        unbanOutcome(uid, "owner", admin) shouldBe UnbanOutcome.Applied

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe false
        row.suspendedUntil.shouldBeNull()
    }

    "8.3b permanently-banned user is unbanned by an admin override" {
        val admin = seedAdmin(role = "admin")
        val uid = seedUser(isBanned = true, suspendedUntil = null)

        unbanOutcome(uid, "admin", admin) shouldBe UnbanOutcome.Applied

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe false
        row.suspendedUntil.shouldBeNull()
    }

    "8.3c unbanning a not-currently-banned user is a no-op that writes no audit row" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        unbanOutcome(uid, "owner", admin) shouldBe UnbanOutcome.NoOpNotBanned

        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid) shouldHaveSize 0
    }

    "8.3d soft-deleted-but-banned user may be unbanned; deleted_at unchanged" {
        val admin = seedAdmin()
        val deletedAt = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
        val uid = seedUser(isBanned = true, suspendedUntil = Instant.now().plus(3, ChronoUnit.DAYS), deletedAt = deletedAt)

        unbanOutcome(uid, "owner", admin) shouldBe UnbanOutcome.Applied

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe false
        row.suspendedUntil.shouldBeNull()
        row.deletedAt.shouldNotBeNull()
        row.deletedAt.truncatedTo(ChronoUnit.SECONDS) shouldBe deletedAt.truncatedTo(ChronoUnit.SECONDS)
    }

    "8.3e moderator may unban a time-bound suspension" {
        val admin = seedAdmin(role = "moderator")
        val uid = seedUser(isBanned = true, suspendedUntil = Instant.now().plus(4, ChronoUnit.DAYS))

        unbanOutcome(uid, "moderator", admin) shouldBe UnbanOutcome.Applied

        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
    }

    "8.3f moderator is forbidden from unbanning a permanent ban — no state change, no audit row" {
        val admin = seedAdmin(role = "moderator")
        val uid = seedUser(isBanned = true, suspendedUntil = null)

        unbanOutcome(uid, "moderator", admin) shouldBe UnbanOutcome.ForbiddenPermanentBan

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe true
        row.suspendedUntil.shouldBeNull()
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_unbanned") shouldHaveSize 0
    }

    // ============================ 8.4 atomicity ================================

    "8.4a audit-insert failure rolls back the suspend (no state change, no audit, no notification)" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        withFailingConstraint(
            dataSource,
            "admin_actions_log",
            "zzz_fail_user_suspended_audit",
            "action_type <> 'user_suspended'",
        ) {
            shouldThrow<Exception> { repo.suspend(uid, admin, reason = null, ip = ip, userAgent = ua) }
            val row = UserModerationTestSupport.loadUser(dataSource, uid)
            row.isBanned shouldBe false
            row.suspendedUntil.shouldBeNull()
            UserModerationTestSupport.auditRowsForTarget(dataSource, uid) shouldHaveSize 0
            UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
        }
    }

    "8.4b notification-insert failure (last write) rolls back the user update AND the audit row" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        withFailingConstraint(
            dataSource,
            "notifications",
            "zzz_fail_account_action_notif",
            "type <> 'account_action_applied'",
        ) {
            shouldThrow<Exception> { repo.suspend(uid, admin, reason = null, ip = ip, userAgent = ua) }
            val row = UserModerationTestSupport.loadUser(dataSource, uid)
            row.isBanned shouldBe false
            row.suspendedUntil.shouldBeNull()
            UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended") shouldHaveSize 0
            UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
        }
    }

    "8.4c successful suspend commits the update, the audit row, and the notification together" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        repo.suspend(uid, admin, reason = null, ip = ip, userAgent = ua).shouldBeInstanceOf<SuspendOutcome.Applied>()

        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe true
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended") shouldHaveSize 1
        UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 1
    }

    // ============================ 8.5 audit-row shape ==========================

    "8.5a suspend writes a user_suspended row with before/after state, target, reason, ip, ua" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        repo.suspend(uid, admin, reason = "spam and harassment", ip = ip, userAgent = ua)

        val audit = UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended").single()
        audit.actionType shouldBe "user_suspended"
        audit.targetType shouldBe "user"
        audit.targetId shouldBe uid.toString()
        audit.reason shouldBe "spam and harassment"
        audit.ip shouldBe ip
        audit.userAgent shouldBe ua
        // admin_id is the acting human admin, NEVER the system sentinel (3.2).
        audit.adminId shouldBe admin
        audit.adminId shouldNotBe SuspensionUnbanWorker.SYSTEM_ACTOR_ID
        parseIsBanned(audit.beforeStateJson) shouldBe false
        parseIsBanned(audit.afterStateJson) shouldBe true
        parseSuspendedUntil(audit.afterStateJson).shouldNotBeNull()
    }

    "8.5b unban writes a user_unbanned row with before/after = {true,prior} → {false,null}" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = true, suspendedUntil = Instant.now().plus(3, ChronoUnit.DAYS))

        repo.unban(uid, actingAdminRole = "owner", actingAdminId = admin, reason = null, ip = ip, userAgent = ua)

        val audit = UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_unbanned").single()
        audit.targetType shouldBe "user"
        audit.targetId shouldBe uid.toString()
        audit.reason.shouldBeNull()
        audit.adminId shouldBe admin
        audit.adminId shouldNotBe SuspensionUnbanWorker.SYSTEM_ACTOR_ID
        parseIsBanned(audit.beforeStateJson) shouldBe true
        parseIsBanned(audit.afterStateJson) shouldBe false
        parseSuspendedUntil(audit.afterStateJson).shouldBeNull()
    }

    "8.5c suspend with a null user_agent records NULL user_agent" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        repo.suspend(uid, admin, reason = null, ip = ip, userAgent = null)

        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended").single().userAgent.shouldBeNull()
    }

    // ============================ 8.6 notification =============================

    "8.6 suspend inserts one sanitized account_action_applied notification; free-text reason absent; unban none" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        repo.suspend(uid, admin, reason = "suspected minor, see report #42", ip = ip, userAgent = ua)

        val notifs = UserModerationTestSupport.notificationsForUser(dataSource, uid)
        notifs shouldHaveSize 1
        val notif = notifs.single()
        notif.type shouldBe "account_action_applied"
        notif.actorUserId.shouldBeNull()
        val body = Json.parseToJsonElement(notif.bodyDataJson!!).jsonObject
        body["action_type"]!!.jsonPrimitive.content shouldBe "user_suspended"
        body["reason"]!!.jsonPrimitive.content shouldBe "suspension"
        body["suspended_until"]!!.jsonPrimitive.content.let { Instant.parse(it) }.let { isRoughlySevenDaysOut(it) } shouldBe true
        // The moderator-internal free-text reason MUST NOT leak to the user-facing notification.
        notif.bodyDataJson shouldNotContain "suspected minor"

        // Unban writes no notification.
        repo.unban(uid, actingAdminRole = "owner", actingAdminId = admin, reason = null, ip = ip, userAgent = ua)
        UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 1 // still just the suspend one
    }

    // ============================ 8.9 (repo lookup) ============================

    "8.9-repo lookup resolves by UUID, by exact username, is case-sensitive, and is null on miss" {
        seedAdmin()
        val uid = seedUser(isBanned = false, username = "budi_jakarta")

        repo.lookup(uid.toString())!!.id shouldBe uid
        repo.lookup("budi_jakarta")!!.id shouldBe uid
        repo.lookup("BUDI_JAKARTA").shouldBeNull() // case-sensitive miss
        repo.lookup("does-not-exist").shouldBeNull()
    }

    // ===================== 7.6/7.8 warn apply + reason discipline ==============

    "7.6 warn writes exactly one user_warned audit row + one sanitized notification; no state mutation; admin_id = acting admin" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        val before = readModFlags(dataSource, uid)

        repo.warn(uid, actingAdminId = admin, reason = "first offense", ip = ip, userAgent = ua) shouldBe WarnOutcome.Applied

        val audit = UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_warned")
        audit shouldHaveSize 1
        audit.single().adminId shouldBe admin
        val notifs = UserModerationTestSupport.notificationsForUser(dataSource, uid)
        notifs shouldHaveSize 1
        notifs.single().type shouldBe "account_action_applied"
        parseJson.parseToJsonElement(notifs.single().bodyDataJson!!).jsonObject["action_type"]!!.jsonPrimitive.content shouldBe "warning"
        // No users-state mutation by a warning.
        val after = readModFlags(dataSource, uid)
        after shouldBe before
    }

    "7.8 the free-text reason is recorded in the audit row but NEVER in the notification body" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        val distinctive = "zz-confidential-reason-7f3a"

        repo.warn(uid, actingAdminId = admin, reason = distinctive, ip = ip, userAgent = ua) shouldBe WarnOutcome.Applied

        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_warned").single().reason shouldBe distinctive
        UserModerationTestSupport.notificationsForUser(dataSource, uid).single().bodyDataJson!!.shouldNotContain(distinctive)
    }

    "7.6 warning a soft-deleted user is rejected — no audit, no notification" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false, deletedAt = Instant.now().minus(1, ChronoUnit.DAYS))

        repo.warn(uid, actingAdminId = admin, reason = "x", ip = ip, userAgent = ua) shouldBe WarnOutcome.TargetDeleted

        UserModerationTestSupport.auditRowsForTarget(dataSource, uid) shouldHaveSize 0
        UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
    }

    // ===================== 7.7 warn atomicity ==================================

    "7.7 a failing notification insert rolls back the whole warning (no audit row, no notification)" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        // Fail ONLY the warning notification insert (account_action_applied with
        // body_data.action_type = 'warning'); the audit INSERT precedes it, so a
        // rollback proves atomicity.
        withFailingConstraint(
            dataSource,
            "notifications",
            "zzz_fail_warn_notif",
            "NOT (type = 'account_action_applied' AND body_data->>'action_type' = 'warning')",
        ) {
            shouldThrow<Exception> { repo.warn(uid, actingAdminId = admin, reason = null, ip = ip, userAgent = ua) }
        }
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_warned") shouldHaveSize 0
        UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
    }

    // ===================== 7.11/7.12 destructive-action cap ====================

    "7.11 at the cap a 21st warn is rejected (quota-exceeded), writes no audit row, mutates no target column" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        seedDestructiveActions(dataSource, admin, 20)
        val before = readModFlags(dataSource, uid)

        repo.warn(uid, actingAdminId = admin, reason = null, ip = ip, userAgent = ua) shouldBe WarnOutcome.RateLimited

        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_warned") shouldHaveSize 0
        readModFlags(dataSource, uid) shouldBe before
        DestructiveActionRateLimiter(dataSource).countInTrailingHour(admin) shouldBe 20
    }

    "7.11 at the cap a 21st suspend is rejected (quota-exceeded), no audit, is_banned/suspended_until unchanged" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        seedDestructiveActions(dataSource, admin, 20)

        repo.suspend(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe SuspendOutcome.RateLimited

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe false
        row.suspendedUntil.shouldBeNull()
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended") shouldHaveSize 0
    }

    "7.11 at 19 a suspend still applies and pushes the count to 20" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        seedDestructiveActions(dataSource, admin, 19)

        repo.suspend(uid, admin, reason = null, ip = ip, userAgent = ua).shouldBeInstanceOf<SuspendOutcome.Applied>()

        DestructiveActionRateLimiter(dataSource).countInTrailingHour(admin) shouldBe 20
    }

    "7.12 a non-destructive action (unban) applies even when the admin is at the cap" {
        val admin = seedAdmin()
        val banned = seedUser(isBanned = true, suspendedUntil = Instant.now().plus(3, ChronoUnit.DAYS))
        seedDestructiveActions(dataSource, admin, 20)

        unbanOutcome(banned, "owner", admin) shouldBe UnbanOutcome.Applied
        UserModerationTestSupport.loadUser(dataSource, banned).isBanned shouldBe false
    }

    // ============================ permanent ban (apply) ========================

    "ban: active user is permanently banned (is_banned=TRUE, suspended_until=NULL)" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        repo.permanentBan(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe BanOutcome.Applied

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe true
        row.suspendedUntil.shouldBeNull()
    }

    "ban: escalating a future-dated suspension records the prior expiry in before_state" {
        val admin = seedAdmin()
        val priorExpiry = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
        val uid = seedUser(isBanned = true, suspendedUntil = priorExpiry)

        repo.permanentBan(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe BanOutcome.Applied

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe true
        row.suspendedUntil.shouldBeNull()
        val audit = UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_banned").single()
        val beforeExpiry = parseSuspendedUntil(audit.beforeStateJson)
        beforeExpiry.shouldNotBeNull()
        beforeExpiry.truncatedTo(ChronoUnit.SECONDS) shouldBe priorExpiry.truncatedTo(ChronoUnit.SECONDS)
    }

    "ban: escalating an elapsed-but-unswept suspension is allowed" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = true, suspendedUntil = Instant.now().minus(1, ChronoUnit.HOURS))

        repo.permanentBan(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe BanOutcome.Applied

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe true
        row.suspendedUntil.shouldBeNull()
    }

    "ban: token_version is NOT modified (mirrors shipped suspend/report-queue ban)" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        val before = readModFlags(dataSource, uid)

        repo.permanentBan(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe BanOutcome.Applied

        readModFlags(dataSource, uid).tokenVersion shouldBe before.tokenVersion
    }

    "ban: a soft-deleted user is rejected — no state change, no audit, no notification" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false, deletedAt = Instant.now().minus(1, ChronoUnit.DAYS))

        repo.permanentBan(uid, admin, reason = "x", ip = ip, userAgent = ua) shouldBe BanOutcome.RejectedSoftDeleted

        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid) shouldHaveSize 0
        UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
    }

    "ban: an already-permanently-banned user is a no-op that writes no audit row" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = true, suspendedUntil = null)

        repo.permanentBan(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe BanOutcome.NoOpAlreadyBanned

        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_banned") shouldHaveSize 0
    }

    "ban: at the cap a ban is rejected (quota-exceeded), no mutation, no audit" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        seedDestructiveActions(dataSource, admin, 20)

        repo.permanentBan(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe BanOutcome.RateLimited

        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_banned") shouldHaveSize 0
    }

    "ban: a standalone ban counts toward the destructive cap" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        seedDestructiveActions(dataSource, admin, 19)

        repo.permanentBan(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe BanOutcome.Applied

        DestructiveActionRateLimiter(dataSource).countInTrailingHour(admin) shouldBe 20
    }

    "ban: audit row is user_banned, attributed to the human admin, with before/after state + ip + ua" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        repo.permanentBan(uid, admin, reason = "repeat severe violations", ip = ip, userAgent = ua)

        val audit = UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_banned").single()
        audit.actionType shouldBe "user_banned"
        audit.targetType shouldBe "user"
        audit.targetId shouldBe uid.toString()
        audit.reason shouldBe "repeat severe violations"
        audit.ip shouldBe ip
        audit.userAgent shouldBe ua
        audit.adminId shouldBe admin
        audit.adminId shouldNotBe SuspensionUnbanWorker.SYSTEM_ACTOR_ID
        parseIsBanned(audit.beforeStateJson) shouldBe false
        parseIsBanned(audit.afterStateJson) shouldBe true
        parseSuspendedUntil(audit.afterStateJson).shouldBeNull()
    }

    "ban: inserts one sanitized account_action_applied notification; free-text reason absent" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        repo.permanentBan(uid, admin, reason = "doxxing — see report #9", ip = ip, userAgent = ua)

        val notifs = UserModerationTestSupport.notificationsForUser(dataSource, uid)
        notifs shouldHaveSize 1
        val notif = notifs.single()
        notif.type shouldBe "account_action_applied"
        notif.actorUserId.shouldBeNull()
        val body = Json.parseToJsonElement(notif.bodyDataJson!!).jsonObject
        body["action_type"]!!.jsonPrimitive.content shouldBe "user_banned"
        body["reason"]!!.jsonPrimitive.content shouldBe "ban"
        notif.bodyDataJson shouldNotContain "doxxing"
    }

    "ban: audit-insert failure rolls back the ban (no state change, no audit, no notification)" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        withFailingConstraint(dataSource, "admin_actions_log", "zzz_fail_user_banned_audit", "action_type <> 'user_banned'") {
            shouldThrow<Exception> { repo.permanentBan(uid, admin, reason = null, ip = ip, userAgent = ua) }
            UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
            UserModerationTestSupport.auditRowsForTarget(dataSource, uid) shouldHaveSize 0
            UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
        }
    }

    "ban: notification-insert failure (last write) rolls back the user update AND the audit row" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        withFailingConstraint(dataSource, "notifications", "zzz_fail_ban_notif", "type <> 'account_action_applied'") {
            shouldThrow<Exception> { repo.permanentBan(uid, admin, reason = null, ip = ip, userAgent = ua) }
            UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
            UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_banned") shouldHaveSize 0
            UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
        }
    }

    // ============================ shadow ban ===================================

    "shadow-ban: active user is shadow-banned (is_shadow_banned=TRUE, no other column)" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        val before = readModFlags(dataSource, uid)

        repo.shadowBan(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe ShadowBanOutcome.Applied

        val after = readModFlags(dataSource, uid)
        after.isShadowBanned shouldBe true
        after.isBanned shouldBe before.isBanned
        after.suspendedUntil shouldBe before.suspendedUntil
        after.tokenVersion shouldBe before.tokenVersion
    }

    "shadow-ban: writes NO notification" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        repo.shadowBan(uid, admin, reason = "spam ring", ip = ip, userAgent = ua) shouldBe ShadowBanOutcome.Applied

        UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
    }

    "shadow-ban: a soft-deleted user is rejected — no state change, no audit" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false, deletedAt = Instant.now().minus(1, ChronoUnit.DAYS))

        repo.shadowBan(uid, admin, reason = "x", ip = ip, userAgent = ua) shouldBe ShadowBanOutcome.RejectedSoftDeleted

        UserModerationTestSupport.loadUser(dataSource, uid).isShadowBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid) shouldHaveSize 0
    }

    "shadow-ban: an already-shadow-banned user is a no-op that writes no audit row" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false, isShadowBanned = true)

        repo.shadowBan(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe ShadowBanOutcome.NoOpAlreadyShadowBanned

        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_shadow_banned") shouldHaveSize 0
    }

    "shadow-ban: at the cap a shadow-ban is rejected (quota-exceeded), no mutation, no audit" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)
        seedDestructiveActions(dataSource, admin, 20)

        repo.shadowBan(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe ShadowBanOutcome.RateLimited

        UserModerationTestSupport.loadUser(dataSource, uid).isShadowBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_shadow_banned") shouldHaveSize 0
    }

    "shadow-ban: audit row is user_shadow_banned with before/after is_shadow_banned, attributed to the human admin" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false)

        repo.shadowBan(uid, admin, reason = "stealth", ip = ip, userAgent = ua)

        val audit = UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_shadow_banned").single()
        audit.targetType shouldBe "user"
        audit.targetId shouldBe uid.toString()
        audit.adminId shouldBe admin
        audit.adminId shouldNotBe SuspensionUnbanWorker.SYSTEM_ACTOR_ID
        parseShadowBanned(audit.beforeStateJson) shouldBe false
        parseShadowBanned(audit.afterStateJson) shouldBe true
    }

    // ============================ un-shadow-ban ================================

    "shadow-unban: a shadow-banned user is un-shadow-banned (is_shadow_banned=FALSE)" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false, isShadowBanned = true)

        repo.shadowUnban(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe ShadowUnbanOutcome.Applied

        UserModerationTestSupport.loadUser(dataSource, uid).isShadowBanned shouldBe false
    }

    "shadow-unban: a not-shadow-banned user is a no-op that writes no audit row" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false, isShadowBanned = false)

        repo.shadowUnban(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe ShadowUnbanOutcome.NoOpNotShadowBanned

        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_shadow_unbanned") shouldHaveSize 0
    }

    "shadow-unban: a soft-deleted-but-shadow-banned user may be un-shadow-banned; deleted_at unchanged" {
        val admin = seedAdmin()
        val deletedAt = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
        val uid = seedUser(isBanned = false, isShadowBanned = true, deletedAt = deletedAt)

        repo.shadowUnban(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe ShadowUnbanOutcome.Applied

        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isShadowBanned shouldBe false
        row.deletedAt.shouldNotBeNull()
        row.deletedAt.truncatedTo(ChronoUnit.SECONDS) shouldBe deletedAt.truncatedTo(ChronoUnit.SECONDS)
    }

    "shadow-unban: writes NO notification and is NOT counted against the destructive cap (applies at the cap)" {
        val admin = seedAdmin()
        val uid = seedUser(isBanned = false, isShadowBanned = true)
        seedDestructiveActions(dataSource, admin, 20)

        repo.shadowUnban(uid, admin, reason = null, ip = ip, userAgent = ua) shouldBe ShadowUnbanOutcome.Applied

        UserModerationTestSupport.loadUser(dataSource, uid).isShadowBanned shouldBe false
        UserModerationTestSupport.notificationsForUser(dataSource, uid) shouldHaveSize 0
        // The restorative action wrote a user_shadow_unbanned row but it is NOT in the destructive set.
        DestructiveActionRateLimiter(dataSource).countInTrailingHour(admin) shouldBe 20
    }
})

private val parseJson = Json { ignoreUnknownKeys = true }

private fun parseIsBanned(stateJson: String?): Boolean =
    parseJson.parseToJsonElement(stateJson!!).jsonObject["is_banned"]!!.jsonPrimitive.content.toBoolean()

private fun parseShadowBanned(stateJson: String?): Boolean =
    parseJson.parseToJsonElement(stateJson!!).jsonObject["is_shadow_banned"]!!.jsonPrimitive.content.toBoolean()

/** Parse `suspended_until` out of a before/after-state JSON, null when the
 *  JSON value is `null` (or the key is absent). */
private fun parseSuspendedUntil(stateJson: String?): Instant? {
    val element = parseJson.parseToJsonElement(stateJson!!).jsonObject["suspended_until"] ?: return null
    val content = element.jsonPrimitive.content
    return if (content == "null") null else Instant.parse(content)
}

private fun isRoughlySevenDaysOut(instant: Instant): Boolean {
    val now = Instant.now()
    return instant.isAfter(now.plus(6, ChronoUnit.DAYS)) && instant.isBefore(now.plus(8, ChronoUnit.DAYS))
}

/** The four target columns a warning/suspend must (or must not) touch, for the
 *  cap "no mutation" assertions: is_banned / suspended_until / is_shadow_banned /
 *  token_version. */
private data class ModFlags(
    val isBanned: Boolean,
    val suspendedUntil: Instant?,
    val isShadowBanned: Boolean,
    val tokenVersion: Int,
)

private fun readModFlags(
    dataSource: javax.sql.DataSource,
    userId: UUID,
): ModFlags =
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT is_banned, suspended_until, is_shadow_banned, token_version FROM users WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                rs.next()
                ModFlags(
                    isBanned = rs.getBoolean("is_banned"),
                    suspendedUntil = rs.getTimestamp("suspended_until")?.toInstant(),
                    isShadowBanned = rs.getBoolean("is_shadow_banned"),
                    tokenVersion = rs.getInt("token_version"),
                )
            }
        }
    }

/** Seed [n] in-window destructive `user_warned` audit rows for [adminId] so the
 *  acting admin sits at a known point against the ±20/hr cap. */
private fun seedDestructiveActions(
    dataSource: javax.sql.DataSource,
    adminId: UUID,
    n: Int,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, created_at) " +
                "VALUES (?, 'user_warned', 'user', ?, NOW())",
        ).use { ps ->
            repeat(n) {
                ps.setObject(1, adminId)
                ps.setString(2, UUID.randomUUID().toString())
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }
}

/**
 * Install a `NOT VALID CHECK` [constraintName] on [table] enforcing
 * [checkExpr] for the duration of [block], then drop it. NOT VALID skips
 * existing rows but enforces new INSERTs — the same DB-layer fault-injection
 * `SuspensionUnbanWorkerTest` 4.2 uses, since the repository SQL has no
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
