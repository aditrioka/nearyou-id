package id.nearyou.app.admin.auth

import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.util.UUID

/**
 * Integration tests for the `admin-panel-scaffold` capability's MODIFIED
 * requirements under `admin-login-argon2-totp` — the index route is now
 * auth-gated (302 to /admin/login when unauthenticated), static assets stay
 * public, and the layout structural sections render in both authed +
 * unauthed contexts. Replaces the deleted (pre-auth-gate) AdminScaffoldTest.
 *
 * DB-backed; tagged `database`.
 */
@Tags("database")
class AdminPanelScaffoldAuthTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        seeded.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seeded.clear()
    }

    fun seedAdmin(): AdminAuthTestSupport.SeededAdmin = AdminAuthTestSupport.seedAdmin(dataSource).also { seeded.add(it.id) }

    "authenticated GET /admin/ returns 200 with rendered Admin Panel content" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "Admin Panel"
            body shouldContain "<header>"
            body shouldContain "<nav>"
            body shouldContain "<footer>"
        }
    }

    "unauthenticated GET /admin/ redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "unmapped admin route returns 404 (unauthenticated)" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // Per spec: 404 is returned at the routing layer regardless of auth.
            // (Ktor resolves an unmapped path to 404 before the authenticate
            // block's challenge fires for the matched-but-unauthenticated case.)
            val res = client.get("/admin/nonexistent-page")
            res.status shouldBe HttpStatusCode.NotFound
        }
    }

    "non-GET on /admin/ index returns 405" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // /admin/ has only GET wired; POST → 405. Carry a valid session so
            // the auth gate passes and the routing layer's method-not-allowed
            // surfaces (not a redirect).
            val res =
                client.post("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.MethodNotAllowed
        }
    }

    "static HTMX asset is served publicly (no auth required)" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/static/htmx.min.js")
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "var htmx"
        }
    }

    "path-traversal under /admin/static/ does not serve a 2xx" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/static/../../some-other-resource")
            (res.status.value !in 200..299) shouldBe true
        }
    }

    "unauthenticated login page extends layout but omits CSRF block" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/login").bodyAsText()
            body shouldContain "<header>"
            body shouldContain "<nav>"
            body shouldContain "<footer>"
            (body.contains("<meta name=\"csrf-token\"")) shouldBe false
            (body.contains("htmx:configRequest")) shouldBe false
        }
    }
})
