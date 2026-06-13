package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.ratelimit.ReservedUsernameActionRateLimiter
import id.nearyou.app.admin.reservedusernames.AddOutcome
import id.nearyou.app.admin.reservedusernames.BulkAddReport
import id.nearyou.app.admin.reservedusernames.BulkOutcome
import id.nearyou.app.admin.reservedusernames.EditOutcome
import id.nearyou.app.admin.reservedusernames.RemoveOutcome
import id.nearyou.app.admin.reservedusernames.ReservedUsernameRow
import id.nearyou.app.admin.reservedusernames.ReservedUsernameValidation
import id.nearyou.app.admin.reservedusernames.ReservedUsernamesCursor
import id.nearyou.app.admin.reservedusernames.ReservedUsernamesQuery
import id.nearyou.app.admin.reservedusernames.ReservedUsernamesRepository
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

/**
 * `/admin/reserved-usernames` — the Reserved Usernames Editor (`admin-reserved-
 * usernames-editor` capability, admin mockup frame 21). Wired INSIDE
 * `authenticate(ADMIN_AUTH_NAME)` so the session middleware gates it.
 *
 *  - `GET` — list/filter/search (any authenticated admin role), dual-mode HTMX
 *    fragment + no-JS full page, mirroring [adminBlockRegistry].
 *  - `POST` (add / bulk / `{username}/edit-reason` / `{username}/remove`) —
 *    state-changing, so each follows the gate order from [adminReportResolution]
 *    (design D6): CSRF FIRST ([AdminCsrfGate.validateCsrf] → 403 +
 *    `admin_csrf_violation`), THEN the write-role gate
 *    ([AdminRoleGate.requireWriteRole]), THEN input validation (a blank/charset/
 *    length failure is a 400 with no write), THEN the repository transaction.
 *    Body is read exactly once via [AdminCsrfGate.formParametersAfterValidation].
 *
 * Expected no-op / in-band outcomes (already reserved, seed-protected, not found,
 * quota exceeded) re-render the list with a message — never a 5xx (design D10).
 * A successful add/edit/remove uses PRG (303 redirect for the no-JS path, table
 * fragment for HTMX); a bulk result re-renders with its three-bucket report.
 */
fun Route.adminReservedUsernames(
    repo: ReservedUsernamesRepository,
    rateLimiter: ReservedUsernameActionRateLimiter,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/reserved-usernames") {
        call.respondList(repo, rateLimiter, layout)
    }

    post("/reserved-usernames") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val principal = call.principal<AdminPrincipal>() ?: return@post call.respond(HttpStatusCode.Forbidden)
        val username = ReservedUsernameValidation.normalizeUsername(body[FIELD_USERNAME])
        val reason = ReservedUsernameValidation.validateReason(body[FIELD_REASON])
        if (username == null || reason == null) {
            call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ADD)
            return@post
        }
        when (repo.addSingle(username, reason, principal.adminId, call.clientIp, call.request.headers[HttpHeaders.UserAgent])) {
            AddOutcome.Added -> call.respondWriteSuccess(repo, rateLimiter, layout, MSG_ADDED)
            AddOutcome.AlreadyReserved -> call.respondList(repo, rateLimiter, layout, message = MSG_ALREADY_RESERVED)
            AddOutcome.RateLimited -> call.respondList(repo, rateLimiter, layout, message = MSG_QUOTA_EXCEEDED)
        }
    }

    post("/reserved-usernames/bulk") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val principal = call.principal<AdminPrincipal>() ?: return@post call.respond(HttpStatusCode.Forbidden)
        val csv = body[FIELD_CSV].orEmpty()
        if (csv.toByteArray(Charsets.UTF_8).size > MAX_CSV_BYTES || csv.lineSequence().count() > MAX_CSV_ROWS) {
            call.respond(HttpStatusCode.BadRequest, MSG_CSV_TOO_LARGE)
            return@post
        }
        when (val outcome = repo.bulkAdd(csv, principal.adminId, call.clientIp, call.request.headers[HttpHeaders.UserAgent])) {
            is BulkOutcome.Applied ->
                call.respondList(
                    repo,
                    rateLimiter,
                    layout,
                    message = bulkSummary(outcome),
                    report = outcome,
                )
            is BulkOutcome.RateLimited ->
                call.respondList(
                    repo,
                    rateLimiter,
                    layout,
                    message = bulkQuotaMessage(outcome),
                )
        }
    }

    post("/reserved-usernames/{username}/edit-reason") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val principal = call.principal<AdminPrincipal>() ?: return@post call.respond(HttpStatusCode.Forbidden)
        val username = call.pathUsername()
        val reason = ReservedUsernameValidation.validateReason(body[FIELD_REASON])
        if (username == null || reason == null) {
            call.respond(HttpStatusCode.BadRequest, MSG_INVALID_EDIT)
            return@post
        }
        when (repo.editReason(username, reason, principal.adminId, call.clientIp, call.request.headers[HttpHeaders.UserAgent])) {
            EditOutcome.Applied -> call.respondWriteSuccess(repo, rateLimiter, layout, MSG_UPDATED)
            EditOutcome.NotFound -> call.respondList(repo, rateLimiter, layout, message = MSG_NOT_FOUND)
            EditOutcome.SeedProtected -> call.respondList(repo, rateLimiter, layout, message = MSG_SEED_EDIT)
            EditOutcome.RateLimited -> call.respondList(repo, rateLimiter, layout, message = MSG_QUOTA_EXCEEDED)
        }
    }

    post("/reserved-usernames/{username}/remove") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        AdminCsrfGate.formParametersAfterValidation(call) // consume body (carries _csrf on the no-JS path)
        val principal = call.principal<AdminPrincipal>() ?: return@post call.respond(HttpStatusCode.Forbidden)
        val username = call.pathUsername() ?: return@post call.respond(HttpStatusCode.BadRequest, MSG_INVALID_REMOVE)
        when (repo.remove(username, principal.adminId, call.clientIp, call.request.headers[HttpHeaders.UserAgent])) {
            RemoveOutcome.Applied -> call.respondWriteSuccess(repo, rateLimiter, layout, MSG_REMOVED)
            RemoveOutcome.NotFound -> call.respondList(repo, rateLimiter, layout, message = MSG_NOT_FOUND)
            RemoveOutcome.SeedProtected -> call.respondList(repo, rateLimiter, layout, message = MSG_SEED_REMOVE)
            RemoveOutcome.RateLimited -> call.respondList(repo, rateLimiter, layout, message = MSG_QUOTA_EXCEEDED)
        }
    }
}

