package id.nearyou.app.admin.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.response.respond

/**
 * Base role gate for state-changing admin handlers (`admin-user-moderation`
 * spec Req "State-changing actions are role-gated …").
 *
 * The three OPERATIONAL roles — `owner`, `admin`, `moderator` — may take
 * routine moderation write actions (suspend, and lifting a time-bound
 * suspension). A `read_only` admin is authenticated but unauthorized, so it is
 * rejected with HTTP 403 (NOT a redirect — the session is valid). This is the
 * panel's first role-gated WRITE; contrast `admin-actions-log-viewer`, whose
 * reads are available to ALL roles including `read_only`.
 *
 * This gate is the REUSABLE base check for any future admin write. It runs
 * AFTER [AdminCsrfGate.validateCsrf]. The higher-trust permanent-ban-unban
 * tier (owner/admin only, design D3) is NOT enforced here — it depends on the
 * target's current state, so it is checked inside the unban transaction after
 * the `SELECT … FOR UPDATE`, so a moderator rejection writes nothing.
 */
object AdminRoleGate {
    /** Roles permitted to take a routine moderation write action. */
    val WRITE_ROLES: Set<String> = setOf("owner", "admin", "moderator")

    /** The higher-trust tier permitted to take owner/admin-only actions
     *  (permanent ban, chat-message redaction). Excludes `moderator` +
     *  `read_only`. */
    val OWNER_ADMIN_ROLES: Set<String> = setOf("owner", "admin")

    /**
     * Returns true when the [call]'s authenticated admin may take a write
     * action; otherwise responds 403 and returns false (the caller just
     * returns early). A missing principal (defensive — the auth plugin should
     * have populated it) is also 403.
     */
    suspend fun requireWriteRole(call: ApplicationCall): Boolean {
        val principal = call.principal<AdminPrincipal>()
        if (principal == null || principal.role !in WRITE_ROLES) {
            call.respond(HttpStatusCode.Forbidden)
            return false
        }
        return true
    }

    /**
     * Owner/admin-tier gate (sibling to [requireWriteRole], narrower set). Used
     * by surfaces that are owner/admin-only END TO END — notably
     * `admin-chat-message-redaction`, whose GET page discloses private 1:1 chat
     * content, so a `moderator`/`read_only` session is 403'd at BOTH the GET
     * page and the POST write (the content disclosure is limited to admins who
     * can act). This is the same role-gate mechanism as [requireWriteRole] with
     * a narrower role set — NOT a new pattern. Runs AFTER
     * [AdminCsrfGate.validateCsrf] on state-changing handlers.
     */
    suspend fun requireOwnerOrAdmin(call: ApplicationCall): Boolean {
        val principal = call.principal<AdminPrincipal>()
        if (principal == null || principal.role !in OWNER_ADMIN_ROLES) {
            call.respond(HttpStatusCode.Forbidden)
            return false
        }
        return true
    }
}
