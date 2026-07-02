package id.nearyou.app.admin.referralgrants

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.subscriptiongrace.GraceTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Integration tests for the `admin-referral-manual-grant` surface
 * (`GET/POST /admin/referral-grants`), tasks.md 7.1/7.2/7.4/7.5/7.9/7.10/7.11.
 * DB-backed; tagged `database`. `withAdminApp` binds the DEFAULT
 * `NoOpReferralEntitlementGranter`, so a real POST here exercises the fail-soft
 * (RC-unconfigured) path — dispatch-outcome + stacking math live in
 * [AdminReferralGrantRepositoryTest] with a fake granter.
 *
 * Covers: the auth gate (7.1), lookup by username + UUID + no-match + bare GET
 * (7.2), the fail-soft grant write + audit row (7.4), the required-reason guard
 * (7.5), CSRF + role gating (7.9), and the past-grants viewer — newest-first,
 * date filter, tombstoned-grantee JOIN tolerance, plain-GET escaping (7.10).
 */
@Tags("database")
class AdminReferralGrantsRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() } // 7.11 — CI connection-budget discipline (docs/11 §3.2)

    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        seededUsers.forEach { GraceTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner") = AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }

    fun seedUser(username: String) = GraceTestSupport.seedUser(dataSource, username, "free").also { seededUsers.add(it) }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    // ---- 7.1 auth gate ----

    "7.1 — unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/referral-grants")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "7.1 — unauthenticated POST redirects (302) to /admin/login (no handler logic)" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/referral-grants") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("user_id=${UUID.randomUUID()}&reason=SUP-X")
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    // ---- 7.2 lookup ----

    "7.2 — a bare GET (no q) renders the lookup form + past-grants viewer with 200" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/referral-grants") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "Referral Manual Grant"
            body shouldContain "Past manual grants"
            body shouldContain "name=\"q\""
            // The nav glyph is the real `redeem` path, not the icons.peb
            // missing-name sentinel (a typo'd icon name renders data-icon="missing-…").
            body shouldContain "data-icon=\"redeem\""
            body shouldNotContain "data-icon=\"missing-"
        }
    }

    "7.2 — lookup by username shows status + enables the grant form for that user" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("warga_baru12")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/referral-grants?q=warga_baru12") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "warga_baru12"
            body shouldContain "Grant 7 days Premium"
            body shouldContain "value=\"$u\"" // the resolved user_id hidden field
        }
    }

    "7.2 — lookup by UUID resolves the same user" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("uuid_lookup")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/referral-grants?q=$u") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "uuid_lookup"
            body shouldContain "value=\"$u\""
        }
    }

    "7.2 — lookup of an unknown identifier shows a no-match state with no grant form" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/referral-grants?q=nobody_here_xyz") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "No user matches"
            body shouldNotContain "Grant 7 days Premium"
        }
    }

    // ---- 7.4 fail-soft grant (NoOp granter) + audit ----

    "7.4 — a grant records one audit row and surfaces the dispatch-skipped (RC-unconfigured) outcome" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("grant_me")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/referral-grants") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("user_id=$u&reason=SUP-2026-0142")
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "dispatch skipped"
            val logged =
                AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                    .single { it.actionType == "referral_manual_grant" }
            logged.reason!! shouldContain "SUP-2026-0142"
            logged.targetType shouldBe "user"
        }
    }

    "7.4 — the grant against an unknown user id is surfaced as not-found (no audit row)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val unknown = UUID.randomUUID()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.post("/admin/referral-grants") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("user_id=$unknown&reason=SUP-UNK")
                }.bodyAsText()
            body shouldContain "No user matches"
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).none { it.actionType == "referral_manual_grant" } shouldBe true
        }
    }

    // ---- 7.5 required reason ----

    "7.5 — an empty reason is rejected (400), no dispatch, no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("no_reason")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/referral-grants") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("user_id=$u&reason=+")
                }
            res.status shouldBe HttpStatusCode.BadRequest
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).none { it.actionType == "referral_manual_grant" } shouldBe true
        }
    }

    // ---- 7.9 CSRF + role ----

    "7.9 — a CSRF-token mismatch is 403 + admin_csrf_violation, no grant" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("csrf_target")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/referral-grants") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", "wrong-token")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("user_id=$u&reason=SUP-1")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            val audit = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
            audit.any { it.actionType == "admin_csrf_violation" } shouldBe true
            audit.none { it.actionType == "referral_manual_grant" } shouldBe true
        }
    }

    "7.9 — a read_only admin cannot grant (403, no audit row)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("ro_target")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/referral-grants") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("user_id=$u&reason=SUP-1")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).none { it.actionType == "referral_manual_grant" } shouldBe true
        }
    }

    // ---- 7.10 past-grants viewer ----

    "7.10 — the viewer lists past grants newest-first" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("viewer_user")
        seedGrantAudit(dataSource, admin.id, u, "SUP-OLD", Instant.parse("2026-06-01T10:00:00Z"))
        seedGrantAudit(dataSource, admin.id, u, "SUP-NEW", Instant.parse("2026-06-15T10:00:00Z"))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/referral-grants") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "SUP-NEW"
            body shouldContain "SUP-OLD"
            (body.indexOf("SUP-NEW") < body.indexOf("SUP-OLD")) shouldBe true // newest first
        }
    }

    "7.10 — the date filter narrows the past-grants list" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("date_user")
        seedGrantAudit(dataSource, admin.id, u, "SUP-EARLY", Instant.parse("2026-06-01T10:00:00Z"))
        seedGrantAudit(dataSource, admin.id, u, "SUP-LATE", Instant.parse("2026-06-20T10:00:00Z"))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/referral-grants?date_from=2026-06-10") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "SUP-LATE"
            body shouldNotContain "SUP-EARLY"
        }
    }

    "7.10 — a tombstoned (hard-deleted) grantee row still renders (LEFT JOIN tolerance)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("gone_user")
        seedGrantAudit(dataSource, admin.id, u, "SUP-GONE", Instant.parse("2026-06-10T10:00:00Z"))
        GraceTestSupport.deleteUser(dataSource, u) // hard-delete the grantee
        seededUsers.remove(u)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/referral-grants") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "SUP-GONE" // row still present despite the missing users join
        }
    }

    "7.10 — the viewer renders (escaped) on a plain GET with no HTMX" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = GraceTestSupport.seedUser(dataSource, "<b>evil</b>_user", "free").also { seededUsers.add(it) }
        seedGrantAudit(dataSource, admin.id, u, "SUP-ESC", Instant.parse("2026-06-10T10:00:00Z"))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/referral-grants") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;b&gt;evil&lt;/b&gt;_user"
            body shouldNotContain "<b>evil</b>_user"
            body shouldContain "<nav>" // full page, not the bare fragment
        }
    }

    // ---- 7.10 keyset pagination (cursor round-trip through the rendered next link) ----

    "7.10 — keyset pagination: page 1 holds the 50 newest, the next link round-trips to the remainder with no overlap" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("keyset_user")
        val base = Instant.parse("2026-01-01T00:00:00Z")
        (1..51).forEach { i ->
            seedGrantAudit(dataSource, admin.id, u, "SUP-K%02d".format(i), base.plusSeconds(i * 3600L))
        }
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val page1 = client.get("/admin/referral-grants") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page1 shouldContain "SUP-K51" // newest first
            page1 shouldContain "SUP-K02" // 50th row of page 1
            page1 shouldNotContain "SUP-K01" // beyond the page boundary
            val nextUrl =
                Regex("href=\"([^\"]*cursor=[^\"]*)\"").find(page1)!!.groupValues[1].replace("&amp;", "&")
            val page2 = client.get(nextUrl) { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page2 shouldContain "SUP-K01" // the remainder
            page2 shouldNotContain "SUP-K02" // no overlap with page 1
        }
    }

    "7.10 — the next link preserves active filters (q survives the cursor round-trip)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val a = seedUser("filter_keep_a")
        val b = seedUser("filter_keep_b")
        val base = Instant.parse("2026-01-01T00:00:00Z")
        (1..51).forEach { i ->
            seedGrantAudit(dataSource, admin.id, a, "SUP-F%02d".format(i), base.plusSeconds(i * 3600L))
        }
        seedGrantAudit(dataSource, admin.id, b, "SUP-OTHER", base.plusSeconds(3600L))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val page1 =
                client.get("/admin/referral-grants?q=filter_keep_a") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            val nextUrl =
                Regex("href=\"([^\"]*cursor=[^\"]*)\"").find(page1)!!.groupValues[1].replace("&amp;", "&")
            nextUrl shouldContain "q=filter_keep_a"
            val page2 = client.get(nextUrl) { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page2 shouldContain "SUP-F01"
            page2 shouldNotContain "SUP-OTHER" // the q filter still applies on page 2
        }
    }

    "7.10 — a malformed cursor is ignored (200, first page), never a 500" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/referral-grants?cursor=%25%25not-base64%25%25") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
        }
    }

    // ---- 7.10 q + date_to viewer filters ----

    "7.10 — the q filter narrows the list by grantee username AND by grantee UUID" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val a = seedUser("narrow_a")
        val b = seedUser("narrow_b")
        seedGrantAudit(dataSource, admin.id, a, "SUP-A", Instant.parse("2026-06-10T10:00:00Z"))
        seedGrantAudit(dataSource, admin.id, b, "SUP-B", Instant.parse("2026-06-10T11:00:00Z"))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val byName = client.get("/admin/referral-grants?q=narrow_a") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            byName shouldContain "SUP-A"
            byName shouldNotContain "SUP-B"
            val byId = client.get("/admin/referral-grants?q=$a") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            byId shouldContain "SUP-A"
            byId shouldNotContain "SUP-B"
        }
    }

    "7.10 — date_to keeps rows ON the end date (inclusive) and drops later ones" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("date_to_user")
        seedGrantAudit(dataSource, admin.id, u, "SUP-ON", Instant.parse("2026-06-10T10:00:00Z"))
        seedGrantAudit(dataSource, admin.id, u, "SUP-AFTER", Instant.parse("2026-06-20T10:00:00Z"))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/referral-grants?date_to=2026-06-10") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "SUP-ON" // same-day row kept (inclusive end)
            body shouldNotContain "SUP-AFTER"
        }
    }

    // ---- soft-deleted target (spec: cannot be granted; lookup renders no form) ----

    "lookup of a soft-deleted user renders the DELETED indicator with the grant form disabled" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("tombstoned_look")
        softDeleteUser(dataSource, u)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/referral-grants?q=tombstoned_look") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "DELETED"
            body shouldContain "manual grants are disabled"
            body shouldNotContain "Grant 7 days Premium"
        }
    }

    "a grant POST for a soft-deleted user is rejected — no dispatch, no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("tombstoned_post")
        softDeleteUser(dataSource, u)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.post("/admin/referral-grants") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("user_id=$u&reason=SUP-TOMB")
                }.bodyAsText()
            body shouldContain "deleted (tombstoned)"
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).none { it.actionType == "referral_manual_grant" } shouldBe true
        }
    }

    // ---- 7.12 Failed dispatch is surfaced with retry guidance (route render) ----

    "7.12 — a Failed dispatch renders the failure message with retry guidance" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("fail_render")
        AdminAuthTestSupport.withAdminApp(dataSource, referralEntitlementGranter = FailingGranter) { client ->
            val body =
                client.post("/admin/referral-grants") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("user_id=$u&reason=SUP-FAIL")
                }.bodyAsText()
            body shouldContain "Premium did not activate"
            body shouldContain "rc 500" // the surfaced failure reason
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .count { it.actionType == "referral_manual_grant" } shouldBe 1 // attempt still recorded
        }
    }
})

