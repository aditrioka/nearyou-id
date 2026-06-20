package id.nearyou.app.admin.moderation

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.auth.AdminCsrfGate
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Route-level integration tests for `adminUserModeration` — the GET lookup
 * surface, the HTTP auth / CSRF / role gate, the malformed-path-id handling,
 * the `clientIp` + `User-Agent` audit wiring, and the read-reason-after-CSRF
 * body-consume contract (`admin-user-moderation` capability, tasks.md Section
 * 8: 8.7, 8.8, 8.10–8.12, plus the HTTP halves of 8.5 + 8.9).
 *
 * DB-backed; tagged `database`. Uses the `AdminAuthTestSupport` harness for
 * authenticated-session wiring + `UserModerationTestSupport` for user fixtures
 * + target-scoped audit/notification reads. The repository-internal transaction
 * semantics (atomicity, before/after JSON, notification shape) are covered by
 * [UserModerationRepositoryTest].
 */
@Tags("database")
class AdminUserModerationRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        UserModerationTestSupport.cleanupUser(dataSource, *seededUsers.toTypedArray())
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner"): AdminAuthTestSupport.SeededAdmin =
        AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins += it.id }

    fun seedUser(
        isBanned: Boolean = false,
        suspendedUntil: Instant? = null,
        deletedAt: Instant? = null,
        username: String? = null,
        isShadowBanned: Boolean = false,
    ): UUID =
        UserModerationTestSupport.seedUser(dataSource, isBanned, suspendedUntil, deletedAt, username, isShadowBanned)
            .also { seededUsers += it }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun formBody(vararg pairs: Pair<String, String>): String = pairs.toList().formUrlEncode()

    // ============================ 8.8 GET surface ==============================

    "8.8a authenticated GET with no q renders the lookup form (full page)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "name=\"q\""
            body shouldContain "<header>" // full page extends layout
        }
    }

    "8.8b resolving q shows the user's state + suspend/unban controls" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/users") {
                    header(HttpHeaders.Cookie, cookie(token))
                    parameter("q", uid.toString())
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "/admin/users/$uid/suspend"
            body shouldContain "/admin/users/$uid/unban"
        }
    }

    "8.8c unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "8.8d HX-Request returns only the result fragment (no full-page wrapper)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/users") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                    parameter("q", uid.toString())
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "id=\"user-moderation-result\""
            body shouldNotContain "<html"
            body shouldNotContain "<header>"
        }
    }

    // ============================ 8.9 HTTP lookup ==============================

    "8.9a lookup by exact username renders the user's state" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false, username = "budi_jakarta")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/users") {
                    header(HttpHeaders.Cookie, cookie(token))
                    parameter("q", "budi_jakarta")
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "/admin/users/$uid/suspend"
        }
    }

    "8.9b case-sensitive username miss renders the empty state" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser(isBanned = false, username = "budi_jakarta")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/users") {
                    header(HttpHeaders.Cookie, cookie(token))
                    parameter("q", "BUDI_JAKARTA")
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No matching user"
        }
    }

    "8.9c non-resolving query renders an empty state (200, not 404)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/users") {
                    header(HttpHeaders.Cookie, cookie(token))
                    parameter("q", "does-not-exist")
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No matching user"
        }
    }

    "8.9d SQL-metacharacter query is treated as a literal; users table survives" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val sentinel = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/users") {
                    header(HttpHeaders.Cookie, cookie(token))
                    parameter("q", "'; DROP TABLE users;--")
                }
            res.status shouldBe HttpStatusCode.OK
        }
        UserModerationTestSupport.userExists(dataSource, sentinel) shouldBe true
    }

    // ============================ 8.10 malformed path id =======================

    "8.10a POST suspend with a non-UUID id is 400 (not 500), no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/not-a-uuid/suspend") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldNotBe HttpStatusCode.InternalServerError
            res.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "8.10b POST unban with a non-UUID id is 400 (not 500)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/not-a-uuid/unban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldNotBe HttpStatusCode.InternalServerError
            res.status shouldBe HttpStatusCode.BadRequest
        }
    }

    // ============================ 8.11 role gate ===============================

    "8.11a read_only suspend → 403, unchanged, no user_suspended row" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/suspend") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended") shouldHaveSize 0
    }

    "8.11b read_only unban → 403, still banned, no user_unbanned row" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = true, suspendedUntil = Instant.now().plus(3, ChronoUnit.DAYS))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/unban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe true
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_unbanned") shouldHaveSize 0
    }

    "8.11c moderator suspend is authorized (303) and applies the suspension" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/suspend") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldNotBe HttpStatusCode.Forbidden
            res.status shouldBe HttpStatusCode.SeeOther
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe true
    }

    "8.11d moderator unban of a time-bound suspension is authorized (cleared)" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = true, suspendedUntil = Instant.now().plus(4, ChronoUnit.DAYS))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/unban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.SeeOther
        }
        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe false
        row.suspendedUntil shouldBe null
    }

    "8.11e moderator unban of a PERMANENT ban → 403, still banned, no user_unbanned row" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = true, suspendedUntil = null)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/unban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe true
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_unbanned") shouldHaveSize 0
    }

    "8.11f admin unban of a permanent ban is authorized (cleared)" {
        val admin = seedAdmin(role = "admin")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = true, suspendedUntil = null)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/unban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.SeeOther
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
    }

    // ============================ 8.12 CSRF ====================================

    "8.12a suspend without a CSRF token → 403, no state, no user_suspended row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/suspend") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended") shouldHaveSize 0
    }

    "8.12b suspend with a wrong CSRF token → 403" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/suspend") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, "wrong-token-value")
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
    }

    "8.12c suspend with a valid CSRF token proceeds" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/suspend") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.SeeOther
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe true
    }

    "8.12d unban without a CSRF token → 403, still banned, no user_unbanned row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = true, suspendedUntil = Instant.now().plus(2, ChronoUnit.DAYS))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/unban") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe true
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_unbanned") shouldHaveSize 0
    }

    "8.12e unban with a valid CSRF token proceeds" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = true, suspendedUntil = Instant.now().plus(2, ChronoUnit.DAYS))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/unban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.SeeOther
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
    }

    // ===================== 8.5 (HTTP: clientIp + UA + admin_id) =================

    "8.5-route suspend records CF-Connecting-IP as the audit ip + the session admin_id + the UA header" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/users/$uid/suspend") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                header("CF-Connecting-IP", "1.2.3.4")
                header(HttpHeaders.UserAgent, "moderation-curl/8.0")
            }
        }
        val audit = UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended").single()
        audit.ip shouldBe "1.2.3.4" // clientIp from CF-Connecting-IP, not the edge hop
        audit.userAgent shouldBe "moderation-curl/8.0"
        audit.adminId shouldBe admin.id
    }

    // ===================== 8.7 (reason read after CSRF body-consume) ============

    "8.7a suspend with BOTH _csrf and reason in one urlencoded body records the reason" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/suspend") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "spam and harassment"))
                }
            res.status shouldBe HttpStatusCode.SeeOther // CSRF passed via the _csrf field
        }
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended").single().reason shouldBe "spam and harassment"
    }

    "8.7b suspend with no reason field records a NULL reason" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/users/$uid/suspend") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
            }
        }
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_suspended").single().reason shouldBe null
    }

    "8.1-route client-supplied duration_days is ignored (server-fixed 7 days)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/users/$uid/suspend") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "duration_days" to "3650"))
            }
        }
        val suspendedUntil = UserModerationTestSupport.loadUser(dataSource, uid).suspendedUntil!!
        val now = Instant.now()
        (suspendedUntil.isAfter(now.plus(6, ChronoUnit.DAYS)) && suspendedUntil.isBefore(now.plus(8, ChronoUnit.DAYS))) shouldBe true
    }

    // =============== redirect target (review hardening: TC1 + TC2) =============

    "suspend success (no-JS) → 303 See Other with Location to the lookup view" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/suspend") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.SeeOther
            res.headers[HttpHeaders.Location] shouldBe "/admin/users?q=$uid"
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe true
    }

    "HTMX suspend success → 200 with HX-Redirect to the lookup view" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/suspend") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                }
            res.status shouldBe HttpStatusCode.OK
            res.headers["HX-Redirect"] shouldBe "/admin/users?q=$uid"
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe true
    }

    // ============ 7.1/7.3/7.4 profile GET (admin-user-management) ==============

    "7.1 authenticated profile GET for an existing user → 200 with identity + state; serving writes no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false, username = "budi_profile")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "budi_profile"
            body shouldContain uid.toString()
            body shouldContain "<header>" // full page extends the layout shell
        }
        // Read-only route: serving the page writes no admin_actions_log row.
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id) shouldHaveSize 0
    }

    "7.1 unauthenticated profile GET → 302 /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/${UUID.randomUUID()}")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "7.1 a display_name carrying HTML markup is escaped, not rendered as live tags" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUserWithDisplayName(dataSource, "<script>alert(1)</script>").also { seededUsers += it }
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldNotContain "<script>alert(1)</script>"
            body shouldContain "&lt;script&gt;"
        }
    }

    "7.3 the profile renders suspend/unban/warn controls each with _csrf AND a quota chip = the acting admin's seeded count" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedWarns(dataSource, admin.id, 14)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "/admin/users/$uid/suspend"
            body shouldContain "/admin/users/$uid/unban"
            body shouldContain "/admin/users/$uid/warn"
            body shouldContain "name=\"_csrf\""
            body shouldContain "14/20"
        }
        // The chip read writes nothing — only the 14 seeded rows remain.
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id) shouldHaveSize 14
    }

    "7.4 non-UUID profile id → 4xx (not 500)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/not-a-uuid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldNotBe HttpStatusCode.InternalServerError
            res.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "7.4 SQL-metacharacter profile id → not 500, users table survives" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val sentinel = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/%27%3B%20DROP%20TABLE%20users%3B--") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldNotBe HttpStatusCode.InternalServerError
        }
        UserModerationTestSupport.userExists(dataSource, sentinel) shouldBe true
    }

    "7.4 unknown well-formed UUID → empty-state 200 (not 404)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/${UUID.randomUUID()}") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No matching user"
        }
    }

    "7.5 a resolved lookup result deep-links to the profile page" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/users") {
                    header(HttpHeaders.Cookie, cookie(token))
                    parameter("q", uid.toString())
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "href=\"/admin/users/$uid\""
        }
    }

    // ============ 7.9 warn route gating (admin-user-moderation) ================

    "7.9 warn happy path → 303 + one user_warned audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/warn") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.SeeOther
        }
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_warned") shouldHaveSize 1
    }

    "7.9 warn unauthenticated → 302, no user_warned row" {
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.post("/admin/users/$uid/warn")
            res.status shouldBe HttpStatusCode.Found
        }
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_warned") shouldHaveSize 0
    }

    "7.9 warn missing CSRF → 403, an admin_csrf_violation row IS written, no user_warned row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.post("/admin/users/$uid/warn") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).any { it.actionType == "admin_csrf_violation" } shouldBe true
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_warned") shouldHaveSize 0
    }

    "7.9 CSRF is checked BEFORE role: read_only + bad CSRF → CSRF rejection (violation row written)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/warn") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, "wrong-token")
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).any { it.actionType == "admin_csrf_violation" } shouldBe true
    }

    "7.9 read_only + valid CSRF → role-rejected (403), no user_warned row, no CSRF violation" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/warn") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_warned") shouldHaveSize 0
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).any { it.actionType == "admin_csrf_violation" } shouldBe false
    }

    "7.9 malformed id is parsed AFTER the role gate: read_only + valid CSRF + bad id → role rejection (403), not a 400" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/not-a-uuid/warn") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
    }

    // ============ 7.2 merged history view (admin-user-management) ==============

    "7.2 a prior suspend action appears in the history view" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        insertUserAudit(dataSource, admin.id, uid, "user_suspended", ageMinutes = 10)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "user_suspended"
        }
    }

    "7.2 a username change appears in the history view" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        insertUsernameHistory(dataSource, uid, "budi", "budi_jakarta", ageMinutes = 10)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "budi → budi_jakarta"
        }
    }

    "7.2 a user with no history renders the empty-state, not an error" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No admin actions or username changes"
        }
    }

    "7.2 the two sources are interleaved into ONE combined newest-first order" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        // oldest: a suspend; middle: a username change; newest: a warn — across
        // BOTH sources, so a correct merge interleaves them (not source-grouped).
        insertUserAudit(dataSource, admin.id, uid, "user_suspended", reason = "AAA-oldest", ageMinutes = 30)
        insertUsernameHistory(dataSource, uid, "uX", "uY-MIDDLE", ageMinutes = 20)
        insertUserAudit(dataSource, admin.id, uid, "user_warned", reason = "ZZZ-newest", ageMinutes = 10)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            val newest = body.indexOf("ZZZ-newest")
            val middle = body.indexOf("uY-MIDDLE")
            val oldest = body.indexOf("AAA-oldest")
            (newest in 1 until middle && middle < oldest) shouldBe true
        }
    }

    // ============ ban route: role gate (owner/admin), CSRF, malformed id =======

    "ban: admin + valid CSRF → 303 and the user is permanently banned" {
        val admin = seedAdmin(role = "admin")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/ban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.SeeOther
        }
        val row = UserModerationTestSupport.loadUser(dataSource, uid)
        row.isBanned shouldBe true
        row.suspendedUntil shouldBe null
    }

    "ban: moderator + valid CSRF → 403 (owner/admin only), not banned, no user_banned row" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/ban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_banned") shouldHaveSize 0
    }

    "ban: missing CSRF → 403, not banned, no user_banned row" {
        val admin = seedAdmin(role = "admin")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.post("/admin/users/$uid/ban") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_banned") shouldHaveSize 0
    }

    "ban: non-UUID id (owner + valid CSRF) → 400 (not 500)" {
        val admin = seedAdmin(role = "owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/not-a-uuid/ban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldNotBe HttpStatusCode.InternalServerError
            res.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "ban: CSRF is checked BEFORE the role gate (read_only + wrong CSRF → CSRF violation row)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/ban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, "wrong-token")
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).any { it.actionType == "admin_csrf_violation" } shouldBe true
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_banned") shouldHaveSize 0
    }

    "ban: role gate runs BEFORE the id-parse (read_only + valid CSRF + bad id → 403, not 400)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/not-a-uuid/ban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
    }

    "ban: at the cap → quota-exceeded (200 with message), not banned, no user_banned row" {
        val admin = seedAdmin(role = "admin")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        seedWarns(dataSource, admin.id, 20)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/ban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldNotBe HttpStatusCode.InternalServerError
            res.bodyAsText() shouldContain "quota exceeded"
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_banned") shouldHaveSize 0
    }

    // ============ shadow-ban / shadow-unban routes (all write roles) ===========

    "shadow-ban: moderator + valid CSRF → 303 and the user is shadow-banned" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/shadow-ban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.SeeOther
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isShadowBanned shouldBe true
    }

    "shadow-ban: read_only + valid CSRF → 403, not shadow-banned, no audit row" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/shadow-ban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isShadowBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_shadow_banned") shouldHaveSize 0
    }

    "shadow-ban: wrong CSRF token → 403, not shadow-banned, no audit row" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/shadow-ban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, "wrong-token-value")
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isShadowBanned shouldBe false
        UserModerationTestSupport.auditRowsForTarget(dataSource, uid, "user_shadow_banned") shouldHaveSize 0
    }

    "shadow-ban: non-UUID id (owner + valid CSRF) → 400 (not 500)" {
        val admin = seedAdmin(role = "owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/not-a-uuid/shadow-ban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldNotBe HttpStatusCode.InternalServerError
            res.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "shadow-unban: non-UUID id (moderator + valid CSRF) → 400 (not 500)" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/not-a-uuid/shadow-unban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldNotBe HttpStatusCode.InternalServerError
            res.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "shadow-unban: moderator + valid CSRF → 303 and the shadow ban is lifted" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false, isShadowBanned = true)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/users/$uid/shadow-unban") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.SeeOther
        }
        UserModerationTestSupport.loadUser(dataSource, uid).isShadowBanned shouldBe false
    }

    // ============ profile render: new controls, role-visibility, quota chip ====

    "profile (owner) of an active user renders the ban + shadow-ban controls" {
        val admin = seedAdmin(role = "owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false, isShadowBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "/admin/users/$uid/ban"
            body shouldContain "/admin/users/$uid/shadow-ban"
        }
    }

    "profile (moderator) of an active user hides the ban control but keeps shadow-ban" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false, isShadowBanned = false)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldNotContain "/admin/users/$uid/ban\""
            body shouldContain "/admin/users/$uid/shadow-ban"
        }
    }

    "profile of a shadow-banned user shows the un-shadow-ban control (shadow-ban absent)" {
        val admin = seedAdmin(role = "owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false, isShadowBanned = true)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "/admin/users/$uid/shadow-unban"
            body shouldNotContain "/admin/users/$uid/shadow-ban\""
        }
    }

    "profile quota chip reflects seeded user_banned + user_shadow_banned rows" {
        val admin = seedAdmin(role = "owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser(isBanned = false)
        insertUserAudit(dataSource, admin.id, UUID.randomUUID(), "user_banned")
        insertUserAudit(dataSource, admin.id, UUID.randomUUID(), "user_banned")
        insertUserAudit(dataSource, admin.id, UUID.randomUUID(), "user_shadow_banned")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/users/$uid") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "3/20"
        }
    }
})

