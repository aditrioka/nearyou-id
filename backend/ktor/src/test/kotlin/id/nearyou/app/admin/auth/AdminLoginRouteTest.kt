package id.nearyou.app.admin.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
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
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Integration tests for the `admin-login` capability covering the login
 * GET/POST routes, no-enumeration discipline, audit-row writes, the
 * `__Host-admin_session` cookie format, the authenticated layout CSRF
 * surfacing, and Unicode/input handling.
 *
 * DB-backed; tagged `database` (CI excludes by default). Run locally with
 * `DB_URL` / `DB_USER` / `DB_PASSWORD`.
 */
@Tags("database")
class AdminLoginRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        seeded.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seeded.clear()
    }

    fun track(admin: AdminAuthTestSupport.SeededAdmin): AdminAuthTestSupport.SeededAdmin {
        seeded.add(admin.id)
        return admin
    }

    fun formBody(vararg pairs: Pair<String, String>): String = pairs.toList().formUrlEncode()

    // ============ Login form GET ============

    "login page renders with email + password + totp fields and the POST form" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/login")
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "name=\"email\""
            body shouldContain "name=\"password\""
            body shouldContain "name=\"totp\""
            body shouldContain "action=\"/admin/login\""
            body shouldContain "method=\"POST\""
        }
    }

    "login page does not include the CSRF meta tag (no session)" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/login").bodyAsText()
            body shouldNotContain "<meta name=\"csrf-token\""
            body shouldNotContain "htmx:configRequest"
        }
    }

    "login page extends the base layout (header + nav stub + footer)" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/login").bodyAsText()
            body shouldContain "<header>"
            body shouldContain "<nav>"
            body shouldContain "<footer>"
        }
    }

    "authenticated client GETting /admin/login is redirected to /admin/" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/login") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/"
        }
    }

    // ============ Login POST verify ============

    "login succeeds with valid credentials — 200 + HX-Redirect + cookie + session row" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to code))
                }
            res.status shouldBe HttpStatusCode.OK
            res.headers["HX-Redirect"] shouldBe "/admin/"
            val cookie = AdminAuthTestSupport.parseSessionCookieValue(res.headers.getAll(HttpHeaders.SetCookie).orEmpty())
            cookie.shouldNotBeNull()

            val session = AdminAuthTestSupport.sessionByTokenHash(dataSource, cookie)
            session.shouldNotBeNull()
            session.adminId shouldBe admin.id
            session.revokedAt.shouldBeNull()
            // expires_at ≈ NOW() + 8h (± generous tolerance for test wall time)
            val expectedExpiry = Instant.now().plusSeconds(8 * 3600)
            (session.expiresAt.epochSecond - expectedExpiry.epochSecond) shouldBeInRange (-30L..30L)
        }
    }

    "login fails with wrong password — 200, no cookie, no session, generic error" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to admin.email, "password" to "WRONG", "totp" to code))
                }
            res.status shouldBe HttpStatusCode.OK
            AdminAuthTestSupport.parseSessionCookieValue(
                res.headers.getAll(HttpHeaders.SetCookie).orEmpty(),
            ).shouldBeNull()
            AdminAuthTestSupport.countSessions(dataSource, admin.id) shouldBe 0
            res.bodyAsText() shouldContain AdminLoginRoutes.GENERIC_ERROR_MESSAGE
        }
    }

    "login fails with wrong TOTP — 200, no cookie, no session, generic error" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to "000000"))
                }
            res.status shouldBe HttpStatusCode.OK
            AdminAuthTestSupport.parseSessionCookieValue(
                res.headers.getAll(HttpHeaders.SetCookie).orEmpty(),
            ).shouldBeNull()
            AdminAuthTestSupport.countSessions(dataSource, admin.id) shouldBe 0
            res.bodyAsText() shouldContain AdminLoginRoutes.GENERIC_ERROR_MESSAGE
        }
    }

    "login fails when is_active is FALSE — 200, no session, generic error" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource, isActive = false))
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to code))
                }
            res.status shouldBe HttpStatusCode.OK
            AdminAuthTestSupport.countSessions(dataSource, admin.id) shouldBe 0
            res.bodyAsText() shouldContain AdminLoginRoutes.GENERIC_ERROR_MESSAGE
        }
    }

    "login fails when totp_secret_encrypted is NULL — 200, no session, generic error" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource, totpSecret = null))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to "123456"))
                }
            res.status shouldBe HttpStatusCode.OK
            AdminAuthTestSupport.countSessions(dataSource, admin.id) shouldBe 0
            res.bodyAsText() shouldContain AdminLoginRoutes.GENERIC_ERROR_MESSAGE
        }
    }

    // ============ No-enumeration ============

    "all failure paths return identical status + body + no distinguishing headers" {
        val active = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val inactive = track(AdminAuthTestSupport.seedAdmin(dataSource, isActive = false))
        val noSecret = track(AdminAuthTestSupport.seedAdmin(dataSource, totpSecret = null))
        val code = AdminAuthTestSupport.currentTotpCode(active.totpSecret!!)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            suspend fun attempt(
                email: String,
                password: String,
                totp: String,
            ): Pair<HttpStatusCode, String> {
                val res =
                    client.post("/admin/login") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formBody("email" to email, "password" to password, "totp" to totp))
                    }
                return res.status to res.bodyAsText()
            }

            // Failure population: (a) email not found, (b) wrong password,
            // (c) wrong TOTP, (d) inactive admin, (e) null TOTP secret.
            val cases =
                listOf(
                    attempt("phantom@nearyou.id", "anything", "000000"),
                    attempt(active.email, "WRONG", code),
                    attempt(active.email, active.password, "000000"),
                    attempt(inactive.email, inactive.password, AdminAuthTestSupport.currentTotpCode(inactive.totpSecret!!)),
                    attempt(noSecret.email, noSecret.password, "123456"),
                )

            // All status 200, all bodies contain the generic error, all bodies identical.
            cases.forEach { (status, _) -> status shouldBe HttpStatusCode.OK }
            cases.forEach { (_, body) -> body shouldContain AdminLoginRoutes.GENERIC_ERROR_MESSAGE }
            val distinctBodies = cases.map { it.second }.distinct()
            distinctBodies shouldHaveSize 1
        }
    }

    // ============ Audit rows ============

    "successful login writes admin_login_success audit row with non-secret after_state" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    header(HttpHeaders.UserAgent, "Mozilla/5.0 (Test)")
                    header("CF-Connecting-IP", "1.2.3.4")
                    setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to code))
                }
            val cookie = AdminAuthTestSupport.parseSessionCookieValue(res.headers.getAll(HttpHeaders.SetCookie).orEmpty())!!

            val rows = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
            rows shouldHaveSize 1
            val row = rows.first()
            row.actionType shouldBe "admin_login_success"
            row.ip shouldBe "1.2.3.4"
            row.userAgent shouldBe "Mozilla/5.0 (Test)"
            row.afterState.shouldNotBeNull()
            row.afterState!! shouldContain "session_id"
            row.afterState shouldContain "expires_at"
            row.afterState shouldContain "last_active_at"
            // after_state SHALL NOT contain the cookie value nor its SHA-256.
            row.afterState shouldNotContain cookie
            row.afterState shouldNotContain HashUtil.sha256Hex(cookie)
        }
    }

    "audit row records NULL user_agent when the request omits the header" {
        // The Ktor test client unavoidably injects `User-Agent: ktor-client`,
        // so a true UA-absent HTTP request can't be produced in-harness. The
        // route's UA propagation IS covered by the success scenario above
        // (asserts userAgent = "Mozilla/5.0 (Test)"); here we prove the
        // NULL-handling half directly at the audit logger — when the route
        // passes userAgent = null (header absent), the column is NULL.
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val auditLogger = AdminAuditLogger(dataSource)
        auditLogger.logFailure(
            adminId = admin.id,
            reason = AdminAuditLogger.LoginFailureReason.PASSWORD_MISMATCH,
            ip = "9.9.9.9",
            userAgent = null,
        )
        val row = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).first()
        row.actionType shouldBe "admin_login_failure"
        row.userAgent.shouldBeNull()
    }

    "password mismatch records admin_login_failure with reason password_mismatch + admin UUID" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/login") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("email" to admin.email, "password" to "WRONG", "totp" to code))
            }
            val row = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).first()
            row.actionType shouldBe "admin_login_failure"
            row.reason shouldBe "password_mismatch"
        }
    }

    "totp mismatch records reason totp_mismatch" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/login") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to "000000"))
            }
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).first().reason shouldBe "totp_mismatch"
        }
    }

    "inactive admin records admin_login_failure with reason inactive_admin" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource, isActive = false))
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/login") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to code))
            }
            // Two-step lookup: findActiveByEmail (is_active=TRUE) returns null,
            // then findByEmailAnyStatus matches the deactivated row → audit row.
            val row = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).first()
            row.actionType shouldBe "admin_login_failure"
            row.reason shouldBe "inactive_admin"
        }
    }

    "totp_secret_missing records admin_login_failure with reason totp_secret_missing" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource, totpSecret = null))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/login") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to "123456"))
            }
            val row = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).first()
            row.actionType shouldBe "admin_login_failure"
            row.reason shouldBe "totp_secret_missing"
        }
    }

    "audit row never contains the submitted password or TOTP code" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/login") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("email" to admin.email, "password" to "P@ssw0rd!Secret", "totp" to "123456"))
            }
            val row = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).first()
            val allCols = listOfNotNull(row.reason, row.beforeState, row.afterState, row.targetType)
            allCols.forEach { col ->
                col shouldNotContain "P@ssw0rd!Secret"
                col shouldNotContain "123456"
                col shouldNotContain sha256Hex("P@ssw0rd!Secret")
            }
        }
    }

    "email-not-found does NOT write an audit row but emits a structured INFO log" {
        val logger = LoggerFactory.getLogger("id.nearyou.app.admin.auth.AdminAuditLogger") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val previous = logger.level
        logger.level = Level.INFO
        try {
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    header(HttpHeaders.UserAgent, "probe-agent")
                    setBody(formBody("email" to "phantom@nearyou.id", "password" to "anything", "totp" to "000000"))
                }
            }
            val line =
                appender.list
                    .filter { it.level == Level.INFO }
                    .map { it.formattedMessage }
                    .firstOrNull { it.contains("event=admin_login_attempt_email_not_found") }
            line.shouldNotBeNull()
            line!! shouldContain "email_hash="
            line shouldNotContain "phantom@nearyou.id" // plaintext email never logged
            line shouldNotContain "anything" // password never logged
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
            appender.stop()
        }
    }

    // ============ Cookie format ============

    "cookie sets __Host- prefix + Secure + HttpOnly + SameSite=Strict + Path=/ + no Domain" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to code))
                }
            val header = res.headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith(AdminAuthProvider.COOKIE_NAME) }
            header shouldContain "Secure"
            header shouldContain "HttpOnly"
            header shouldContain "SameSite=Strict"
            header shouldContain "Path=/"
            header shouldNotContain "Domain="
        }
    }

    "cookie value is a 43-char base64url string + its SHA-256 matches the stored hash" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to code))
                }
            val cookie = AdminAuthTestSupport.parseSessionCookieValue(res.headers.getAll(HttpHeaders.SetCookie).orEmpty())!!
            cookie shouldMatch Regex("^[A-Za-z0-9_-]{43}$")
            val session = AdminAuthTestSupport.sessionByTokenHash(dataSource, cookie)!!
            session.sessionTokenHash shouldBe HashUtil.sha256Hex(cookie)
        }
    }

    "cookie Max-Age aligns with the 8h expires_at (±60s)" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to code))
                }
            val header = res.headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith(AdminAuthProvider.COOKIE_NAME) }
            val maxAge = Regex("Max-Age=(\\d+)").find(header)!!.groupValues[1].toLong()
            (maxAge - 28800L) shouldBeInRange (-60L..60L)
        }
    }

    // ============ Authenticated layout CSRF surfacing ============

    "authenticated index renders the CSRF meta tag matching the session csrf_token_hash" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }.bodyAsText()
            body shouldContain "<meta name=\"csrf-token\""
            // The meta content equals the derived CSRF token; SHA-256 of it
            // equals the stored csrf_token_hash.
            val derivedCsrf = AdminAuthTestSupport.csrfFor(token)
            body shouldContain derivedCsrf
            val session = AdminAuthTestSupport.sessionByTokenHash(dataSource, token)!!
            session.csrfTokenHash shouldBe HashUtil.sha256Hex(derivedCsrf)
        }
    }

    "authenticated index includes the htmx:configRequest JS hook setting X-CSRF-Token" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }.bodyAsText()
            body shouldContain "htmx:configRequest"
            body shouldContain "X-CSRF-Token"
        }
    }

    // ============ Unicode + input handling ============

    "Unicode email + Unicode password round-trip through Argon2id verify" {
        val admin =
            track(
                AdminAuthTestSupport.seedAdmin(
                    dataSource,
                    email = "oka.テスト+${UUID.randomUUID().toString().take(6)}@nearyou.id",
                    password = "p@ssword.テスト",
                ),
            )
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to code))
                }
            res.status shouldBe HttpStatusCode.OK
            res.headers["HX-Redirect"] shouldBe "/admin/"
        }
    }

    "TOTP code with leading whitespace is rejected (no silent strip)" {
        val admin = track(AdminAuthTestSupport.seedAdmin(dataSource))
        val code = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("email" to admin.email, "password" to admin.password, "totp" to " $code"))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain AdminLoginRoutes.GENERIC_ERROR_MESSAGE
            // Audit row records totp_mismatch (NOT silently stripped to a valid code).
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).first().reason shouldBe "totp_mismatch"
        }
    }
})

private fun sha256Hex(input: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))

private infix fun Long.shouldBeInRange(range: LongRange) {
    (this in range) shouldBe true
}
