package id.nearyou.app.admin.auth

import id.nearyou.app.common.clientIp
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.infra.otel.IpHasher
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
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
 * Throttle (#532): at most [CREDENTIAL_CHECK_PERMITS] credential checks run at once
 * (excess shed), and before any lookup the POST passes three sliding-window tiers on
 * the shared [RateLimiter] — per client IP 10/15 min, per submitted email 5/15 min,
 * per email 20/24 h, counting failed attempts only. A throttled or shed attempt is a
 * 429 + `Retry-After` re-render that is identical for existing and phantom emails,
 * logged via [AdminAuditLogger.logLoginThrottled] (no audit row: no actor yet).
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
    private val csrfHmacKeyProvider: () -> ByteArray,
    private val rateLimiter: RateLimiter,
    // Process-wide: one AdminLoginRoutes per application. Injectable so a test can
    // hold every permit and observe the shed path.
    private val credentialGate: Semaphore = Semaphore(CREDENTIAL_CHECK_PERMITS),
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

        // Oversized-input guard (spec Req "Login input handling tolerates
        // Unicode and rejects malformed shapes safely" scenario "Oversized
        // email field is rejected without reaching auth handler"): reject
        // absurd field lengths with 400 BEFORE any admin_users SELECT or
        // audit-row write. MAX_FIELD_LENGTH is generous enough for any real
        // Unicode email/password (RFC 5321 caps email at 320 chars). A
        // malformed-request 400 is distinct from the 200 no-enumeration
        // error, which is correct — no-enumeration governs credential
        // failure modes, not malformed requests.
        if (email.length > MAX_FIELD_LENGTH || password.length > MAX_FIELD_LENGTH || totp.length > MAX_FIELD_LENGTH) {
            call.respond(HttpStatusCode.BadRequest)
            return
        }

        // Credential-check concurrency gate (#532, design D11): at most
        // CREDENTIAL_CHECK_PERMITS Argon2id verifies in flight per instance; a request
        // that cannot get a permit within CREDENTIAL_GATE_WAIT_MS is shed with a 429
        // before it costs any Redis, DB or hash work. The IP tier is spoofable until
        // the host sits behind Cloudflare (#530), so this — not the rate tiers — is
        // what keeps a spoofed-IP spray from exhausting the ~128 MiB heap (19 MiB per
        // verify) or both vCPUs. Semaphore.acquire releases a permit it was handed if
        // the timeout cancels it at that instant, so a shed request never leaks one.
        val admitted = withTimeoutOrNull(CREDENTIAL_GATE_WAIT_MS) { credentialGate.acquire() } != null
        if (!admitted) {
            return respondThrottled(Throttle(AXIS_CONCURRENCY, SHED_RETRY_AFTER_SECONDS), email, ip, userAgent)
        }
        try {
            checkCredentials(email, password, totp, ip, userAgent)
        } finally {
            credentialGate.release()
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.checkCredentials(
        email: String,
        password: String,
        totp: String,
        ip: String,
        userAgent: String?,
    ) {
        // Throttle (#532) BEFORE any admin_users lookup or Argon2id/TOTP work, so a
        // spray costs neither CPU nor DB, and a 429 depends only on counters keyed by
        // the client IP + the submitted string — never on whether it is an admin
        // (no-enumeration). Fail-open rides the limiter's own fail-soft contract.
        val tiers = throttleTiers(ip, email)
        val throttle = firstExhausted(tiers)
        if (throttle != null) return respondThrottled(throttle, email, ip, userAgent)

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
        val csrfToken = HashUtil.deriveCsrfFromSessionToken(sessionToken, csrfHmacKeyProvider())
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

        auditLogger.logSuccess(
            adminId = admin.id,
            sessionId = sessionId,
            expiresAt = expiresAt,
            lastActiveAt = now,
            ip = ip,
            userAgent = userAgent,
        )

        // Only FAILED attempts count (design D5): a successful login hands back the
        // slot it took in every tier, so the operator's own logins and the staging
        // smoke scripts never burn throttle budget, and the counters reveal nothing
        // about legitimate activity on an address.
        tiers.forEach { rateLimiter.releaseMostRecent(admin.id, it.key) }

        // Dual-mode navigation on success:
        //  - HTMX-driven submit (HX-Request: true) → 200 + HX-Redirect, the
        //    canonical HTMX redirect (HTMX reads the header + full-reloads).
        //  - Plain browser form POST (no HX-Request) → 303 See Other +
        //    Location, the standard POST-redirect-GET. HX-Redirect is an
        //    HTMX-only header a plain browser ignores, so without this a
        //    real browser login would land on a blank 200 body despite the
        //    cookie being set. login.peb is a plain form (progressive
        //    enhancement: login works without JS), so the 303 path is the
        //    normal one; the HX-Redirect path serves any future hx-post.
        if (call.request.headers[HX_REQUEST_HEADER] == "true") {
            call.response.header(HX_REDIRECT_HEADER, "/admin/")
            call.respond(HttpStatusCode.OK)
        } else {
            call.response.header(HttpHeaders.Location, "/admin/")
            call.respond(HttpStatusCode.SeeOther)
        }
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

    /**
     * The three throttle tiers in evaluation order (IP → email burst → email lockout).
     * All keys are rolling windows (no `_day` scope), so no computeTTLToNextReset; the
     * IP value is the [IpHasher] hash, the email value the same `email_hash` the D14
     * log line carries.
     */
    private fun throttleTiers(
        ip: String,
        email: String,
    ): List<Tier> {
        val hashedIp = IpHasher.hash(ip)
        val emailHash = AdminAuditLogger.sha256Base64Url(email)
        return listOf(
            Tier(AXIS_IP, "{scope:rate_admin_login}:{ip:$hashedIp}", IP_CAP, BURST_WINDOW),
            Tier(AXIS_EMAIL, "{scope:rate_admin_login}:{email:$emailHash}", EMAIL_CAP, BURST_WINDOW),
            Tier(AXIS_EMAIL_LOCKOUT, "{scope:rate_admin_login_lockout}:{email:$emailHash}", LOCKOUT_CAP, LOCKOUT_WINDOW),
        )
    }

    /**
     * The first exhausted tier, or null when all admit. Later tiers are not consulted
     * once one rejects, so an IP-throttled flood spends none of the target email's
     * slots (design D5); a throttled attempt has used one slot in each EARLIER tier.
     */
    private fun firstExhausted(tiers: List<Tier>): Throttle? {
        for (tier in tiers) {
            val outcome = rateLimiter.tryAcquireByKey(tier.key, tier.capacity, tier.window)
            if (outcome is RateLimiter.Outcome.RateLimited) return Throttle(tier.axis, outcome.retryAfterSeconds)
        }
        return null
    }

    /** 429 + `Retry-After` + the login page with the throttle banner; WARN-logged, no audit row. */
    private suspend fun io.ktor.server.routing.RoutingContext.respondThrottled(
        throttle: Throttle,
        email: String,
        ip: String,
        userAgent: String?,
    ) {
        auditLogger.logLoginThrottled(throttle.axis, email, ip, userAgent, throttle.retryAfterSeconds)
        call.response.header(HttpHeaders.RetryAfter, throttle.retryAfterSeconds.toString())
        val minutes = (throttle.retryAfterSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
        call.respond(
            HttpStatusCode.TooManyRequests,
            PebbleContent("login.peb", model = mapOf<String, Any>("errorMessage" to throttledMessage(minutes))),
        )
    }

    private class Tier(val axis: String, val key: String, val capacity: Int, val window: Duration)

    private class Throttle(val axis: String, val retryAfterSeconds: Long)

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
        const val HX_REQUEST_HEADER = "HX-Request"
        const val HX_REDIRECT_HEADER = "HX-Redirect"
        const val GENERIC_ERROR_MESSAGE = "Email, password, or code is incorrect."

        /** Max accepted length for a login form field (generous vs RFC 5321's
         *  320-char email cap); oversized fields are 400-rejected pre-DB. */
        const val MAX_FIELD_LENGTH = 1024

        // Login throttle tiers (#532, design D6): per IP, per submitted email (burst),
        // and the sustained per-email lockout ("progressive lockout": a burst recovers
        // in 15 min, a sustained attack on one account stays locked up to a day).
        const val IP_CAP = 10
        const val EMAIL_CAP = 5
        const val LOCKOUT_CAP = 20
        val BURST_WINDOW: Duration = Duration.ofMinutes(15)
        val LOCKOUT_WINDOW: Duration = Duration.ofHours(24)
        const val AXIS_IP = "ip"
        const val AXIS_EMAIL = "email"
        const val AXIS_EMAIL_LOCKOUT = "email_lockout"

        // Credential-check concurrency gate (design D11): 2 permits = the 2 vCPUs and
        // 2 × 19 MiB of Argon2 memory, well inside the ~128 MiB heap. A request waits
        // at most CREDENTIAL_GATE_WAIT_MS (a few verifies' worth) before it is shed.
        const val CREDENTIAL_CHECK_PERMITS = 2
        const val CREDENTIAL_GATE_WAIT_MS = 2_000L
        const val SHED_RETRY_AFTER_SECONDS = 60L
        const val AXIS_CONCURRENCY = "concurrency"
        private const val SECONDS_PER_MINUTE = 60L

        fun throttledMessage(minutes: Long): String = "Too many login attempts. Try again in $minutes min."

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
