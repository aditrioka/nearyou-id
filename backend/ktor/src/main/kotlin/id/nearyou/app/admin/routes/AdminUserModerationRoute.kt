package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.moderation.SuspendOutcome
import id.nearyou.app.admin.moderation.UnbanOutcome
import id.nearyou.app.admin.moderation.UserModerationRepository
import id.nearyou.app.admin.moderation.UserModerationState
import id.nearyou.app.common.clientIp
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `GET /admin/users` + `POST /admin/users/{id}/suspend` + `POST
 * /admin/users/{id}/unban` — the admin panel's first state-changing surface
 * (`admin-user-moderation` capability, Admin #5).
 *
 * Wired INSIDE the `authenticate(ADMIN_AUTH_NAME) { ... }` block so the
 * `admin-login` session middleware gates it (302 → `/admin/login` when
 * unauthenticated), mirroring [adminActionsLog] / [adminIndex].
 *
 * Dual-mode rendering (mirrors `admin-actions-log-viewer` design D3):
 *  - `HX-Request: true` → respond with ONLY the `users-result.peb` fragment
 *    (the lookup form + layout stay put; the result region swaps in place).
 *  - otherwise → the full `users.peb` page (extends `layout.peb`) so the
 *    surface works without JavaScript.
 *
 * Each `POST` is a state-changing handler, so it validates CSRF FIRST
 * ([AdminCsrfGate.validateCsrf]), then the base role gate
 * ([AdminRoleGate.requireWriteRole]), then parses the `{id}` path UUID, then
 * reads the optional free-text `reason` from the cached `receiveParameters()`
 * (Ktor caches the body the CSRF gate may already have parsed). The
 * permanent-ban-unban higher-trust tier (owner/admin only) is enforced inside
 * the repository transaction (design D3).
 */
fun Route.adminUserModeration(
    repo: UserModerationRepository,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/users") {
        val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() }
        val user = q?.let { repo.lookup(it) }
        call.respondModeration(layout, q = q, user = user)
    }

    post("/users/{id}/suspend") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        val targetId =
            call.parseTargetId() ?: run {
                call.respondModeration(
                    layout,
                    q = null,
                    user = null,
                    message = MSG_INVALID_ID,
                    status = HttpStatusCode.BadRequest,
                )
                return@post
            }
        val reason = call.readReason()
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val outcome =
            repo.suspend(
                targetId = targetId,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            is SuspendOutcome.Applied -> call.respondActionRedirect(targetId)
            SuspendOutcome.RejectedSoftDeleted ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_SUSPEND_REJECTED_SOFT_DELETED,
                )
            SuspendOutcome.RejectedPermanentBan ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_SUSPEND_REJECTED_PERMANENT_BAN,
                )
            SuspendOutcome.NotFound ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = null,
                    message = MSG_USER_NOT_FOUND,
                )
        }
    }

    post("/users/{id}/unban") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        val targetId =
            call.parseTargetId() ?: run {
                call.respondModeration(
                    layout,
                    q = null,
                    user = null,
                    message = MSG_INVALID_ID,
                    status = HttpStatusCode.BadRequest,
                )
                return@post
            }
        val reason = call.readReason()
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val outcome =
            repo.unban(
                targetId = targetId,
                actingAdminRole = principal.role,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            UnbanOutcome.Applied -> call.respondActionRedirect(targetId)
            UnbanOutcome.NoOpNotBanned ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_UNBAN_NOOP_NOT_BANNED,
                )
            UnbanOutcome.ForbiddenPermanentBan ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_UNBAN_FORBIDDEN_PERMANENT_BAN,
                    status = HttpStatusCode.Forbidden,
                )
            UnbanOutcome.NotFound ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = null,
                    message = MSG_USER_NOT_FOUND,
                )
        }
    }
}

/** Parse the `{id}` path segment as a UUID; null when malformed (→ 400, no
 *  writes — per the malformed-path-identifier requirement). */
private fun ApplicationCall.parseTargetId(): UUID? = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/**
 * Read the OPTIONAL free-text `reason` form field AFTER the CSRF gate. Uses
 * [AdminCsrfGate.formParametersAfterValidation], which returns the body the
 * gate already parsed on the `_csrf`-form-field path (stashed in the call
 * attributes) or performs the first read on the header-token path — so the
 * `reason` survives the gate's body-consume either way. NULL when absent / blank.
 */
private suspend fun ApplicationCall.readReason(): String? =
    AdminCsrfGate.formParametersAfterValidation(this)[REASON_FIELD]?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Render the moderation surface: the `users-result.peb` fragment for an
 * `HX-Request`, otherwise the full `users.peb` page (which carries the CSRF
 * meta tag + the logout-form token via the authenticated layout, so its
 * suspend/unban `_csrf` hidden fields populate for the no-JS path).
 */
private suspend fun ApplicationCall.respondModeration(
    layout: AdminLayout,
    q: String?,
    user: UserModerationState?,
    message: String? = null,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val isHtmx = request.headers["HX-Request"] == "true"
    val model =
        buildMap<String, Any> {
            put("searched", q != null)
            q?.let { put("q", it) }
            user?.let { put("user", it.toViewMap()) }
            message?.let { put("message", it) }
            if (!isHtmx) {
                layout.putShellModel(
                    this@respondModeration,
                    this,
                    pageTitle = "Users",
                    activePath = "/admin/users",
                )
            }
        }
    val template = if (isHtmx) "users-result.peb" else "users.peb"
    respond(status, PebbleContent(template, model))
}

/** On a successful action, redirect back to the lookup view so the admin sees
 *  the updated state: `HX-Redirect` for HTMX, a 303 See Other otherwise. */
private suspend fun ApplicationCall.respondActionRedirect(targetId: UUID) {
    val url = "/admin/users?q=$targetId"
    if (request.headers["HX-Request"] == "true") {
        response.headers.append("HX-Redirect", url)
        respond(HttpStatusCode.OK)
    } else {
        response.headers.append(HttpHeaders.Location, url)
        respond(HttpStatusCode.SeeOther)
    }
}

/** Pre-format a user's moderation state into autoescaped display strings for
 *  the template (NULL `suspended_until` → em-dash). */
private fun UserModerationState.toViewMap(): Map<String, Any> {
    val suspendedUntilDisplay = suspendedUntil?.let { ISO_INSTANT.format(it) } ?: EM_DASH
    val statusLabel =
        when {
            !isBanned -> "Active"
            suspendedUntil != null -> "Suspended until $suspendedUntilDisplay"
            else -> "Permanently banned"
        }
    return mapOf(
        "id" to id.toString(),
        "username" to username,
        "isBanned" to isBanned,
        "suspendedUntil" to suspendedUntilDisplay,
        "statusLabel" to statusLabel,
        "deleted" to (deletedAt != null),
    )
}

private const val REASON_FIELD = "reason"
private const val EM_DASH = "—"
private val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

// User-facing informational messages (admin panel is English-only, matching
// the existing index/login/actions-log templates).
private const val MSG_INVALID_ID = "Invalid user id."
private const val MSG_USER_NOT_FOUND = "No matching user."
private const val MSG_SUSPEND_REJECTED_SOFT_DELETED = "Cannot suspend a deleted account."
private const val MSG_SUSPEND_REJECTED_PERMANENT_BAN =
    "User is permanently banned; suspending would downgrade the ban. No change made."
private const val MSG_UNBAN_NOOP_NOT_BANNED = "User is not banned. No change made."
private const val MSG_UNBAN_FORBIDDEN_PERMANENT_BAN =
    "Lifting a permanent ban requires owner or admin role."
