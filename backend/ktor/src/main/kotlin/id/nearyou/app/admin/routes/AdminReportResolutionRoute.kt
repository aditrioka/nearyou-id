package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.auth.HashUtil
import id.nearyou.app.admin.reportqueue.QueueResolution
import id.nearyou.app.admin.reportqueue.QueueResolutionOutcome
import id.nearyou.app.admin.reportqueue.ReportDecision
import id.nearyou.app.admin.reportqueue.ReportDecisionOutcome
import id.nearyou.app.admin.reportqueue.ReportQueueRepository
import id.nearyou.app.admin.reportqueue.ReportResolutionRepository
import id.nearyou.app.admin.reportqueue.toViewMap
import id.nearyou.app.common.clientIp
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.util.UUID

/**
 * `POST /admin/reports/{id}/resolve` + `POST /admin/moderation-queue/{id}/resolve`
 * — the in-panel report-queue resolution actions (`admin-report-queue-
 * resolution-actions`, the write-back fast-follow to the read-only Report Queue
 * viewer). Both are wired INSIDE `authenticate(ADMIN_AUTH_NAME)` alongside
 * [adminReportQueue], so the `admin-login` session middleware gates them (302 →
 * `/admin/login` when unauthenticated). The bare collection `/admin/reports`
 * stays GET-only (a bare POST surfaces as a routing-layer 405).
 *
 * Each handler is a state-changing POST, so it follows the same gate order as
 * [adminUserModeration] (design D6): CSRF FIRST ([AdminCsrfGate.validateCsrf] —
 * 403 + `admin_csrf_violation` on miss), THEN the base role gate
 * ([AdminRoleGate.requireWriteRole]), THEN parse the path UUID (malformed →
 * 400, no write), THEN read the `decision`/`resolution` form field from the
 * CSRF-consumed body and map it onto the server-side enum allowlist
 * ([ReportDecision] / [QueueResolution], design D7 — an out-of-allowlist value
 * is 400 with no write, never a DB-CHECK 5xx), THEN the repository transaction.
 * The `ban_author` owner/admin tier is enforced INSIDE the repository
 * transaction so a moderator rejection writes nothing (design D2/D6).
 *
 * The form body is read EXACTLY ONCE (`formParametersAfterValidation`, which on
 * the `_csrf`-form path returns the body the CSRF gate already consumed, and on
 * the header-token path performs the single receive) and threaded through, so
 * the `decision`/`resolution` field + the carried-through filter hidden inputs
 * are both read from one parse.
 *
 * Dual-mode response (design D8): a successful resolution re-renders the re-
 * queried (filter-preserving) `reports-table.peb` fragment for an `HX-Request`,
 * or 303-redirects back to the filtered `/admin/reports` for the no-JS path. A
 * no-op / rejection re-renders the table with a message (fragment for HTMX,
 * full page for no-JS).
 */
fun Route.adminReportResolution(
    resolutionRepo: ReportResolutionRepository,
    reportQueueRepo: ReportQueueRepository,
    auditLogger: AdminAuditLogger,
    csrfHmacKeyProvider: () -> ByteArray,
) {
    post("/reports/{id}/resolve") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val reportId =
            call.parseId() ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        val decision =
            ReportDecision.fromWire(body.field(DECISION_FIELD)) ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_DECISION)
                return@post
            }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val outcome =
            resolutionRepo.resolveReport(
                reportId = reportId,
                decision = decision,
                actingAdminId = principal.adminId,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            ReportDecisionOutcome.Applied -> call.respondResolutionSuccess(reportQueueRepo, body)
            ReportDecisionOutcome.NoOpAlreadyResolved ->
                call.respondReportsView(reportQueueRepo, csrfHmacKeyProvider, body, MSG_REPORT_ALREADY_RESOLVED)
            ReportDecisionOutcome.NotFound ->
                call.respondReportsView(reportQueueRepo, csrfHmacKeyProvider, body, MSG_REPORT_NOT_FOUND)
        }
    }

    post("/moderation-queue/{id}/resolve") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val queueId =
            call.parseId() ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        // Server-side allowlist (design D7): only the five in-scope resolutions
        // map to a non-null enum. `delete`, `accept_flagged_username`,
        // `reject_flagged_username`, and any garbage → null → 400, no write,
        // never reaching the `moderation_queue.resolution` CHECK as a 5xx.
        val resolution =
            QueueResolution.fromWire(body.field(RESOLUTION_FIELD)) ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_RESOLUTION)
                return@post
            }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val outcome =
            resolutionRepo.resolveQueueItem(
                queueId = queueId,
                resolution = resolution,
                actingAdminId = principal.adminId,
                actingAdminRole = principal.role,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            QueueResolutionOutcome.Applied -> call.respondResolutionSuccess(reportQueueRepo, body)
            QueueResolutionOutcome.NoOpAlreadyResolved ->
                call.respondReportsView(reportQueueRepo, csrfHmacKeyProvider, body, MSG_QUEUE_ALREADY_RESOLVED)
            QueueResolutionOutcome.NotFound ->
                call.respondReportsView(reportQueueRepo, csrfHmacKeyProvider, body, MSG_QUEUE_NOT_FOUND)
            QueueResolutionOutcome.RejectedContentNotApplicable ->
                call.respondReportsView(reportQueueRepo, csrfHmacKeyProvider, body, MSG_CONTENT_NOT_APPLICABLE)
            QueueResolutionOutcome.RejectedAuthorUnresolvable ->
                call.respondReportsView(reportQueueRepo, csrfHmacKeyProvider, body, MSG_AUTHOR_UNRESOLVABLE)
            QueueResolutionOutcome.RejectedSuspendGuard ->
                call.respondReportsView(reportQueueRepo, csrfHmacKeyProvider, body, MSG_SUSPEND_GUARD)
            QueueResolutionOutcome.ForbiddenBanTier ->
                call.respondReportsView(
                    reportQueueRepo,
                    csrfHmacKeyProvider,
                    body,
                    MSG_BAN_TIER,
                    status = HttpStatusCode.Forbidden,
                )
        }
    }
}