/** Normalized path `{username}` (lowercased so a case-variant matches the stored
 *  lowercase row); null when the segment is missing/blank. */
private fun ApplicationCall.pathUsername(): String? = parameters["username"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/**
 * Successful add/edit/remove response: re-render the table fragment with
 * [message] for an `HX-Request` (htmx swaps it in place), or 303-redirect to the
 * list for the no-JS path (PRG, so a refresh doesn't re-POST). Mirrors
 * [adminReportResolution]'s success handling — a blanket redirect would make
 * htmx follow it and nest the full page into the table target.
 */
private suspend fun ApplicationCall.respondWriteSuccess(
    repo: ReservedUsernamesRepository,
    rateLimiter: ReservedUsernameActionRateLimiter,
    layout: AdminLayout,
    message: String,
) {
    if (request.headers[HX_REQUEST] == "true") {
        respondList(repo, rateLimiter, layout, message = message)
    } else {
        // PRG: 303 See Other → the browser GETs the list (mirrors
        // adminReportResolution; a refresh then doesn't re-POST).
        response.headers.append(HttpHeaders.Location, "/admin/reserved-usernames")
        respond(HttpStatusCode.SeeOther)
    }
}

/**
 * Render the list: re-query the first page (filters from the query string on GET;
 * unfiltered on a write re-render), then respond with the `reserved-usernames-
 * table.peb` fragment for an `HX-Request` or the full `reserved-usernames.peb`
 * page otherwise. [message] surfaces an in-band outcome; [report] adds the bulk
 * three-bucket summary.
 */
private suspend fun ApplicationCall.respondList(
    repo: ReservedUsernamesRepository,
    rateLimiter: ReservedUsernameActionRateLimiter,
    layout: AdminLayout,
    message: String? = null,
    report: BulkOutcome.Applied? = null,
) {
    val params = request.queryParameters
    val sourceFilter = params["source"]?.takeIf { it == SOURCE_SEED || it == SOURCE_ADMIN_ADDED }
    val rawSearch = params["q"]?.trim()?.takeIf { it.isNotEmpty() }?.take(SEARCH_MAX)
    val cursor = ReservedUsernamesCursor.decode(params["cursor"])

    val page =
        repo.query(
            ReservedUsernamesQuery(source = sourceFilter, search = rawSearch, cursor = cursor),
        )

    val activeFilters =
        buildList {
            sourceFilter?.let { add("source" to it) }
            rawSearch?.let { add("q" to it) }
        }
    val olderUrl =
        page.nextCursor?.let { next ->
            val pairs = activeFilters + ("cursor" to next.encode())
            "/admin/reserved-usernames?" + pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        }

    val principal = principal<AdminPrincipal>()
    val quotaUsed = principal?.let { rateLimiter.countInTrailingHour(it.adminId) } ?: 0

    val model =
        buildMap<String, Any> {
            put("rows", page.rows.map { it.toViewMap() })
            put("hasRows", page.rows.isNotEmpty())
            put("filters", activeFilters.toMap())
            put("quotaUsed", quotaUsed)
            put("quotaCap", ReservedUsernameActionRateLimiter.RESERVED_USERNAME_ACTION_CAP)
            olderUrl?.let { put("olderUrl", it) }
            message?.let { put("message", it) }
            report?.let { put("report", it.report.toViewMap()) }
            if (request.headers[HX_REQUEST] != "true") {
                layout.putShellModel(this@respondList, this, pageTitle = "Reserved usernames", activePath = "/admin/reserved-usernames")
            }
        }
    val template = if (request.headers[HX_REQUEST] == "true") "reserved-usernames-table.peb" else "reserved-usernames.peb"
    respond(PebbleContent(template, model))
}

private fun ReservedUsernameRow.toViewMap(): Map<String, Any> {
    val enc = URLEncoder.encode(username, Charsets.UTF_8)
    return mapOf(
        "username" to username,
        "reason" to reason,
        "source" to source,
        "isSeed" to (source == SOURCE_SEED),
        "createdAt" to ISO_INSTANT.format(createdAt),
        "updatedAt" to ISO_INSTANT.format(updatedAt),
        "editAction" to "/admin/reserved-usernames/$enc/edit-reason",
        "removeAction" to "/admin/reserved-usernames/$enc/remove",
    )
}

/** Flatten the bulk report into template-friendly counts + lists. */
private fun BulkAddReport.toViewMap(): Map<String, Any> =
    mapOf(
        "addedCount" to added.size,
        "skippedDuplicateCount" to skippedDuplicate.size,
        "skippedInvalidCount" to skippedInvalid.size,
        "added" to added,
        "skippedDuplicate" to skippedDuplicate,
        "skippedInvalid" to skippedInvalid.map { mapOf("line" to it.line, "problem" to it.problem) },
    )

private fun bulkSummary(o: BulkOutcome.Applied): String =
    "${o.report.added.size} added, " +
        "${o.report.skippedDuplicate.size} skipped (duplicate), " +
        "${o.report.skippedInvalid.size} skipped (invalid)."

private fun bulkQuotaMessage(o: BulkOutcome.RateLimited): String =
    "Upload of ${o.wouldAdd} would exceed your 100/hour quota " +
        "(${o.currentCount} used). Nothing was added."

private const val HX_REQUEST = "HX-Request"
private const val FIELD_USERNAME = "username"
private const val FIELD_REASON = "reason"
private const val FIELD_CSV = "csv"
private const val SOURCE_SEED = "seed_system"
private const val SOURCE_ADMIN_ADDED = "admin_added"
private const val SEARCH_MAX = 30
private const val MAX_CSV_ROWS = 1000
private const val MAX_CSV_BYTES = 256 * 1024

private val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

private const val MSG_ADDED = "Reserved username added."
private const val MSG_UPDATED = "Reason updated."
private const val MSG_REMOVED = "Reserved username removed."
private const val MSG_INVALID_ADD = "Username must be 1-30 chars of a-z, 0-9, '.', '_' and reason must be 1-64 chars."
private const val MSG_INVALID_EDIT = "Reason must be 1-64 characters."
private const val MSG_INVALID_REMOVE = "Invalid username."
private const val MSG_ALREADY_RESERVED = "That username is already reserved. No change made."
private const val MSG_SEED_EDIT = "System-seed entries cannot be edited. No change made."
private const val MSG_SEED_REMOVE = "System-seed entries cannot be removed. No change made."
private const val MSG_NOT_FOUND = "No matching reserved username. No change made."
private const val MSG_QUOTA_EXCEEDED = "Reserved-username quota exceeded (100/hour). Try again later. No change made."
private const val MSG_CSV_TOO_LARGE = "CSV too large (max 1000 rows / 256 KB)."