/** Insert one `admin_actions_log` row targeting [targetUserId], aged [ageMinutes]. */
private fun insertUserAudit(
    dataSource: javax.sql.DataSource,
    adminId: UUID,
    targetUserId: UUID,
    actionType: String,
    reason: String? = null,
    ageMinutes: Int = 1,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, reason, after_state, created_at) " +
                "VALUES (?, ?, 'user', ?, ?, '{}'::jsonb, NOW() - (? * INTERVAL '1 minute'))",
        ).use { ps ->
            ps.setObject(1, adminId)
            ps.setString(2, actionType)
            ps.setString(3, targetUserId.toString())
            ps.setString(4, reason)
            ps.setInt(5, ageMinutes)
            ps.executeUpdate()
        }
    }
}

/** Insert one `username_history` row for [userId], aged [ageMinutes]. */
private fun insertUsernameHistory(
    dataSource: javax.sql.DataSource,
    userId: UUID,
    old: String,
    new: String,
    ageMinutes: Int = 1,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO username_history (user_id, old_username, new_username, changed_at, released_at) " +
                "VALUES (?, ?, ?, NOW() - (? * INTERVAL '1 minute'), NOW())",
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, old)
            ps.setString(3, new)
            ps.setInt(4, ageMinutes)
            ps.executeUpdate()
        }
    }
}

/** Seed [n] in-window destructive `user_warned` audit rows for [adminId]. */
private fun seedWarns(
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

/** Seed a `users` row with a caller-chosen [displayName] (for the autoescape
 *  assertion); other columns mirror `UserModerationTestSupport.seedUser`. */
private fun seedUserWithDisplayName(
    dataSource: javax.sql.DataSource,
    displayName: String,
): UUID {
    val id = UUID.randomUUID()
    val short = id.toString().replace("-", "").take(8)
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix) " +
                "VALUES (?, ?, ?, DATE '1990-01-01', ?)",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "mod_$short")
            ps.setString(3, displayName)
            ps.setString(4, "m${short.take(7)}")
            ps.executeUpdate()
        }
    }
    return id
}
