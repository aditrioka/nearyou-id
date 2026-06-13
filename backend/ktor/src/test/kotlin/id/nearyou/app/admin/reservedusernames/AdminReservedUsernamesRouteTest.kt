package id.nearyou.app.admin.reservedusernames

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
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
import io.ktor.http.formUrlEncode
import java.util.UUID

/**
 * Route-level integration tests for `adminReservedUsernames` — the HTTP auth /
 * CSRF / write-role gate + gate ORDER, input-validation 400s, the in-band /
 * dual-mode rendering, output escaping, and the bulk size guard
 * (`admin-reserved-usernames-editor` tasks.md Section 5: 5.4/5.5/5.8/5.9/5.13/
 * 5.27-5.31/5.35). The repository-internal transaction semantics are covered by
 * [ReservedUsernamesRepositoryTest]. DB-backed; tagged `database`.
 */
@Tags("database")
class AdminReservedUsernamesRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededAdmins = mutableListOf<UUID>()
    val createdUsernames = mutableListOf<String>()
    afterEach {
        createdUsernames.forEach { ReservedUsernamesTestSupport.deleteAdminAdded(dataSource, it) }
        createdUsernames.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner"): AdminAuthTestSupport.SeededAdmin =
        AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins += it.id }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun form(vararg pairs: Pair<String, String>): String = pairs.toList().formUrlEncode()

    fun track(username: String): String = username.also { createdUsernames += it }

    fun csrfViolated(adminId: UUID): Boolean =
        AdminAuthTestSupport.latestAuditRows(dataSource, adminId).any { it.actionType == "admin_csrf_violation" }

    // ---- GET (read) --------------------------------------------------------

    "5.5 — unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/reserved-usernames")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "5.4 — GET renders the full page and HTML-escapes the reason" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ReservedUsernamesTestSupport.seed(dataSource, track("ruaescape"), reason = "<script>alert(1)</script>")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/reserved-usernames?q=ruaescape") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "<header>" // full page extends layout
            body shouldContain "ruaescape"
            body shouldContain "&lt;script&gt;"
            body shouldNotContain "<script>alert(1)</script>"
        }
    }

    "5.4 — any authenticated role (read_only) may view the editor" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/reserved-usernames") { header(HttpHeaders.Cookie, cookie(token)) }
                .status shouldBe HttpStatusCode.OK
        }
    }

    // ---- Add (write) -------------------------------------------------------

    "add valid (no-JS) → 303 to the list + row created" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reserved-usernames") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "username" to track("ruahttpadd"), "reason" to "via http"))
                }
            res.status shouldBe HttpStatusCode.SeeOther
            res.headers[HttpHeaders.Location] shouldBe "/admin/reserved-usernames"
        }
        ReservedUsernamesTestSupport.exists(dataSource, "ruahttpadd") shouldBe true
    }

    "add valid (HX-Request) → 200 table fragment" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reserved-usernames") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "username" to track("ruahtmladd"), "reason" to "via htmx"))
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "id=\"reserved-usernames-table\""
            body shouldNotContain "<header>" // fragment only
        }
    }

    "5.8 — add blank reason → 400, no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reserved-usernames") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "username" to "ruablank", "reason" to "  "))
                }
            res.status shouldBe HttpStatusCode.BadRequest
        }
        ReservedUsernamesTestSupport.exists(dataSource, "ruablank") shouldBe false
    }

    "5.9 — add out-of-charset username → 400, no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reserved-usernames") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "username" to "bad-user", "reason" to "r"))
                }
            res.status shouldBe HttpStatusCode.BadRequest
        }
        ReservedUsernamesTestSupport.exists(dataSource, "bad-user") shouldBe false
    }

    "5.18 — edit blank reason → 400, no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ReservedUsernamesTestSupport.seed(dataSource, track("rua_editblank"), reason = "keep me")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reserved-usernames/rua_editblank/edit-reason") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "   "))
                }
            res.status shouldBe HttpStatusCode.BadRequest
        }
        ReservedUsernamesTestSupport.reasonOf(dataSource, "rua_editblank") shouldBe "keep me"
    }

    "5.31 — add 65-char reason → 400; 64-char reason → 303 success" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/reserved-usernames") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "username" to "rua65", "reason" to "x".repeat(65)))
            }.status shouldBe HttpStatusCode.BadRequest

            client.post("/admin/reserved-usernames") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "username" to track("rua64"), "reason" to "x".repeat(64)))
            }.status shouldBe HttpStatusCode.SeeOther
        }
        ReservedUsernamesTestSupport.exists(dataSource, "rua65") shouldBe false
        ReservedUsernamesTestSupport.exists(dataSource, "rua64") shouldBe true
    }

    "5.30 — add a case-variant of an existing username → normalized → in-band 'already reserved'" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // "Admin" normalizes to the existing seed "admin"
            val res =
                client.post("/admin/reserved-usernames") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "username" to "Admin", "reason" to "dup"))
                }
            res.status shouldBe HttpStatusCode.OK // in-band, not a redirect / not a 4xx
            res.bodyAsText() shouldContain "already reserved"
        }
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).any { it.actionType == "reserved_username_added" } shouldBe false
    }

    // ---- CSRF / role / gate order ------------------------------------------

    "5.27 — add without a CSRF token → 403 + admin_csrf_violation, no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reserved-usernames") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("username" to "ruanocsrf", "reason" to "r"))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        csrfViolated(admin.id) shouldBe true
        ReservedUsernamesTestSupport.exists(dataSource, "ruanocsrf") shouldBe false
    }

    "5.28 — read_only role with a valid CSRF token → 403, no write, no CSRF violation" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reserved-usernames") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "username" to "ruareadonly", "reason" to "r"))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        ReservedUsernamesTestSupport.exists(dataSource, "ruareadonly") shouldBe false
        csrfViolated(admin.id) shouldBe false // the role gate (not CSRF) rejected
    }

    "5.29 — CSRF is checked before the role gate (read_only + no CSRF → CSRF rejection)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reserved-usernames/anything/remove") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        csrfViolated(admin.id) shouldBe true // CSRF ran before the role gate + before path parsing
    }

    // ---- Bulk --------------------------------------------------------------

    "5.13 — bulk add >1000 rows → 400, no insert" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val bigCsv = (1..1001).joinToString("\n") { "ruabig$it,r" }
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reserved-usernames/bulk") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "csv" to bigCsv))
                }
            res.status shouldBe HttpStatusCode.BadRequest
        }
        ReservedUsernamesTestSupport.exists(dataSource, "ruabig1") shouldBe false
    }

    "5.35 — bulk route is CSRF- and role-gated" {
        val owner = seedAdmin()
        val ownerToken = AdminAuthTestSupport.seedSession(dataSource, owner.id)
        val readOnly = seedAdmin(role = "read_only")
        val readOnlyToken = AdminAuthTestSupport.seedSession(dataSource, readOnly.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // no CSRF → 403 + violation
            client.post("/admin/reserved-usernames/bulk") {
                header(HttpHeaders.Cookie, cookie(ownerToken))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("csv" to "ruabulkx,r"))
            }.status shouldBe HttpStatusCode.Forbidden
            // read_only + valid CSRF → 403 (role gate)
            client.post("/admin/reserved-usernames/bulk") {
                header(HttpHeaders.Cookie, cookie(readOnlyToken))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(readOnlyToken), "csv" to "ruabulky,r"))
            }.status shouldBe HttpStatusCode.Forbidden
        }
        csrfViolated(owner.id) shouldBe true
        ReservedUsernamesTestSupport.exists(dataSource, "ruabulkx") shouldBe false
        ReservedUsernamesTestSupport.exists(dataSource, "ruabulky") shouldBe false
    }

    // ---- Edit / remove via the {username} path -----------------------------

    "edit-reason via the {username} path (no-JS) → 303 + reason updated" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ReservedUsernamesTestSupport.seed(dataSource, track("ruaedithttp"), reason = "old")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/reserved-usernames/ruaedithttp/edit-reason") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "newhttp"))
            }.status shouldBe HttpStatusCode.SeeOther
        }
        ReservedUsernamesTestSupport.reasonOf(dataSource, "ruaedithttp") shouldBe "newhttp"
    }

    "remove via the {username} path (no-JS) → 303 + row gone" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ReservedUsernamesTestSupport.seed(dataSource, track("ruarmhttp"), reason = "r")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/reserved-usernames/ruarmhttp/remove") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token)))
            }.status shouldBe HttpStatusCode.SeeOther
        }
        ReservedUsernamesTestSupport.exists(dataSource, "ruarmhttp") shouldBe false
    }
})
