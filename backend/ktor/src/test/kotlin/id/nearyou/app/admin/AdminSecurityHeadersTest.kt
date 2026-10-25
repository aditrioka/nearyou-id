package id.nearyou.app.admin

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
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
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * `admin-panel-scaffold` "Every response carries the admin security response headers" (#531 —
 * host-wide, so router-normalised admin paths are covered)
 * + the MODIFIED base-layout scenario "Every admin page loads the vendored panel
 * script and the htmx CSP config". DB-backed (the admin wiring needs a pool).
 */
@Tags("database")
class AdminSecurityHeadersTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        seeded.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seeded.clear()
    }

    fun sessionCookie(): String {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource).also { seeded += it.id }
        return "${AdminAuthProvider.COOKIE_NAME}=${AdminAuthTestSupport.seedSession(dataSource, admin.id)}"
    }

    fun HttpResponse.shouldCarryAdminHeaders() {
        ADMIN_SECURITY_HEADERS.forEach { (name, value) -> headers.getAll(name) shouldBe listOf(value) }
    }

    "the header set is exactly the reviewed CSP + anti-framing + nosniff + referrer + HSTS values" {
        ADMIN_SECURITY_HEADERS shouldBe
            mapOf(
                "Content-Security-Policy" to
                    "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; " +
                    "base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
                "X-Frame-Options" to "DENY",
                "X-Content-Type-Options" to "nosniff",
                "Referrer-Policy" to "same-origin",
                "Strict-Transport-Security" to "max-age=31536000",
            )
    }

    "login page carries the security header set" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/login")
            res.status shouldBe HttpStatusCode.OK
            res.shouldCarryAdminHeaders()
        }
    }

    "authenticated page and HTMX fragment carry the header set" {
        val cookie = sessionCookie()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val page = client.get("/admin/") { header(HttpHeaders.Cookie, cookie) }
            page.status shouldBe HttpStatusCode.OK
            page.shouldCarryAdminHeaders()

            val fragment =
                client.get("/admin/actions-log") {
                    header(HttpHeaders.Cookie, cookie)
                    header("HX-Request", "true")
                }
            fragment.status shouldBe HttpStatusCode.OK
            fragment.bodyAsText() shouldContain "id=\"actions-log-table\""
            fragment.shouldCarryAdminHeaders()
        }
    }

    "unauthenticated redirect, bare /admin redirect, static asset and unmapped admin path carry the header set" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val redirect = client.get("/admin/users")
            redirect.status shouldBe HttpStatusCode.Found
            redirect.headers[HttpHeaders.Location] shouldBe "/admin/login"
            redirect.shouldCarryAdminHeaders()

            val bare = client.get("/admin")
            bare.status shouldBe HttpStatusCode.Found
            bare.shouldCarryAdminHeaders()

            val asset = client.get("/admin/static/admin.js")
            asset.status shouldBe HttpStatusCode.OK
            asset.shouldCarryAdminHeaders()

            val missing = client.get("/admin/nonexistent-page")
            missing.status shouldBe HttpStatusCode.NotFound
            missing.shouldCarryAdminHeaders()
        }
    }

    "router-normalised admin paths and every other path carry the header set too (host-wide)" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // The router collapses `//` and decodes `%61` → `a`, so these reach the admin
            // handlers; a raw-path `/admin` prefix check would have missed them.
            val doubled = client.get("//admin/users")
            doubled.status shouldBe HttpStatusCode.Found
            doubled.shouldCarryAdminHeaders()
            val encoded = client.get("/%61dmin/login")
            encoded.status shouldBe HttpStatusCode.OK
            encoded.bodyAsText() shouldContain "action=\"/admin/login\""
            encoded.shouldCarryAdminHeaders()
            for (path in listOf("/administrator", "/api/v1/timeline/global")) {
                client.get(path).shouldCarryAdminHeaders()
            }
        }
    }

    "an early transport rejection (RequestBodyLimit 413) carries the header set" {
        AdminAuthTestSupport.withAdminApp(dataSource, productionBodyLimit = true) { client ->
            val res =
                client.post("/admin/reserved-usernames/bulk") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() })
                }
            res.status shouldBe HttpStatusCode.PayloadTooLarge
            res.shouldCarryAdminHeaders()
        }
    }

    "admin.js is served with a JS content type and matches its SHA256SUMS pin" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/static/admin.js")
            res.status shouldBe HttpStatusCode.OK
            res.contentType()?.withoutParameters()?.toString() shouldBeIn listOf("application/javascript", "text/javascript")
            val served = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(res.body<ByteArray>()))
            val pinned =
                checkNotNull(javaClass.classLoader.getResource("admin/static/htmx.min.js.SHA256SUMS"))
                    .readText()
                    .lines()
                    .map { it.split(Regex("\\s+")) }
                    .single { it.size == 2 && it[1] == "admin.js" }[0]
            served shouldBe pinned
        }
    }

    "every admin page loads admin.js after htmx plus the htmx CSP config, with no inline script" {
        val cookie = sessionCookie()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val pages =
                listOf(
                    client.get("/admin/login").bodyAsText(),
                    client.get("/admin/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText(),
                )
            for (body in pages) {
                val htmx = body.indexOf("<script src=\"/admin/static/htmx.min.js\" defer></script>")
                val panel = body.indexOf(AdminAuthTestSupport.ADMIN_JS_TAG)
                (htmx in 0 until panel) shouldBe true
                body shouldContain
                    "<meta name=\"htmx-config\" content='{\"includeIndicatorStyles\":false,\"allowEval\":false," +
                    "\"allowScriptTags\":false,\"historyCacheSize\":0,\"refreshOnHistoryMiss\":true}'>"
                AdminAuthTestSupport.INLINE_SCRIPT_TAG.containsMatchIn(body) shouldBe false
            }
        }
    }
})
