package id.nearyou.app.admin.actionslog

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.auth.AdminCsrfGate
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the `GET /admin/actions-log` route — the viewer half
 * of the `admin-actions-log-viewer` capability (tasks.md Section 7).
 *
 * DB-backed; tagged `database`. Uses the `AdminAuthTestSupport` harness for
 * the authenticated-session wiring + `ActionsLogTestSupport` for audit-row
 * seeding. Asserts the auth gate, render, HTMX/plain dual-mode, lenient
 * filtering, output escaping, the read-only/no-mutation posture, role access,
 * empty state, and the malformed-cursor fallback.
 */
@Tags("database")
class AdminActionsLogRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        seeded.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seeded.clear()
    }

    fun seedAdmin(role: String = "owner"): AdminAuthTestSupport.SeededAdmin =
        AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seeded.add(it.id) }

    val base = Instant.parse("2026-05-20T00:00:00Z")

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    "7.2 — authenticated GET renders the table with a seeded row + actor display_name" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_login_success", base)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/actions-log") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "admin_login_success"
            body shouldContain "Test Admin"
            body shouldContain "<header>" // full page extends layout
        }
    }

    "7.3 — unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/actions-log")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "7.4 — HX-Request returns only the table fragment (no full-page layout)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_login_success", base)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/actions-log") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "id=\"actions-log-table\""
            body shouldNotContain "<html"
            body shouldNotContain "<header>"
        }
    }

    "7.5 — plain GET with a filter returns the full page reflecting the filter" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_login_success", base.plusSeconds(1))
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_logout", base.plusSeconds(2))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/actions-log?action_type=admin_login_success") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "<header>" // full page
            body shouldContain "admin_login_success"
            body shouldNotContain "admin_logout"
        }
    }

    "7.6 — malformed admin_id / unparseable from are ignored; other filters still apply" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_login_success", base.plusSeconds(1))
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_logout", base.plusSeconds(2))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/actions-log?admin_id=not-a-uuid&from=13th-of-never&action_type=admin_login_success") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "admin_login_success"
            body shouldNotContain "admin_logout"
        }
    }

    "7.7 — after_state with <script> renders escaped, not as a live tag" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(
            dataSource,
            admin.id,
            "admin_login_success",
            base,
            afterStateJson = "{\"x\":\"<script>alert(1)</script>\"}",
        )

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/actions-log") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;script&gt;"
            body shouldNotContain "<script>alert(1)</script>"
        }
    }

    "7.7a — client-controlled user_agent with markup renders escaped" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(
            dataSource,
            admin.id,
            "admin_login_success",
            base,
            userAgent = "<img src=x onerror=alert(1)>",
        )

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/actions-log") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;img "
            body shouldNotContain "<img src=x onerror=alert(1)>"
        }
    }

    "7.7b — NULL before_state renders as an em-dash, not the literal 'null'" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(
            dataSource,
            admin.id,
            "admin_login_success",
            base,
            beforeStateJson = null,
            afterStateJson = "{\"ok\":1}",
            reason = "a reason",
            ip = "1.2.3.4",
            userAgent = "ua",
        )

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/actions-log") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "—" // before_state placeholder
            body shouldNotContain "null"
        }
    }

    "7.8 — POST /admin/actions-log returns 405 (only GET is wired)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/actions-log") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.MethodNotAllowed
        }
    }

    "7.9 — serving the viewer writes no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_login_success", base)
        val before = AdminAuthTestSupport.countAuditRows(dataSource, admin.id)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/actions-log") { header(HttpHeaders.Cookie, cookie(token)) }
            client.get("/admin/actions-log") { header(HttpHeaders.Cookie, cookie(token)) }
        }
        AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe before
    }

    "7.10 — read_only admin can view the audit log" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_login_success", base)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/actions-log") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
        }
    }

    "7.11 — no-match filter returns 200 with an empty-state message" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_login_success", base)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/actions-log?action_type=matches_nothing_at_all") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No audit log entries"
        }
    }

    "7.12 — malformed cursor falls back to the newest page (no error)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_login_success", base)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/actions-log?cursor=not-a-valid-cursor") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "admin_login_success"
        }
    }

    "7.13 — full page carries the CSRF meta tag; HTMX fragment does not" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        ActionsLogTestSupport.seedActionLog(dataSource, admin.id, "admin_login_success", base)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val full = client.get("/admin/actions-log") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            full shouldContain "<meta name=\"csrf-token\""

            val fragment =
                client.get("/admin/actions-log") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            fragment shouldNotContain "<meta name=\"csrf-token\""
        }
    }
})
