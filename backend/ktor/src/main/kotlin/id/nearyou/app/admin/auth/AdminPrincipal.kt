package id.nearyou.app.admin.auth

import io.ktor.server.auth.Principal
import java.time.Instant
import java.util.UUID

/**
 * Authenticated admin's identity for downstream route handlers.
 *
 * Populated by [AdminAuthProvider] after validating the `__Host-admin_session`
 * cookie against `admin_sessions`. Carries:
 *
 *  - [adminId]: the matched admin's UUID (used for audit-row writes,
 *    role-aware authorization checks in future admin routes).
 *  - [sessionId]: the matched session row's UUID (used by the logout route
 *    to revoke this specific session).
 *  - [csrfTokenHash]: the session's stored `csrf_token_hash` (hex string;
 *    SHA-256 of the plaintext CSRF token). Compared constant-time against
 *    the request's `X-CSRF-Token` (or `_csrf` form field) by
 *    [AdminCsrfGate].
 *  - [role]: the admin's role (`owner`, `admin`, `moderator`, `read_only`)
 *    per V16 schema. Used by future admin routes that gate destructive
 *    actions by role; rendered as the layout identity-box role chip.
 *  - [displayName]: the admin's `display_name`, rendered in the layout
 *    identity box + the index greeting (admin-mockup-parity design.md D5).
 *  - [expiresAt]: the session's absolute 8h cap, for diagnostics + future
 *    UI surfacing.
 *  - [lastActiveAt]: the value AT request-arrival time, BEFORE the
 *    [AdminAuthProvider] refreshes it. Useful for diagnostics + future
 *    "log me out everywhere" UI.
 */
data class AdminPrincipal(
    val adminId: UUID,
    val sessionId: UUID,
    val csrfTokenHash: String,
    val role: String,
    val displayName: String,
    val expiresAt: Instant,
    val lastActiveAt: Instant,
) : Principal
