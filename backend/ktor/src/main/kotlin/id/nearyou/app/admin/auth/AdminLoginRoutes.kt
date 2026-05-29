package id.nearyou.app.admin.auth

import id.nearyou.app.common.clientIp
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Login routes — `GET /admin/login` (render form) + `POST /admin/login`
 * (verify credentials + create session).
 *
 * Per `admin-login-argon2-totp` spec Req "Login form GET renders the
 * unauthenticated login page" + Req "Login POST verifies Argon2id password
 * and TOTP and creates a session on success" + Req "Login POST returns
 * no-enumeration response on every failure path" + Req "Login POST writes
 * audit row when an admin actor is identified" + Req
 * "__Host-admin_session cookie format meets security invariants".
 *
 * No-enumeration discipline:
 *  - Every failure mode (wrong email, wrong password, wrong TOTP,
 *    inactive admin, missing TOTP secret) returns the SAME response —
 *    HTTP 200 with the login form re-rendered and the generic error
 *    message.
 *  - Timing equalization: ALWAYS run Argon2id verify (against the
 *    sentinel hash if email not found) + ALWAYS run TOTP verify
 *    (against the sentinel secret if password mismatched / secret
 *    missing). This keeps wall-time roughly equal across failure modes.
 *
 * Per design.md D14: `email_not_found` does NOT write to
 * `admin_actions_log` (the V16 schema's `admin_id NOT NULL` invariant
 * forbids it). Instead it emits a structured INFO log line via
 * [AdminAuditLogger.logEmailNotFoundAttempt].
 */
