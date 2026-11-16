package id.nearyou.app.admin.privacyflips

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for `POST /admin/privacy-flips/{user_id}/resolve` + the
 * resolve control on the monitor (`admin-privacy-flip-resolve` tasks.md
 * Section 5). DB-backed; tagged `database`. The app is wired with a FIXED clock
 * (`eval`) so OVERDUE eligibility — the monitor's classification — is
 * deterministic, including the boundary row.
 *
 * Covers: the owner/admin-only control on OVERDUE rows, the subscription-aware
 * branch (free → applied; premium_active / premium_billing_retry → cleared),
 * the audit snapshot, soft-deleted targets, the CSRF → role → id → reason gate
 * order, OVERDUE-only eligibility + idempotency, and the distinct 10/hour cap.
 */
@Tags("database")
class AdminPrivacyFlipResolveRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val eval = Instant.parse("2026-06-13T12:00:00Z")
    val overdue = eval.minusSeconds(25 * 3600)
    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    beforeEach { PrivacyFlipsTestSupport.deleteAllPendingFlips(dataSource) }
    afterEach {
        seededUsers.forEach { PrivacyFlipsTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner") = AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }

    fun seedUser(
        username: String,
        scheduledAt: Instant? = overdue,
        subscriptionStatus: String = "free",
        deletedAt: Instant? = null,
    ): UUID =
        PrivacyFlipsTestSupport.seedUser(
            dataSource,
            username,
            scheduledAt = scheduledAt,
            deletedAt = deletedAt,
            subscriptionStatus = subscriptionStatus,
        ).also { seededUsers.add(it) }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun state(id: UUID) = PrivacyFlipsTestSupport.flipState(dataSource, id)

    fun resolves(id: UUID) = PrivacyFlipsTestSupport.countResolvesFor(dataSource, id)

    suspend fun HttpClient.resolve(
        token: String,
        userId: Any,
        reason: String = "SUP-277 stuck row",
        csrf: String = AdminAuthTestSupport.csrfFor(token),
        htmx: Boolean = true,
    ): HttpResponse =
        post("/admin/privacy-flips/$userId/resolve") {
            header(HttpHeaders.Cookie, cookie(token))
            header("X-CSRF-Token", csrf)
            if (htmx) header("HX-Request", "true")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("reason=${URLEncoder.encode(reason, Charsets.UTF_8)}")
        }

    // ---- 5.2: control visibility ----

    "5.2 — owner sees the resolve control + quota chip on an OVERDUE row, none on an IN_WINDOW row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val stuck = seedUser("flip_stuck_ctl")
        val inWindow = seedUser("flip_window_ctl", scheduledAt = eval.plusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val body = client.get("/admin/privacy-flips") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "/admin/privacy-flips/$stuck/resolve"
            body shouldNotContain "/admin/privacy-flips/$inWindow/resolve"
            body shouldContain "Resolve actions this hour: <b>0/10</b>"
            body shouldContain "name=\"_csrf\" value=\"${AdminAuthTestSupport.csrfFor(token)}\""
            body shouldNotContain "READ-ONLY"
        }
    }

    "5.2 — read_only and moderator sessions see no resolve control and no quota chip" {
        seedUser("flip_stuck_noctl")
        for (role in listOf("read_only", "moderator")) {
            val admin = seedAdmin(role)
            val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
            AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
                val res = client.get("/admin/privacy-flips") { header(HttpHeaders.Cookie, cookie(token)) }
                res.status shouldBe HttpStatusCode.OK
                val body = res.bodyAsText()
                body shouldContain "flip_stuck_noctl"
                body shouldNotContain "/resolve"
                body shouldNotContain "Resolve actions this hour"
            }
        }
    }

    // ---- 5.3 / 5.4 / 5.5 / 5.6: the subscription-aware branch ----

    "5.3 — HTMX resolve of a free OVERDUE user applies the flip, logs one row, re-renders the fragment" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_apply_me")

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val res = client.resolve(token, u, reason = "user reported stuck private profile")
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "id=\"privacy-flips-table\""
            body shouldNotContain "<header>" // fragment, not the full page
            body shouldContain "Flip applied"
            body shouldNotContain "flip_apply_me" // no pending flip → off the list
            body shouldContain "Resolve actions this hour: <b>1/10</b>"

            state(u).isPrivate shouldBe false
            state(u).scheduledAt.shouldBeNull()
            resolves(u) shouldBe 1
            val logged =
                AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                    .first { it.actionType == "privacy_flip_resolved" }
            logged.targetType shouldBe "user"
            logged.reason shouldBe "user reported stuck private profile"
        }
    }

    "5.3 — a no-JS resolve is a 303 PRG redirect back to the monitor" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_apply_nojs")

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val res = client.resolve(token, u, htmx = false)
            res.status shouldBe HttpStatusCode.SeeOther
            res.headers[HttpHeaders.Location] shouldBe "/admin/privacy-flips"
            resolves(u) shouldBe 1
        }
    }

    "5.4 — a premium_active or premium_billing_retry OVERDUE user only has the schedule cleared" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val active = seedUser("flip_premium_active", subscriptionStatus = "premium_active")
        val retry = seedUser("flip_premium_retry", subscriptionStatus = "premium_billing_retry")

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            for (u in listOf(active, retry)) {
                client.resolve(token, u).bodyAsText() shouldContain "Schedule cleared"
                state(u).isPrivate shouldBe true // a paying user's profile is not made public
                state(u).scheduledAt.shouldBeNull()
                resolves(u) shouldBe 1
            }
            val afterStates =
                AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                    .filter { it.actionType == "privacy_flip_resolved" }
                    .map { Json.parseToJsonElement(it.afterState!!).jsonObject }
            afterStates.size shouldBe 2
            afterStates.all { it["resolution"]!!.jsonPrimitive.content == "cleared" } shouldBe true
            afterStates.all { it["private_profile_opt_in"]!!.jsonPrimitive.boolean } shouldBe true
        }
    }

    "5.5 — the audit row snapshots the locked before-state and the applied after-state" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_snapshot")

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(token, u)
            val row =
                AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                    .first { it.actionType == "privacy_flip_resolved" }
            val before = Json.parseToJsonElement(row.beforeState!!).jsonObject
            before["private_profile_opt_in"]!!.jsonPrimitive.boolean shouldBe true
            Instant.parse(before["privacy_flip_scheduled_at"]!!.jsonPrimitive.content) shouldBe overdue
            before["subscription_status"]!!.jsonPrimitive.content shouldBe "free"

            val after = Json.parseToJsonElement(row.afterState!!).jsonObject
            after["private_profile_opt_in"]!!.jsonPrimitive.boolean shouldBe false
            after["privacy_flip_scheduled_at"] shouldBe JsonNull
            after["subscription_status"]!!.jsonPrimitive.content shouldBe "free"
            after["resolution"]!!.jsonPrimitive.content shouldBe "applied"
        }
    }

    "5.6 — a soft-deleted free OVERDUE user is resolvable (flip applied + audit row)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_soft_deleted", deletedAt = eval.minusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(token, u).bodyAsText() shouldContain "Flip applied"
            state(u).isPrivate shouldBe false
            state(u).scheduledAt.shouldBeNull()
            resolves(u) shouldBe 1
        }
    }

    // ---- 5.7: gate order ----

    "5.7 — an unauthenticated resolve redirects to login and writes nothing" {
        val u = seedUser("flip_unauth")
        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val res =
                client.post("/admin/privacy-flips/$u/resolve") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=x")
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
            state(u).scheduledAt.shouldNotBeNull()
            resolves(u) shouldBe 0
        }
    }

    "5.7 — a CSRF mismatch is 403 + audited, no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_bad_csrf")

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(token, u, csrf = "wrong-token").status shouldBe HttpStatusCode.Forbidden
            state(u).scheduledAt.shouldNotBeNull()
            resolves(u) shouldBe 0
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .any { it.actionType == "admin_csrf_violation" } shouldBe true
        }
    }

    "5.7 — CSRF is checked before the role gate (read_only + bad CSRF → CSRF violation)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_csrf_pre_role")

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(token, u, csrf = "wrong-token").status shouldBe HttpStatusCode.Forbidden
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .any { it.actionType == "admin_csrf_violation" } shouldBe true
            state(u).scheduledAt.shouldNotBeNull()
        }
    }

    "5.7 — moderator and read_only admins are rejected by the role gate (403, no write)" {
        val u = seedUser("flip_role_gate")
        for (role in listOf("moderator", "read_only")) {
            val admin = seedAdmin(role)
            val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
            AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
                client.resolve(token, u).status shouldBe HttpStatusCode.Forbidden
            }
        }
        state(u).isPrivate shouldBe true
        state(u).scheduledAt.shouldNotBeNull()
        resolves(u) shouldBe 0
    }

    // ---- 5.8: reason + id validation ----

    "5.8 — a blank reason is 400 with no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_blank_reason")

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(token, u, reason = "   ").status shouldBe HttpStatusCode.BadRequest
            state(u).scheduledAt.shouldNotBeNull()
            resolves(u) shouldBe 0
        }
    }

    "5.8 — a malformed user id is 400 with no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(token, "not-a-uuid").status shouldBe HttpStatusCode.BadRequest
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .none { it.actionType == "privacy_flip_resolved" } shouldBe true
        }
    }

    "5.8 — an over-long reason is bounded to 200 chars, not errored" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_long_reason")

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(token, u, reason = "x".repeat(300)).status shouldBe HttpStatusCode.OK
            val stored =
                AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                    .first { it.actionType == "privacy_flip_resolved" }
                    .reason!!
            stored.length shouldBe 200
        }
    }

    // ---- 5.9: OVERDUE-only eligibility + idempotency ----

    "5.9 — an IN_WINDOW target is not resolvable (200 message, no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_in_window", scheduledAt = eval.plusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val res = client.resolve(token, u)
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "Not resolvable"
            state(u).isPrivate shouldBe true
            state(u).scheduledAt shouldBe eval.plusSeconds(3600)
            resolves(u) shouldBe 0
        }
    }

    "5.9 — a flip due exactly at the evaluation instant is resolvable (OVERDUE boundary)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_boundary_resolve", scheduledAt = eval)

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(token, u).bodyAsText() shouldContain "Flip applied"
            resolves(u) shouldBe 1
        }
    }

    "5.9 — resolving an already-resolved user is a no-op" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_twice")

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(token, u).bodyAsText() shouldContain "Flip applied"
            val second = client.resolve(token, u)
            second.status shouldBe HttpStatusCode.OK
            second.bodyAsText() shouldContain "Not resolvable"
            resolves(u) shouldBe 1
        }
    }

    "5.9 — an admin at the cap resolving an IN_WINDOW row gets \"not resolvable\", not the quota" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_cap_window", scheduledAt = eval.plusSeconds(3600))
        repeat(10) { PrivacyFlipsTestSupport.seedAuditRow(dataSource, admin.id, "privacy_flip_resolved") }

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val body = client.resolve(token, u).bodyAsText()
            body shouldContain "Not resolvable"
            body shouldNotContain "quota exceeded"
            resolves(u) shouldBe 0
        }
    }

    "5.9 — an unknown user id is a 200 no-op" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val res = client.resolve(token, UUID.randomUUID())
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "Not resolvable"
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .none { it.actionType == "privacy_flip_resolved" } shouldBe true
        }
    }

    // ---- 5.10: the distinct rate-limit cap ----

    "5.10 — the 11th resolve in an hour is rejected (quota), no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_capped")
        repeat(10) { PrivacyFlipsTestSupport.seedAuditRow(dataSource, admin.id, "privacy_flip_resolved") }

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val res = client.resolve(token, u)
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "quota exceeded"
            state(u).isPrivate shouldBe true
            state(u).scheduledAt.shouldNotBeNull()
            resolves(u) shouldBe 0
        }
    }

    "5.10 — an exhausted destructive budget does not block a resolve (independent counters)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("flip_indep")
        repeat(20) { PrivacyFlipsTestSupport.seedAuditRow(dataSource, admin.id, "user_suspended") }

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(token, u).bodyAsText() shouldContain "Flip applied"
            resolves(u) shouldBe 1
        }
    }

    "5.10 — the cap is per-admin (admin B unaffected by admin A's exhausted quota)" {
        val adminA = seedAdmin()
        val adminB = seedAdmin()
        val tokenB = AdminAuthTestSupport.seedSession(dataSource, adminB.id)
        val u = seedUser("flip_per_admin")
        repeat(10) { PrivacyFlipsTestSupport.seedAuditRow(dataSource, adminA.id, "privacy_flip_resolved") }

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.resolve(tokenB, u).bodyAsText() shouldContain "Flip applied"
            resolves(u) shouldBe 1
        }
    }
})
