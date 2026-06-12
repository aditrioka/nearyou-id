package id.nearyou.app.admin.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.response.respondRedirect
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Custom Ktor [AuthenticationProvider] for the admin panel's cookie-based
 * session.
 *
 * Per `admin-login-argon2-totp` spec Req "Session middleware validates the
 * cookie on every authenticated request":
 *
 *  1. Read `__Host-admin_session` cookie. Absent → 302 to `/admin/login`.
 *  2. SHA-256 the cookie value (hex). Look up `admin_sessions` row.
 *     Missing → 302.
 *  3. Validate the session: `revoked_at IS NULL`, `expires_at > NOW()`,
 *     `last_active_at > NOW() - INTERVAL '30 minutes'`. Any fail → 302
 *     (do NOT extend the session; per spec scenario "Session middleware
 *     does not extend an idle-timed-out session via last_active_at
 *     refresh").
 *  4. Look up `admin_users` by `admin_id` for the role. If the admin is
 *     no longer active, 302 (defense beyond spec — mid-session
 *     deactivation invalidates the session).
 *  5. Refresh `last_active_at` to NOW().
 *  6. Populate [AdminPrincipal] with adminId + sessionId + csrfTokenHash
 *     + role + expiresAt + lastActiveAt.
 *  7. **Fail closed on DB exception** (per spec scenario "Session
 *     middleware fails CLOSED on DB exception" + design.md D16). Catch
 *     and 302 + WARN log; never proceed to the route handler with a
 *     null principal.
 *
 * Install via [AuthenticationConfig.adminAuth]; wrap routes with
 * `authenticate("admin") { ... }`.
 */
class AdminAuthProvider(
    config: Config,
) : AuthenticationProvider(config) {
    private val sessionRepository = config.sessionRepository
    private val adminUserRepository = config.adminUserRepository
    private val idleTimeout = config.idleTimeout
    private val clockSource = config.clockSource

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val cookieValue = call.request.cookies[COOKIE_NAME]

        if (cookieValue.isNullOrBlank()) {
            redirectToLogin(call, context, AuthenticationFailedCause.NoCredentials, clearCookie = false)
            return
        }

        try {
            val sessionTokenHash = HashUtil.sha256Hex(cookieValue)
            val session = sessionRepository.findBySessionHash(sessionTokenHash)
            if (session == null) {
                redirectToLogin(call, context, AuthenticationFailedCause.InvalidCredentials, clearCookie = true)
                return
            }
            if (!session.isActive(now = clockSource(), idleTimeout = idleTimeout)) {
                redirectToLogin(call, context, AuthenticationFailedCause.InvalidCredentials, clearCookie = true)
                return
            }
            val admin = adminUserRepository.findById(session.adminId)
            if (admin == null || !admin.isActive) {
                // Defense beyond spec: mid-session deactivation or row
                // hard-delete invalidates the session.
                redirectToLogin(call, context, AuthenticationFailedCause.InvalidCredentials, clearCookie = true)
                return
            }

            sessionRepository.refreshLastActive(session.id)

            context.principal(
                AdminPrincipal(
                    adminId = admin.id,
                    sessionId = session.id,
                    csrfTokenHash = session.csrfTokenHash,
                    role = admin.role,
                    expiresAt = session.expiresAt,
                    lastActiveAt = session.lastActiveAt,
                ),
            )
        } catch (ex: Exception) {
            // Per spec scenario "Session middleware fails CLOSED on DB
            // exception" + design.md D16: any thrown exception → redirect
            // to login, log WARN. NEVER proceed without a valid principal.
            log.warn(
                "event=admin_session_middleware_db_failure path=\"{}\" exception=\"{}\"",
                call.request.local.uri,
                ex::class.simpleName,
                ex,
            )
            // Transient failure: do NOT clear the cookie — the session row may be
            // perfectly valid once the DB recovers.
            redirectToLogin(call, context, AuthenticationFailedCause.Error("session validation failed"), clearCookie = false)
        }
    }

    private suspend fun redirectToLogin(
        call: ApplicationCall,
        context: AuthenticationContext,
        cause: AuthenticationFailedCause,
        clearCookie: Boolean,
    ) {
        context.challenge(COOKIE_NAME, cause) { challenge, _ ->
            // Spec § "Session middleware validates the cookie on every authenticated
            // request": a presented-but-invalid cookie (forged / expired / idle /
            // deactivated admin) SHALL be cleared alongside the redirect.
            if (clearCookie) {
                call.response.cookies.append(AdminLoginRoutes.buildClearSessionCookie())
            }
            call.respondRedirect(LOGIN_PATH, permanent = false)
            challenge.complete()
        }
    }

    class Config(
        name: String,
    ) : AuthenticationProvider.Config(name) {
        lateinit var sessionRepository: SessionRepository
        lateinit var adminUserRepository: AdminUserRepository
        var idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT
        var clockSource: () -> Instant = Instant::now
    }

    companion object {
        const val COOKIE_NAME = "__Host-admin_session"
        const val LOGIN_PATH = "/admin/login"
        val DEFAULT_IDLE_TIMEOUT: Duration = Duration.ofMinutes(30)

        private val log = LoggerFactory.getLogger("id.nearyou.app.admin.auth.AdminAuthProvider")
    }
}

/**
 * Returns true iff this session is currently usable.
 *
 *  - `revoked_at IS NULL`
 *  - `expires_at > now`
 *  - `last_active_at > now - idleTimeout`
 *
 * Per spec Req "Session middleware validates the cookie on every
 * authenticated request" — the active predicate.
 */
fun SessionRow.isActive(
    now: Instant,
    idleTimeout: Duration,
): Boolean {
    if (revokedAt != null) return false
    if (!expiresAt.isAfter(now)) return false
    val idleThreshold = now.minus(idleTimeout)
    if (!lastActiveAt.isAfter(idleThreshold)) return false
    return true
}

/**
 * DSL builder for installing [AdminAuthProvider] into the Authentication
 * plugin. Usage:
 *
 * ```kotlin
 * install(Authentication) {
 *     adminAuth("admin") {
 *         sessionRepository = sessionRepo
 *         adminUserRepository = adminUserRepo
 *     }
 * }
 * ```
 */
fun AuthenticationConfig.adminAuth(
    name: String,
    configure: AdminAuthProvider.Config.() -> Unit,
) {
    val config = AdminAuthProvider.Config(name).apply(configure)
    register(AdminAuthProvider(config))
}
