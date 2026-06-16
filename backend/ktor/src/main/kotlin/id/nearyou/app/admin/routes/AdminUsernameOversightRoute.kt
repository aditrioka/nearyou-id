package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.usernameoversight.FlagResolution
import id.nearyou.app.admin.usernameoversight.FlagResolveOutcome
import id.nearyou.app.admin.usernameoversight.FlagRow
import id.nearyou.app.admin.usernameoversight.FlagsCursor
import id.nearyou.app.admin.usernameoversight.HistoryCursor
import id.nearyou.app.admin.usernameoversight.HistoryRow
import id.nearyou.app.admin.usernameoversight.HoldReleaseOutcome
import id.nearyou.app.admin.usernameoversight.HoldRow
import id.nearyou.app.admin.usernameoversight.UsernameOversightRepository
import id.nearyou.app.admin.usernameoversight.UsernameOversightService
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
import java.net.URLEncoder
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `/admin/username-oversight` — the Premium Username Change Oversight surface
 * (`admin-premium-username-oversight` capability, admin mockup frame 22). Wired
 * INSIDE `authenticate(ADMIN_AUTH_NAME)` so the session middleware gates it.
 *
 *  - `GET` — render the three sections (flagged-candidate queue, read-only
 *    `username_history` viewer with search, 30-day-hold list). Available to ANY
 *    authenticated admin role (matching `adminReportQueue` / `adminReservedUsernames`;
 *    write controls render only for write-capable roles). Lenient `q` /
 *    `flags_cursor` / `history_cursor` parsing (malformed → first page / ignored,
 *    never 4xx/5xx). Dual-mode: HTMX fragment vs full page extending the base layout.
 *  - `POST /flags/{queue_id}/resolve` + `POST /holds/{history_id}/release` —
 *    state-changing, so each follows the gate order from [adminReportResolution]:
 *    CSRF FIRST ([AdminCsrfGate.validateCsrf] → 403 + `admin_csrf_violation`), THEN
 *    [AdminRoleGate.requireWriteRole], THEN parse the path UUID (malformed → 400, no
 *    write), THEN (for resolve) map the `resolution` field onto the server-side
 *    [FlagResolution] allowlist (out-of-allowlist → 400, no write — never a DB-CHECK
 *    5xx), THEN the service transaction. No-JS → 303 redirect preserving filters;
 *    HTMX → re-render the affected fragment.
 *
 * Expected no-op / in-band outcomes (already resolved, already released, not found,
 * out-of-scope trigger, accept-with-no-candidate, quota exceeded) re-render with a
 * message — never a 5xx.
 */
fun Route.adminUsernameOversight(
    repo: UsernameOversightRepository,
    service: UsernameOversightService,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/username-oversight") {
        call.respondOversight(repo, layout)
    }

    post("/username-oversight/flags/{queue_id}/resolve") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val queueId =
            call.parseUuid("queue_id") ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        // Server-side allowlist: only the two in-scope resolutions map to a
        // non-null enum. Anything else (content resolutions, `delete`, garbage) →
        // null → 400, no write, never reaching the resolution CHECK as a 5xx.
        val resolution =
            FlagResolution.fromWire(body[FIELD_RESOLUTION]?.trim()) ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_RESOLUTION)
                return@post
            }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val outcome =
            service.resolveFlag(
                queueId = queueId,
                resolution = resolution,
                actingAdminId = principal.adminId,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            FlagResolveOutcome.Applied -> call.respondWriteSuccess(repo, layout)
            FlagResolveOutcome.AcceptedNoCandidate -> call.respondOversight(repo, layout, message = MSG_ACCEPT_NO_CANDIDATE)
            FlagResolveOutcome.NoOpAlreadyResolved -> call.respondOversight(repo, layout, message = MSG_FLAG_ALREADY_RESOLVED)
            FlagResolveOutcome.NotFound -> call.respondOversight(repo, layout, message = MSG_FLAG_NOT_FOUND)
            FlagResolveOutcome.NotUsernameFlagged -> call.respondOversight(repo, layout, message = MSG_NOT_USERNAME_FLAGGED)
            FlagResolveOutcome.RateLimited -> call.respondOversight(repo, layout, message = MSG_FLAG_RATE_LIMITED)
        }
    }

    post("/username-oversight/holds/{history_id}/release") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        AdminCsrfGate.formParametersAfterValidation(call) // consume body (carries _csrf on the no-JS path)
        val historyId =
            call.parseUuid("history_id") ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val outcome =
            service.releaseHold(
                historyId = historyId,
                actingAdminId = principal.adminId,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            HoldReleaseOutcome.Applied -> call.respondWriteSuccess(repo, layout)
            HoldReleaseOutcome.NoOpAlreadyReleased -> call.respondOversight(repo, layout, message = MSG_HOLD_ALREADY_RELEASED)
            HoldReleaseOutcome.NotFound -> call.respondOversight(repo, layout, message = MSG_HOLD_NOT_FOUND)
            HoldReleaseOutcome.RateLimited -> call.respondOversight(repo, layout, message = MSG_HOLD_RATE_LIMITED)
        }
    }
}