/** Parse the `{id}` path segment as a UUID; null when malformed (→ 400, no
 *  writes — per the malformed-path-identifier requirement). */
private fun ApplicationCall.parseId(): UUID? = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** Trimmed, blank-dropped form-field accessor. */
private fun Parameters.field(name: String): String? = this[name]?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Successful resolution: re-render the re-queried (filter-preserving)
 * `reports-table.peb` fragment for an `HX-Request` (fragment only), or
 * 303-redirect back to the filtered `/admin/reports` for the no-JS path
 * (design D8). Filters are recovered from the carried-through hidden inputs in
 * [body] (parsed with the SAME semantics as the GET listing via
 * [parseReportFilters]) so a resolution round-trip preserves the active filters.
 */
private suspend fun ApplicationCall.respondResolutionSuccess(
    reportQueueRepo: ReportQueueRepository,
    body: Parameters,
) {
    val filters = parseReportFilters { body[it] }
    if (request.headers[HX_REQUEST] == "true") {
        val page = reportQueueRepo.query(filters.toQuery())
        respond(
            HttpStatusCode.OK,
            PebbleContent(
                "reports-table.peb",
                buildMap<String, Any> {
                    put("rows", page.rows.map { it.toViewMap() })
                    put("hasRows", page.rows.isNotEmpty())
                    put("filters", filters.echoMap)
                },
            ),
        )
    } else {
        val qs = filters.echo.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        response.headers.append(HttpHeaders.Location, if (qs.isEmpty()) "/admin/reports" else "/admin/reports?$qs")
        respond(HttpStatusCode.SeeOther)
    }
}

/**
 * Re-render the report-queue table with an outcome [message] (no-op /
 * rejection): the `reports-table.peb` fragment for an `HX-Request`, otherwise
 * the full `reports.peb` page (carrying the derived `csrfToken` so its in-row
 * `_csrf` hidden fields populate for the no-JS path). The table is re-queried
 * with the carried-through filters so the admin sees the current filtered state
 * alongside the message.
 */
private suspend fun ApplicationCall.respondReportsView(
    reportQueueRepo: ReportQueueRepository,
    csrfHmacKeyProvider: () -> ByteArray,
    body: Parameters,
    message: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val isHtmx = request.headers[HX_REQUEST] == "true"
    val filters = parseReportFilters { body[it] }
    val page = reportQueueRepo.query(filters.toQuery())
    val model =
        buildMap<String, Any> {
            put("rows", page.rows.map { it.toViewMap() })
            put("hasRows", page.rows.isNotEmpty())
            put("filters", filters.echoMap)
            put("message", message)
            if (!isHtmx) {
                request.cookies[AdminAuthProvider.COOKIE_NAME]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { HashUtil.deriveCsrfFromSessionToken(it, csrfHmacKeyProvider()) }
                    ?.let { put("csrfToken", it) }
            }
        }
    val template = if (isHtmx) "reports-table.peb" else "reports.peb"
    respond(status, PebbleContent(template, model))
}

private const val HX_REQUEST = "HX-Request"
private const val DECISION_FIELD = "decision"
private const val RESOLUTION_FIELD = "resolution"

// User-facing informational messages (admin panel is English-only, matching the
// existing index/login/actions-log/moderation templates).
private const val MSG_INVALID_ID = "Invalid id."
private const val MSG_INVALID_DECISION = "Invalid decision. Use 'actioned' or 'dismissed'."
private const val MSG_INVALID_RESOLUTION =
    "Invalid or out-of-scope resolution. Use keep, hide, suspend_author_7d, ban_author, or shadow_ban_author."
private const val MSG_REPORT_NOT_FOUND = "No matching report."
private const val MSG_REPORT_ALREADY_RESOLVED = "Report is already resolved. No change made."
private const val MSG_QUEUE_NOT_FOUND = "No matching queue item."
private const val MSG_QUEUE_ALREADY_RESOLVED = "Queue item is already resolved. No change made."
private const val MSG_CONTENT_NOT_APPLICABLE =
    "Keep/Hide apply only to post or reply targets. No change made."
private const val MSG_AUTHOR_UNRESOLVABLE =
    "Cannot resolve the offending author (target was hard-deleted). No change made."
private const val MSG_SUSPEND_GUARD =
    "Cannot suspend this author (deleted account, or already permanently banned). No change made."
private const val MSG_BAN_TIER = "Issuing a permanent ban requires owner or admin role. No change made."
