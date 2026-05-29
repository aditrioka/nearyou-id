package id.nearyou.app.admin.auth

import id.nearyou.app.common.clientIp
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Logout route — `POST /admin/logout` revokes the session, clears the
 * cookie, audit-logs, redirects to /admin/login.
 *
 * Per `admin-login-argon2-totp` spec Req "Logout POST revokes session,
 * clears cookie, and writes audit row".
 *
 * Wired INSIDE the `authenticate("admin") { ... }` block. An already-revoked
 * session is handled by the session middleware (which 302-redirects before
 * this handler runs) — the idempotency contract is preserved at the
 * middleware layer. As a state-changing handler it validates CSRF first via
 * [AdminCsrfGate.validateCsrf] (the shared CSRF "middleware").
 */
class AdminLogoutRoute(
    private val sessionRepository: SessionRepository,
    private val auditLogger: AdminAuditLogger,
) {
    fun install(route: Route) {
        route.post("/logout") {
            val principal =
                call.principal<AdminPrincipal>() ?: run {
                    // Defensive: the auth plugin should have populated
                    // this; if it didn't, just redirect.
                    call.respondRedirect("/admin/login", permanent = false)
                    return@post
                }

            // CSRF gate: every state-changing admin handler MUST validate
            // CSRF first. validateCsrf writes the 403 + admin_csrf_violation
            // audit row on failure; we just stop here.
            if (!AdminCsrfGate.validateCsrf(call, auditLogger)) {
                return@post
            }

            val ip = call.clientIp
            val userAgent = call.request.headers[HttpHeaders.UserAgent]

            sessionRepository.revoke(principal.sessionId)

            // Clear the cookie regardless of revoke result (idempotent).
            call.response.cookies.append(AdminLoginRoutes.buildClearSessionCookie())

            auditLogger.logLogout(
                adminId = principal.adminId,
                ip = ip,
                userAgent = userAgent,
            )

            call.respondRedirect("/admin/login", permanent = false)
        }
    }
}
