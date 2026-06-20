package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.moderation.BanOutcome
import id.nearyou.app.admin.moderation.ShadowBanOutcome
import id.nearyou.app.admin.moderation.ShadowUnbanOutcome
import id.nearyou.app.admin.moderation.SuspendOutcome
import id.nearyou.app.admin.moderation.UnbanOutcome
import id.nearyou.app.admin.moderation.UserModerationRepository
import id.nearyou.app.admin.moderation.UserModerationState
import id.nearyou.app.admin.moderation.WarnOutcome
import id.nearyou.app.admin.ratelimit.DestructiveActionRateLimiter
import id.nearyou.app.admin.usermanagement.ProfileActionRow
import id.nearyou.app.admin.usermanagement.UserProfile
import id.nearyou.app.admin.usermanagement.UserProfileRepository
import id.nearyou.app.admin.usermanagement.UsernameChangeRow
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
    profileRepo: UserProfileRepository,
    rateLimiter: DestructiveActionRateLimiter,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/users") {
        val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() }
        val user = q?.let { repo.lookup(it) }
        call.respondModeration(layout, q = q, user = user)
    }

    // GET /admin/users/{id} — the per-user profile + history page
    // (admin-user-management). Read-only (no audit write, no mutation); any
    // admin role may view. Malformed {id} → 400 inline (never 500); a well-formed
    // UUID matching no user → inline empty-state (200, not 404).
    get("/users/{id}") {
        val targetId =
            call.parseTargetId() ?: run {
                call.respondProfile(layout, profile = null, status = HttpStatusCode.BadRequest, message = MSG_INVALID_ID)
                return@get
            }
        val profile = profileRepo.loadProfile(targetId)
        if (profile == null) {
            call.respondProfile(layout, profile = null, message = MSG_USER_NOT_FOUND)
            return@get
        }
        val principal = call.principal<AdminPrincipal>()
        val quotaUsed = principal?.let { rateLimiter.countInTrailingHour(it.adminId) } ?: 0
        call.respondProfile(
            layout,
            profile = profile,
            history =
                buildHistory(
                    profileRepo.loadAdminActionHistory(targetId),
                    profileRepo.loadUsernameHistory(targetId),
                ),
            quotaUsed = quotaUsed,
        )
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
            SuspendOutcome.RateLimited ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_RATE_LIMITED,
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

    // POST /admin/users/{id}/warn — issue a recorded + notified warning
    // (admin-user-moderation). CSRF → write-role → parse {id} (after the role
    // gate) → repo.warn. One user_warned audit row + one sanitized
    // account_action_applied notification; no users-state mutation; the free-text
    // reason is audit-only. Enforces the destructive-action cap.
    post("/users/{id}/warn") {
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
            repo.warn(
                targetId = targetId,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            WarnOutcome.Applied -> call.respondActionRedirect(targetId)
            WarnOutcome.TargetDeleted ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_WARN_TARGET_DELETED,
                )
            WarnOutcome.RateLimited ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_RATE_LIMITED,
                )
            WarnOutcome.NotFound ->
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

    // POST /admin/users/{id}/ban — permanent ban (admin-user-moderation).
    // CSRF → owner/admin gate (the higher-trust tier per the admin mockup; a
    // moderator/read_only 403s before any write) → parse {id} → repo.permanentBan.
    // One user_banned audit row + one sanitized account_action_applied
    // notification; sets is_banned=TRUE, suspended_until=NULL. Enforces the
    // destructive-action cap.
    post("/users/{id}/ban") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
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
            repo.permanentBan(
                targetId = targetId,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            BanOutcome.Applied -> call.respondActionRedirect(targetId)
            BanOutcome.RejectedSoftDeleted ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_BAN_REJECTED_SOFT_DELETED,
                )
            BanOutcome.NoOpAlreadyBanned ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_BAN_NOOP_ALREADY_BANNED,
                )
            BanOutcome.RateLimited ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_RATE_LIMITED,
                )
            BanOutcome.NotFound ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = null,
                    message = MSG_USER_NOT_FOUND,
                )
        }
    }

    // POST /admin/users/{id}/shadow-ban — shadow ban (admin-user-moderation).
    // CSRF → write-role gate (all write roles per the admin mockup) → parse {id}
    // → repo.shadowBan. One user_shadow_banned audit row; sets
    // is_shadow_banned=TRUE; NO notification (invisible by design). Enforces the
    // destructive-action cap.
    post("/users/{id}/shadow-ban") {
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
            repo.shadowBan(
                targetId = targetId,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            ShadowBanOutcome.Applied -> call.respondActionRedirect(targetId)
            ShadowBanOutcome.RejectedSoftDeleted ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_SHADOW_BAN_REJECTED_SOFT_DELETED,
                )
            ShadowBanOutcome.NoOpAlreadyShadowBanned ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_SHADOW_BAN_NOOP_ALREADY,
                )
            ShadowBanOutcome.RateLimited ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_RATE_LIMITED,
                )
            ShadowBanOutcome.NotFound ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = null,
                    message = MSG_USER_NOT_FOUND,
                )
        }
    }

    // POST /admin/users/{id}/shadow-unban — lift a shadow ban (restorative;
    // admin-user-moderation). CSRF → write-role gate → parse {id} →
    // repo.shadowUnban. One user_shadow_unbanned audit row; sets
    // is_shadow_banned=FALSE; NO notification; NOT rate-limited (restorative).
    post("/users/{id}/shadow-unban") {
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
            repo.shadowUnban(
                targetId = targetId,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            ShadowUnbanOutcome.Applied -> call.respondActionRedirect(targetId)
            ShadowUnbanOutcome.NoOpNotShadowBanned ->
                call.respondModeration(
                    layout,
                    q = targetId.toString(),
                    user = repo.lookup(targetId.toString()),
                    message = MSG_SHADOW_UNBAN_NOOP_NOT_SHADOW_BANNED,
                )
            ShadowUnbanOutcome.NotFound ->
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

/**
 * Render the per-user profile + history page (`user-profile.peb`,
 * `admin-user-management`). A null [profile] renders the inline empty-state /
 * error message. The full page carries the authenticated layout shell (so the
 * `csrfToken` populates the suspend/unban/warn `_csrf` hidden fields for the
 * no-JS path) + the live destructive-quota chip ([quotaUsed]/[quotaCap]).
 */
private suspend fun ApplicationCall.respondProfile(
    layout: AdminLayout,
    profile: UserProfile?,
    history: List<Map<String, Any>> = emptyList(),
    quotaUsed: Int = 0,
    message: String? = null,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val model =
        buildMap<String, Any> {
            profile?.let { put("profile", it.toProfileViewMap()) }
            put("history", history)
            put("quotaUsed", quotaUsed)
            put("quotaCap", DestructiveActionRateLimiter.DESTRUCTIVE_ACTION_CAP)
            message?.let { put("message", it) }
            layout.putShellModel(
                this@respondProfile,
                this,
                pageTitle = "User Profile",
                activePath = "/admin/users",
            )
        }
    respond(status, PebbleContent("user-profile.peb", model))
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

/** Pre-format the profile identity + moderation state for `user-profile.peb`
 *  (NULL instants → em-dash). Every value is Pebble-autoescaped on output. */
private fun UserProfile.toProfileViewMap(): Map<String, Any> {
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
        "displayName" to displayName,
        "isPremium" to isPremium,
        "subscriptionStatus" to subscriptionStatus,
        "createdAt" to (createdAt?.let { ISO_INSTANT.format(it) } ?: EM_DASH),
        "privateProfileOptIn" to privateProfileOptIn,
        "isBanned" to isBanned,
        "suspendedUntil" to suspendedUntilDisplay,
        "isShadowBanned" to isShadowBanned,
        // True only for a PERMANENT ban (is_banned with no expiry) — the ban
        // control is hidden when already permanently banned (a time-bound
        // suspension is still escalatable to permanent, so it does NOT set this).
        "isPermanentlyBanned" to (isBanned && suspendedUntil == null),
        "statusLabel" to statusLabel,
    )
}

/**
 * Merge the admin-action rows and the username-change rows into ONE
 * chronological timeline, newest-first across BOTH sources (spec: "The action-
 * history view merges admin_actions_log and username_history, newest-first" —
 * a combined order, not merely newest-first within one source). Each entry
 * carries a `kind` discriminator (`action` | `username`) the template branches
 * on; NULL reason/state → em-dash. Every value is Pebble-autoescaped on output.
 */
private fun buildHistory(
    actions: List<ProfileActionRow>,
    usernames: List<UsernameChangeRow>,
): List<Map<String, Any>> {
    val actionEntries =
        actions.map { a ->
            a.createdAt to
                mapOf<String, Any>(
                    "kind" to "action",
                    "at" to ISO_INSTANT.format(a.createdAt),
                    "adminDisplayName" to a.adminDisplayName,
                    "actionType" to a.actionType,
                    "reason" to (a.reason ?: EM_DASH),
                    "beforeState" to (a.beforeState ?: EM_DASH),
                    "afterState" to (a.afterState ?: EM_DASH),
                )
        }
    val usernameEntries =
        usernames.map { u ->
            u.changedAt to
                mapOf<String, Any>(
                    "kind" to "username",
                    "at" to ISO_INSTANT.format(u.changedAt),
                    "oldUsername" to u.oldUsername,
                    "newUsername" to u.newUsername,
                )
        }
    return (actionEntries + usernameEntries)
        .sortedByDescending { it.first }
        .map { it.second }
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
private const val MSG_WARN_TARGET_DELETED = "Cannot warn a deleted account."
private const val MSG_BAN_REJECTED_SOFT_DELETED = "Cannot ban a deleted account."
private const val MSG_BAN_NOOP_ALREADY_BANNED = "User is already permanently banned. No change made."
private const val MSG_SHADOW_BAN_REJECTED_SOFT_DELETED = "Cannot shadow-ban a deleted account."
private const val MSG_SHADOW_BAN_NOOP_ALREADY = "User is already shadow-banned. No change made."
private const val MSG_SHADOW_UNBAN_NOOP_NOT_SHADOW_BANNED = "User is not shadow-banned. No change made."
private const val MSG_RATE_LIMITED =
    "Destructive-action quota exceeded (20/hour). Try again later. No change made."