/** Fake configured granter whose dispatch always fails — drives the route-level
 *  `DispatchFailed` render (7.12) without any real RC call. */
private object FailingGranter : id.nearyou.app.infra.revenuecatapi.ReferralEntitlementGranter {
    override fun isConfigured(): Boolean = true

    override suspend fun grant(request: id.nearyou.app.infra.revenuecatapi.GrantRequest): id.nearyou.app.infra.revenuecatapi.GrantResult =
        id.nearyou.app.infra.revenuecatapi.GrantResult.Failed("rc 500")
}

/** Soft-delete (tombstone) a seeded user — the target shape the grant guard rejects. */
private fun softDeleteUser(
    dataSource: DataSource,
    userId: UUID,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("UPDATE users SET deleted_at = NOW() WHERE id = ?").use { ps ->
            ps.setObject(1, userId)
            ps.executeUpdate()
        }
    }
}

/** Seed one `referral_manual_grant` audit row (grantee + reason + dispatch outcome). */
private fun seedGrantAudit(
    dataSource: DataSource,
    adminId: UUID,
    targetUserId: UUID,
    reason: String,
    createdAt: Instant,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, reason, after_state, created_at)
            VALUES (?, 'referral_manual_grant', 'user', ?, ?, '{"dispatch":"dispatched"}'::jsonb, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, adminId)
            ps.setString(2, targetUserId.toString())
            ps.setString(3, reason)
            ps.setObject(4, Timestamp.from(createdAt))
            ps.executeUpdate()
        }
    }
}