/** Parse a path UUID segment; null when malformed (→ 400, no write). */
private fun ApplicationCall.parseUuid(name: String): UUID? = parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/**
 * Successful write response: re-render the swapped fragment for an `HX-Request`, or
 * 303-redirect to the listing for the no-JS path (PRG, so a refresh doesn't re-POST).
 * Filters are NOT carried on a write re-render (the listing re-queries the first
 * page) — mirroring [adminReservedUsernames]'s write success handling.
 */
private suspend fun ApplicationCall.respondWriteSuccess(
    repo: UsernameOversightRepository,
    layout: AdminLayout,
) {
    if (request.headers[HX_REQUEST] == "true") {
        respondOversight(repo, layout)
    } else {
        response.headers.append(HttpHeaders.Location, "/admin/username-oversight")
        respond(HttpStatusCode.SeeOther)
    }
}

/**
 * Render the oversight page: query the three sections (flagged queue + history with
 * the active `q`/cursors from the query string; holds), then respond with the
 * `username-oversight-table.peb` fragment for an `HX-Request` or the full
 * `username-oversight.peb` page otherwise. [message] surfaces an in-band outcome.
 */
private suspend fun ApplicationCall.respondOversight(
    repo: UsernameOversightRepository,
    layout: AdminLayout,
    message: String? = null,
) {
    val params = request.queryParameters
    val rawSearch = params["q"]?.trim()?.takeIf { it.isNotEmpty() }?.take(SEARCH_MAX)
    val flagsCursor = FlagsCursor.decode(params["flags_cursor"])
    val historyCursor = HistoryCursor.decode(params["history_cursor"])

    val flagsPage = repo.listPendingFlags(flagsCursor)
    val historyPage = repo.listHistory(rawSearch, historyCursor)
    val holds = repo.listHolds()

    val principal = principal<AdminPrincipal>()
    val canWrite = principal?.role in AdminRoleGate.WRITE_ROLES

    // "Older" links preserve the OTHER section's state (search + the other
    // cursor), so paging one list never resets the other.
    val flagsOlderUrl =
        flagsPage.nextCursor?.let { next ->
            oversightUrl(
                buildList {
                    add("flags_cursor" to next.encode())
                    rawSearch?.let { add("q" to it) }
                },
            )
        }
    val historyOlderUrl =
        historyPage.nextCursor?.let { next ->
            oversightUrl(
                buildList {
                    add("history_cursor" to next.encode())
                    rawSearch?.let { add("q" to it) }
                },
            )
        }

    val model =
        buildMap<String, Any> {
            put("flags", flagsPage.rows.map { it.toViewMap() })
            put("hasFlags", flagsPage.rows.isNotEmpty())
            put("history", historyPage.rows.map { it.toViewMap() })
            put("hasHistory", historyPage.rows.isNotEmpty())
            put("holds", holds.map { it.toViewMap() })
            put("hasHolds", holds.isNotEmpty())
            put("canWrite", canWrite)
            put("search", rawSearch ?: "")
            put("flagResolveCap", id.nearyou.app.admin.ratelimit.UsernameOversightActionRateLimiter.FLAG_RESOLVE_CAP)
            put("holdReleaseCap", id.nearyou.app.admin.ratelimit.UsernameOversightActionRateLimiter.HOLD_RELEASE_CAP)
            flagsOlderUrl?.let { put("flagsOlderUrl", it) }
            historyOlderUrl?.let { put("historyOlderUrl", it) }
            message?.let { put("message", it) }
            if (request.headers[HX_REQUEST] != "true") {
                layout.putShellModel(
                    this@respondOversight,
                    this,
                    pageTitle = "Username oversight",
                    activePath = "/admin/username-oversight",
                )
            }
        }
    val template = if (request.headers[HX_REQUEST] == "true") "username-oversight-table.peb" else "username-oversight.peb"
    respond(PebbleContent(template, model))
}

