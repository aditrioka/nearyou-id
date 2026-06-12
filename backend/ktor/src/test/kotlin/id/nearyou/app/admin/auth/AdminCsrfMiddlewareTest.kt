package id.nearyou.app.admin.auth

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.ADMIN_AUTH_NAME
import id.nearyou.app.admin.admin
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the `admin-login` capability's CSRF middleware +
 * the `admin_csrf_violation` audit rows + logout. Uses `POST /admin/logout`
 * for the rejection cases and a synthetic non-terminating `POST
 * /admin/test-echo` route (wired in a second `authenticate` block that
 * mirrors the production CSRF intercept) for the token-reuse / precedence /
 * no-rotation scenarios.
 *
 * DB-backed; tagged `database`.
 */
@Tags("database")
class AdminCsrfMiddlewareTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        seeded.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seeded.clear()
    }

    fun seedAdmin(): AdminAuthTestSupport.SeededAdmin = AdminAuthTestSupport.seedAdmin(dataSource).also { seeded.add(it.id) }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun formBody(vararg pairs: Pair<String, String>): String = pairs.toList().formUrlEncode()

    /**
     * testApplication wiring that adds a synthetic `POST /admin/test-echo`
     * route behind the same auth + CSRF gate as production routes.
     */
    suspend fun withEchoApp(block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit) {
        testApplication {
            application {
                install(
                    createApplicationPlugin("TestClientIpDefaultEcho") {
                        onCall { call ->
                            val hasCf = !call.request.headers["CF-Connecting-IP"].isNullOrBlank()
                            if (!hasCf && !call.attributes.contains(id.nearyou.app.common.ClientIpAttributeKey)) {
                                call.attributes.put(id.nearyou.app.common.ClientIpAttributeKey, "127.0.0.1")
                            }
                        }
                    },
                )
                admin(
                    dataSource = dataSource,
                    aesKeyProvider = { AdminAuthTestSupport.FIXED_AES_KEY },
                    csrfHmacKeyProvider = { AdminAuthTestSupport.FIXED_CSRF_HMAC_KEY },
                    environmentName = AdminAuthTestSupport.TEST_ENVIRONMENT_NAME,
                )
                val auditLogger = AdminAuditLogger(dataSource)
                routing {
                    route("/admin") {
                        authenticate(ADMIN_AUTH_NAME) {
                            // Mirror the production per-handler CSRF contract:
                            // the state-changing handler validates CSRF first.
                            post("/test-echo") {
                                if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
                                call.respondText("echo")
                            }
                        }
                    }
                }
            }
            val client = createClient { followRedirects = false }
            block(client)
        }
    }

    // ============ CSRF rejection (via POST /admin/logout) ============

    "state-changing request without CSRF token returns 403" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/logout") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
    }

    "state-changing request with wrong CSRF token returns 403" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/logout") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, "wrong-token")
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
    }

    "state-changing request with correct CSRF token passes through (logout 302)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val csrf = AdminAuthTestSupport.csrfFor(token)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/logout") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, csrf)
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "CSRF token accepted from _csrf form field when header absent" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val csrf = AdminAuthTestSupport.csrfFor(token)
        withEchoApp { client ->
            val res =
                client.post("/admin/test-echo") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("_csrf" to csrf))
                }
            res.status shouldBe HttpStatusCode.OK
        }
    }

    "header takes precedence over form field" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val csrf = AdminAuthTestSupport.csrfFor(token)
        withEchoApp { client ->
            val res =
                client.post("/admin/test-echo") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, csrf) // correct
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("_csrf" to "different-wrong-value")) // ignored
                }
            res.status shouldBe HttpStatusCode.OK
        }
    }

    "POST /admin/login is exempt from CSRF middleware" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // No session, no CSRF token — login POST must still be processed
            // (returns the generic error, NOT a 403).
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to "x@y.z", "password" to "p", "totp" to "000000"))
                }
            res.status shouldBe HttpStatusCode.OK
        }
    }

    "GET requests are not gated by CSRF middleware" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
        }
    }

    "CSRF token does NOT rotate per request — same token accepted twice, hash unchanged" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val csrf = AdminAuthTestSupport.csrfFor(token)
        withEchoApp { client ->
            val hashBefore = AdminAuthTestSupport.sessionByTokenHash(dataSource, token)!!.csrfTokenHash
            val r1 =
                client.post("/admin/test-echo") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, csrf)
                }
            r1.status shouldBe HttpStatusCode.OK
            val r2 =
                client.post("/admin/test-echo") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, csrf)
                }
            r2.status shouldBe HttpStatusCode.OK
            val hashAfter = AdminAuthTestSupport.sessionByTokenHash(dataSource, token)!!.csrfTokenHash
            hashAfter shouldBe hashBefore
            // No csrf-violation rows.
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .none { it.actionType == "admin_csrf_violation" } shouldBe true
        }
    }

    // ============ CSRF audit rows ============

    "missing-token rejection writes admin_csrf_violation with reason missing_token" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/logout") { header(HttpHeaders.Cookie, cookie(token)) }
            val row =
                AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                    .first { it.actionType == "admin_csrf_violation" }
            row.targetType shouldBe "csrf"
            row.reason shouldBe "missing_token"
        }
    }

    "header-mismatch rejection writes admin_csrf_violation with reason header_mismatch" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/logout") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, "wrong-csrf-token-value-here")
            }
            val row =
                AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                    .first { it.actionType == "admin_csrf_violation" }
            row.reason shouldBe "header_mismatch"
            // Audit row never contains the submitted token value.
            listOfNotNull(row.reason, row.beforeState, row.afterState, row.targetType).forEach {
                it.contains("wrong-csrf-token-value-here") shouldBe false
            }
        }
    }

    // ============ Logout ============

    "logout sets revoked_at, clears cookie, redirects, writes admin_logout audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val csrf = AdminAuthTestSupport.csrfFor(token)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/logout") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, csrf)
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
            val clearCookie =
                res.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    .first { it.startsWith(AdminAuthProvider.COOKIE_NAME) }
            clearCookie.contains("Max-Age=0") shouldBe true

            val session = AdminAuthTestSupport.sessionByTokenHash(dataSource, token)!!
            (session.revokedAt != null) shouldBe true
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .any { it.actionType == "admin_logout" } shouldBe true
        }
    }

    "logout is idempotent against already-revoked sessions (middleware rejects, no new audit row)" {
        val admin = seedAdmin()
        val token =
            AdminAuthTestSupport.seedSession(
                dataSource,
                admin.id,
                revokedAt = Instant.now().minusSeconds(5),
            )
        val csrf = AdminAuthTestSupport.csrfFor(token)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/logout") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, csrf)
                }
            // Session middleware rejects the revoked session first (302).
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
            // Logout handler never reached → no admin_logout row.
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .none { it.actionType == "admin_logout" } shouldBe true
        }
    }
})
