package id.nearyou.app.admin.auth

import id.nearyou.app.admin.auth.AdminAuditLogger.CsrfViolationReason
import id.nearyou.app.common.clientIp
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond

/**
 * CSRF middleware for the admin panel.
 *
 * Per `admin-login-argon2-totp` spec Req "CSRF middleware validates
 * X-CSRF-Token on state-changing requests" + Req "CSRF mismatch writes
 * admin_csrf_violation audit row" + design.md D6 + D7:
 *
 *  - Triggers on POST/PUT/PATCH/DELETE methods under the authenticated
 *    /admin/ subtree.
 *  - Accepts CSRF token from `X-CSRF-Token` header OR `_csrf` form field;
 *    header wins precedence when both are present.
 *  - Compares SHA-256(submitted) constant-time against the session's
 *    stored `csrf_token_hash` (already on the [AdminPrincipal]).
 *  - Mismatch / missing → 403 + audit row + halt pipeline.
 *  - GET/HEAD/OPTIONS pass through unconditionally (idempotent methods).
 *  - `POST /admin/login` is exempt (pre-session; CSRF cannot apply).
 *
 * The CSRF check is wired by calling [validateCsrf] from a route-level
 * interceptor inside the `authenticate("admin") { ... }` block. The
 * interceptor finishes the pipeline (`finish()`) on rejection so the route
 * handler does not run.
 */
object AdminCsrfGate {
    /**
     * Validate CSRF on the given [call]. Returns true when the call may
     * proceed to the route handler; false when the call was rejected with
     * 403 + audit. Caller is responsible for calling [finish()] in the
     * interceptor when this returns false.
     *
     * Per spec, exempt paths:
     *  - `GET` / `HEAD` / `OPTIONS` (idempotent)
     *  - `POST /admin/login` (pre-session; checked at the routing layer,
     *    not here — this gate does not run inside the `authenticate`
     *    block that wraps post-login routes)
     */
    suspend fun validateCsrf(
        call: ApplicationCall,
        auditLogger: AdminAuditLogger,
    ): Boolean {
        val method = call.request.local.method
        if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) {
            return true
        }

        // The auth plugin already redirected unauthenticated requests; if
        // we reach the CSRF gate, there MUST be a principal. Defensive
        // null-check + 403 if it's somehow missing.
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return false
            }

        // Read the submitted token: header first, then form field.
        val headerToken = call.request.headers[X_CSRF_TOKEN_HEADER]
        val (submittedToken, source) =
            when {
                !headerToken.isNullOrBlank() -> headerToken to TokenSource.HEADER
                isFormBody(call) -> {
                    val formValue = readFormToken(call)
                    if (formValue.isNullOrBlank()) {
                        null to TokenSource.MISSING
                    } else {
                        formValue to TokenSource.FORM_FIELD
                    }
                }
                else -> null to TokenSource.MISSING
            }

        if (submittedToken == null) {
            auditLogger.logCsrfViolation(
                adminId = principal.adminId,
                reason = CsrfViolationReason.MISSING_TOKEN,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
            call.respond(HttpStatusCode.Forbidden)
            return false
        }

        val submittedHash = HashUtil.sha256Hex(submittedToken)
        if (!HashUtil.constantTimeEqualHex(submittedHash, principal.csrfTokenHash)) {
            val mismatchReason =
                when (source) {
                    TokenSource.HEADER -> CsrfViolationReason.HEADER_MISMATCH
                    TokenSource.FORM_FIELD -> CsrfViolationReason.FORM_FIELD_MISMATCH
                    TokenSource.MISSING -> CsrfViolationReason.MISSING_TOKEN // unreachable
                }
            auditLogger.logCsrfViolation(
                adminId = principal.adminId,
                reason = mismatchReason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
            call.respond(HttpStatusCode.Forbidden)
            return false
        }

        return true
    }

    private fun isFormBody(call: ApplicationCall): Boolean {
        val contentType = call.request.contentType()
        return contentType.match("application/x-www-form-urlencoded") ||
            contentType.match("multipart/form-data")
    }

    private suspend fun readFormToken(call: ApplicationCall): String? =
        try {
            call.receiveParameters()[FORM_FIELD_NAME]
        } catch (_: Exception) {
            null
        }

    const val X_CSRF_TOKEN_HEADER = "X-CSRF-Token"
    const val FORM_FIELD_NAME = "_csrf"

    private enum class TokenSource { HEADER, FORM_FIELD, MISSING }
}