private fun oversightUrl(pairs: List<Pair<String, String>>): String =
    "/admin/username-oversight?" + pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }

private fun FlagRow.toViewMap(): Map<String, Any> {
    val m =
        mutableMapOf<String, Any>(
            "queueId" to queueId.toString(),
            "createdAt" to ISO_INSTANT.format(createdAt),
            "candidate" to (candidate ?: ""),
            "hasCandidate" to (candidate?.isNotBlank() ?: false),
            "resolveAction" to "/admin/username-oversight/flags/$queueId/resolve",
        )
    if (username != null) {
        m["username"] = username
        m["userLink"] = "/admin/users?q=${URLEncoder.encode(username, Charsets.UTF_8)}"
    } else {
        // Hard-deleted user: render the target_id as text with NO deep-link.
        m["targetId"] = targetId.toString()
    }
    return m
}

private fun HistoryRow.toViewMap(): Map<String, Any> {
    val m =
        mutableMapOf<String, Any>(
            "changedAt" to ISO_INSTANT.format(changedAt),
            "oldUsername" to oldUsername,
            "newUsername" to newUsername,
        )
    if (username != null) {
        m["username"] = username
        m["userLink"] = "/admin/users?q=${URLEncoder.encode(username, Charsets.UTF_8)}"
    } else {
        m["userId"] = userId.toString()
    }
    return m
}

private fun HoldRow.toViewMap(): Map<String, Any> {
    val m =
        mutableMapOf<String, Any>(
            "oldUsername" to oldUsername,
            "releasedAt" to ISO_INSTANT.format(releasedAt),
            "releaseAction" to "/admin/username-oversight/holds/$id/release",
        )
    if (username != null) {
        m["username"] = username
        m["userLink"] = "/admin/users?q=${URLEncoder.encode(username, Charsets.UTF_8)}"
    } else {
        m["userId"] = userId.toString()
    }
    return m
}

private const val HX_REQUEST = "HX-Request"
private const val FIELD_RESOLUTION = "resolution"
private const val SEARCH_MAX = 30

private val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

// English-only informational messages (matching the existing admin templates).
private const val MSG_INVALID_ID = "Invalid id."
private const val MSG_INVALID_RESOLUTION =
    "Invalid or out-of-scope resolution. Use accept_flagged_username or reject_flagged_username."
private const val MSG_FLAG_NOT_FOUND = "No matching flagged candidate. No change made."
private const val MSG_FLAG_ALREADY_RESOLVED = "That flag is already resolved. No change made."
private const val MSG_NOT_USERNAME_FLAGGED =
    "That queue item is not a username flag (handled on the Reports queue). No change made."
private const val MSG_ACCEPT_NO_CANDIDATE =
    "Flag resolved, but it carried no candidate to approve (legacy flag), so no override was written."
private const val MSG_FLAG_RATE_LIMITED = "Flag-resolution quota exceeded (10/hour). Try again later. No change made."
private const val MSG_HOLD_NOT_FOUND = "No matching held handle. No change made."
private const val MSG_HOLD_ALREADY_RELEASED = "That hold has already been released. No change made."
private const val MSG_HOLD_RATE_LIMITED = "Hold-release quota exceeded (5/hour). Try again later. No change made."
