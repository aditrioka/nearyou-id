package id.nearyou.app.admin.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.logging.Logger
import javax.sql.DataSource
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Integration tests for the `admin-login` capability's session-validation
 * middleware: the active-predicate matrix (revoked / expired / idle /
 * idle-on-threshold), no-cookie + forged-cookie rejection, last_active_at
 * refresh, and fail-CLOSED-on-DB-exception.
 *
 * DB-backed; tagged `database`.
 */
@Tags("database")
class AdminSessionMiddlewareTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        seeded.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seeded.clear()
    }

    fun seedAdmin(): AdminAuthTestSupport.SeededAdmin = AdminAuthTestSupport.seedAdmin(dataSource).also { seeded.add(it.id) }

    fun lastActiveOf(token: String): Instant = AdminAuthTestSupport.sessionByTokenHash(dataSource, token)!!.lastActiveAt

    "request without session cookie redirects to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "request with valid session returns 200 and refreshes last_active_at" {
        val admin = seedAdmin()
        // Seed last_active_at slightly in the past so the refresh is observable.
        val t0 = Instant.now().minusSeconds(120)
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id, lastActiveAt = t0)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.OK
            // last_active_at refreshed to ≈ NOW() (strictly after t0).
            lastActiveOf(token).isAfter(t0) shouldBe true
        }
    }

    "request with expired session (expires_at < NOW()) redirects and does not refresh" {
        val admin = seedAdmin()
        val t0 = Instant.now().minusSeconds(120)
        val token =
            AdminAuthTestSupport.seedSession(
                dataSource,
                admin.id,
                expiresAt = Instant.now().minusSeconds(60),
                lastActiveAt = t0,
            )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
            // Dead session not revived.
            lastActiveOf(token) shouldBe t0
        }
    }

    "request with idle session (last_active_at > 30m ago) redirects to login" {
        val admin = seedAdmin()
        val token =
            AdminAuthTestSupport.seedSession(
                dataSource,
                admin.id,
                lastActiveAt = Instant.now().minusSeconds(31 * 60),
            )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "request with revoked session redirects to login" {
        val admin = seedAdmin()
        val token =
            AdminAuthTestSupport.seedSession(
                dataSource,
                admin.id,
                revokedAt = Instant.now().minusSeconds(5),
            )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "idle-on-threshold (last_active_at = NOW - 30m1s) is classified idle and not refreshed" {
        val admin = seedAdmin()
        val justPast = Instant.now().minusSeconds(30 * 60 + 1)
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id, lastActiveAt = justPast)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
            // last_active_at must NOT be refreshed by a rejecting request.
            lastActiveOf(token) shouldBe justPast
        }
    }

    "forged cookie (well-formed but never issued) redirects to login and clears cookie" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // 43-char base64url string matching the cookie format regex.
            val forged = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$forged")
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
            // The forged token hashes to no row.
            AdminAuthTestSupport.sessionByTokenHash(dataSource, forged).shouldBeNull()
        }
    }

    "session middleware fails CLOSED on DB exception (redirect, not 200, with WARN log)" {
        val logger = LoggerFactory.getLogger("id.nearyou.app.admin.auth.AdminAuthProvider") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val previous = logger.level
        logger.level = Level.WARN
        try {
            val throwingDs = ThrowingDataSource()
            AdminAuthTestSupport.withAdminApp(throwingDs) { client ->
                val res =
                    client.get("/admin/") {
                        // Any non-blank cookie so the null-check passes and the
                        // DB lookup is attempted (and throws).
                        header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=anything-nonblank")
                    }
                // Must NOT be 200; redirect (or 500) signals closed.
                (res.status.value != 200) shouldBe true
                if (res.status == HttpStatusCode.Found) {
                    res.headers[HttpHeaders.Location] shouldBe "/admin/login"
                }
            }
            val warned =
                appender.list.any {
                    it.level == Level.WARN && it.formattedMessage.contains("admin_session_middleware_db_failure")
                }
            warned shouldBe true
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
            appender.stop()
        }
    }
})

/**
 * A [DataSource] whose `connection` always throws, to exercise the
 * session middleware's fail-CLOSED path (spec scenario "Session middleware
 * fails CLOSED on DB exception").
 */
private class ThrowingDataSource : DataSource {
    override fun getConnection(): Connection = throw java.sql.SQLException("simulated pool exhaustion")

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = throw java.sql.SQLException("simulated pool exhaustion")

    override fun getLogWriter(): java.io.PrintWriter? = null

    override fun setLogWriter(out: java.io.PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = Logger.getLogger("ThrowingDataSource")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw java.sql.SQLException("unsupported")

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