class AdminLoginRoutes(
    private val adminUserRepository: AdminUserRepository,
    private val sessionRepository: SessionRepository,
    private val auditLogger: AdminAuditLogger,
    private val aesKeyProvider: () -> ByteArray,
    private val sessionLifetime: Duration = DEFAULT_SESSION_LIFETIME,
    private val clockSource: () -> Instant = Instant::now,
    private val tokenGenerator: () -> String = ::generateOpaqueToken,
) {
    fun install(route: Route) {
        route.get("/login") { handleGet() }
        route.post("/login") { handlePost() }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleGet() {
        if (hasValidSession(call.request.cookies[AdminAuthProvider.COOKIE_NAME])) {
            call.respondRedirect("/admin/", permanent = false)
            return
        }
        call.respond(PebbleContent("login.peb", model = emptyMap<String, Any>()))
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handlePost() {
        val params = call.receiveParameters()
        val email = params[FORM_EMAIL].orEmpty()
        val password = params[FORM_PASSWORD].orEmpty()
        val totp = params[FORM_TOTP].orEmpty()
        val ip = call.clientIp
        val userAgent = call.request.headers[HttpHeaders.UserAgent]

        val admin = adminUserRepository.findActiveByEmail(email)

        // Primary auth lookup returned null → the email is either missing OR
        // belongs to a deactivated admin (the `is_active = TRUE` filter on
        // findActiveByEmail excludes both from the happy path). Run sentinel
        // verifies FIRST for timing equalization, then a secondary audit-only
        // lookup distinguishes inactive-admin (→ audit row) from truly-missing
        // (→ INFO log) per spec reconciliation + design.md D14.
        if (admin == null) {
            PasswordHasher.verifyAgainstSentinel(password)
            TotpVerifier.verifyAgainstSentinel(totp)
            val inactiveAdmin = adminUserRepository.findByEmailAnyStatus(email)
            if (inactiveAdmin != null) {
                // Exists but is_active = FALSE.
                auditLogger.logFailure(
                    adminId = inactiveAdmin.id,
                    reason = AdminAuditLogger.LoginFailureReason.INACTIVE_ADMIN,
                    ip = ip,
                    userAgent = userAgent,
                )
            } else {
                // Truly missing — no admin actor, so no admin_actions_log row.
                auditLogger.logEmailNotFoundAttempt(email, ip, userAgent)
            }
            return renderError()
        }

        // totp_secret_missing path: defensive; the bootstrap procedure
        // produces a non-null totp_secret_encrypted, so this would only
        // fire on a partially-provisioned admin row.
        if (admin.totpSecretEncrypted == null) {
            PasswordHasher.verifyAgainstSentinel(password)
            TotpVerifier.verifyAgainstSentinel(totp)
            auditLogger.logFailure(
                adminId = admin.id,
                reason = AdminAuditLogger.LoginFailureReason.TOTP_SECRET_MISSING,
                ip = ip,
                userAgent = userAgent,
            )
            return renderError()
        }

        // Verify password.
        val passwordOk = PasswordHasher.verify(password, admin.passwordHash)
        if (!passwordOk) {
            // Run sentinel TOTP verify so the wall-time of a wrong-password
            // attempt matches a wrong-TOTP attempt.
            TotpVerifier.verifyAgainstSentinel(totp)
            auditLogger.logFailure(
                adminId = admin.id,
                reason = AdminAuditLogger.LoginFailureReason.PASSWORD_MISMATCH,
                ip = ip,
                userAgent = userAgent,
            )
            return renderError()
        }

        // Verify TOTP.
        val totpSecret =
            try {
                AesGcmCipher.decrypt(
                    ciphertext = admin.totpSecretEncrypted,
                    key = aesKeyProvider(),
                    aad = AesGcmCipher.adminUuidAsBytes(admin.id),
                )
            } catch (ex: Exception) {
                // Decryption failure — treat as totp_secret_missing (the
                // bootstrap procedure produces valid ciphertext; an
                // AEADBadTagException here signals storage corruption or
                // row-swap, not a normal login flow).
                log.warn(
                    "event=admin_totp_decrypt_failed admin_id={} exception=\"{}\"",
                    admin.id,
                    ex::class.simpleName,
                    ex,
                )
                TotpVerifier.verifyAgainstSentinel(totp)
                auditLogger.logFailure(
                    adminId = admin.id,
                    reason = AdminAuditLogger.LoginFailureReason.TOTP_SECRET_MISSING,
                    ip = ip,
                    userAgent = userAgent,
                )
                return renderError()
            }

        val totpOk = TotpVerifier.verify(totpSecret, totp)
        if (!totpOk) {
            auditLogger.logFailure(
                adminId = admin.id,
                reason = AdminAuditLogger.LoginFailureReason.TOTP_MISMATCH,
                ip = ip,
                userAgent = userAgent,
            )
            return renderError()
        }

        // Success path: generate fresh session token; derive CSRF
        // deterministically (per HashUtil.deriveCsrfFromSessionToken — see
        // design.md D6 + HashUtil rationale); INSERT admin_sessions; set
        // cookie; audit row; HX-Redirect.
        val now = clockSource()
        val expiresAt = now.plus(sessionLifetime)
        val sessionToken = tokenGenerator()
        val csrfToken = HashUtil.deriveCsrfFromSessionToken(sessionToken)
        val sessionTokenHash = HashUtil.sha256Hex(sessionToken)
        val csrfTokenHash = HashUtil.sha256Hex(csrfToken)

        val sessionId =
            sessionRepository.insert(
                adminId = admin.id,
                sessionTokenHash = sessionTokenHash,
                csrfTokenHash = csrfTokenHash,
                ip = ip,
                userAgent = userAgent,
                expiresAt = expiresAt,
            )

        call.response.cookies.append(
            buildSessionCookie(
                value = sessionToken,
                maxAgeSeconds = sessionLifetime.seconds.toInt(),
            ),
        )
        call.response.header("HX-Redirect", "/admin/")

        auditLogger.logSuccess(
            adminId = admin.id,
            sessionId = sessionId,
            expiresAt = expiresAt,
            lastActiveAt = now,
            ip = ip,
            userAgent = userAgent,
        )

        call.respond(HttpStatusCode.OK)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.renderError() {
        call.respond(
            HttpStatusCode.OK,
            PebbleContent(
                "login.peb",
                model = mapOf<String, Any>("errorMessage" to GENERIC_ERROR_MESSAGE),
            ),
        )
    }

    private fun hasValidSession(cookieValue: String?): Boolean {
        if (cookieValue.isNullOrBlank()) return false
        return try {
            val sessionTokenHash = HashUtil.sha256Hex(cookieValue)
            val session = sessionRepository.findBySessionHash(sessionTokenHash) ?: return false
            session.isActive(now = clockSource(), idleTimeout = AdminAuthProvider.DEFAULT_IDLE_TIMEOUT)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val FORM_EMAIL = "email"
        const val FORM_PASSWORD = "password"
        const val FORM_TOTP = "totp"
        const val GENERIC_ERROR_MESSAGE = "Email, password, or code is incorrect."

        val DEFAULT_SESSION_LIFETIME: Duration = Duration.ofHours(8)

        private val log = LoggerFactory.getLogger("id.nearyou.app.admin.auth.AdminLoginRoutes")

        private val secureRandom = SecureRandom()

        /**
         * Generate a 256-bit base64url-encoded opaque token (43 chars,
         * no padding) per spec Req "__Host-admin_session cookie format
         * meets security invariants" scenario "Cookie value is a 43-
         * character base64url string (256 bits of entropy)".
         */
        fun generateOpaqueToken(): String {
            val bytes = ByteArray(32) // 256 bits
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /**
         * Build the `__Host-admin_session` cookie per spec Req
         * "__Host-admin_session cookie format meets security invariants":
         * Secure, HttpOnly, SameSite=Strict, Path=/, NO Domain attribute
         * (required by the `__Host-` prefix).
         */
        fun buildSessionCookie(
            value: String,
            maxAgeSeconds: Int,
        ): Cookie =
            Cookie(
                name = AdminAuthProvider.COOKIE_NAME,
                value = value,
                encoding = CookieEncoding.RAW,
                maxAge = maxAgeSeconds,
                expires = null,
                domain = null,
                path = "/",
                secure = true,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            )

        /**
         * Build a clear-cookie variant of the session cookie (same shape
         * but empty value + Max-Age=0). Used by the logout handler to
         * tell the browser to drop the cookie.
         */
        fun buildClearSessionCookie(): Cookie =
            Cookie(
                name = AdminAuthProvider.COOKIE_NAME,
                value = "",
                encoding = CookieEncoding.RAW,
                maxAge = 0,
                expires = null,
                domain = null,
                path = "/",
                secure = true,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            )
    }
}
