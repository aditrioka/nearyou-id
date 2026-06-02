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
    ): UUID = UserModerationTestSupport.seedUser(dataSource, isBanned, suspendedUntil, deletedAt, username).also { seededUsers += it }

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
})
