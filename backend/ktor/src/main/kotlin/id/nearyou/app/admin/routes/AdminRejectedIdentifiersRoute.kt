package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.rejectedidentifiers.ALLOWED_IDENTIFIER_TYPES
import id.nearyou.app.admin.rejectedidentifiers.ALLOWED_REJECTION_REASONS
import id.nearyou.app.admin.rejectedidentifiers.AdminRejectedIdentifiersRepository
import id.nearyou.app.admin.rejectedidentifiers.ClearOutcome
import id.nearyou.app.admin.rejectedidentifiers.RejectedIdentifierRow
import id.nearyou.app.admin.rejectedidentifiers.RejectedIdentifiersCursor
import id.nearyou.app.admin.rejectedidentifiers.RejectedIdentifiersQuery
import id.nearyou.app.admin.rejectedidentifiers.RejectedIdentifiersSummary
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `/admin/rejected-identifiers` — the rejected-identifiers viewer
 * (`admin-rejected-identifiers-viewer` capability, Admin #3.5) PLUS the manual
 * support-clear write action (`admin-rejected-identifiers-clear-action`).
 *
 * Wired INSIDE the `authenticate(ADMIN_AUTH_NAME) { ... }` block so the
 * `admin-login` session middleware gates both (302 → `/admin/login` when
 * unauthenticated).
 *
 *  - `GET /rejected-identifiers` — read-only list (any authenticated admin role),
 *    dual-mode HTMX fragment + no-JS full page, keyset pagination + lenient
 *    parameterized filters + per-reason/per-type count summary.
 *  - `POST /rejected-identifiers/{id}/clear` — hard-DELETE one row so a falsely-
 *    rejected legitimate adult can re-verify. State-changing, so it follows the
 *    gate order from [adminReservedUsernames] (design D2): CSRF FIRST
 *    ([AdminCsrfGate.validateCsrf] → 403 + `admin_csrf_violation`), THEN the
 *    owner/admin role gate ([AdminRoleGate.requireOwnerOrAdmin] — STRICTER than
 *    the any-role read; clearing weakens an anti-abuse control), THEN id/reason
 *    validation (400 on a malformed UUID; 400 on a blank/over-long reason), THEN
 *    the repository transaction (audit-logged + rate-limited inside [repo.clear]).
 *    The bare collection path stays GET-only (a `POST /rejected-identifiers` is an
 *    unmapped 405).
 *
 * Filter parsing is lenient (an unrecognized / over-long enum value and an
 * unparseable date are each ignored). All applied values reach the repository as
 * positionally-bound `PreparedStatement` parameters, never interpolated SQL.
 *
 * Expected no-op / in-band outcomes (id not found, quota exceeded) re-render the
 * list with a message — never a 5xx. A successful clear uses PRG (303 redirect for
 * the no-JS path, table fragment for HTMX). The per-row clear control renders
 * ONLY for owner/admin sessions (`canClear`); a moderator/read_only session sees
 * the read view with no clear control.
 */
fun Route.adminRejectedIdentifiers(
    repo: AdminRejectedIdentifiersRepository,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/rejected-identifiers") {
        call.respondRejectedIdentifiersList(repo, layout)
    }

    post("/rejected-identifiers/{id}/clear") {
        // Gate order (design D2): CSRF → owner/admin role → parse.
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val principal = call.principal<AdminPrincipal>() ?: return@post call.respond(HttpStatusCode.Forbidden)
        val id =
            call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
        val reason =
            body[FIELD_REASON]?.trim()?.takeIf { it.isNotEmpty() && it.length <= REASON_MAX_LEN }
                ?: return@post call.respond(HttpStatusCode.BadRequest, MSG_INVALID_REASON)
        when (
            repo.clear(
                id = id,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        ) {
            ClearOutcome.Cleared -> call.respondClearSuccess(repo, layout)
            ClearOutcome.NotFound -> call.respondRejectedIdentifiersList(repo, layout, message = MSG_NOT_FOUND)
            ClearOutcome.RateLimited -> call.respondRejectedIdentifiersList(repo, layout, message = MSG_QUOTA_EXCEEDED)
        }
    }
}

/**
 * Successful-clear response: re-render the table fragment with a success message
 * for an `HX-Request` (htmx swaps it in place — the cleared row is absent from the
 * re-queried page), or 303-redirect to the list for the no-JS path (PRG, so a
 * refresh doesn't re-POST). The re-render re-queries unfiltered (mirrors
 * [adminReservedUsernames]); the hard-deleted row cannot reappear regardless of any
 * prior filter.
 */
private suspend fun ApplicationCall.respondClearSuccess(
    repo: AdminRejectedIdentifiersRepository,
    layout: AdminLayout,
) {
    if (request.headers[HX_REQUEST] == "true") {
        respondRejectedIdentifiersList(repo, layout, message = MSG_CLEARED)
    } else {
        response.headers.append(HttpHeaders.Location, "/admin/rejected-identifiers")
        respond(HttpStatusCode.SeeOther)
    }
}

/**
 * Render the rejected-identifiers list: parse the query-string filters + cursor,
 * query the page + summary, then respond with the `rejected-identifiers-table.peb`
 * fragment for an `HX-Request` or the full `rejected-identifiers.peb` page
 * otherwise. [message] surfaces an in-band write outcome. `canClear` (owner/admin
 * only) drives whether the per-row clear control renders. Reads write NO audit row.
 */
private suspend fun ApplicationCall.respondRejectedIdentifiersList(
    repo: AdminRejectedIdentifiersRepository,
    layout: AdminLayout,
    message: String? = null,
) {
    val params = request.queryParameters

    // Enum filters: exact membership in the V3 CHECK allowlist. An over-long
    // value is length-bounded first (defense-in-depth) and then, matching no
    // allowed enum, is ignored — never bound, never errored.
    val reason =
        params["reason"]?.trim()?.take(REASON_MAX)?.takeIf { it in ALLOWED_REJECTION_REASONS }
    val identifierType =
        params["identifier_type"]?.trim()?.take(IDENTIFIER_TYPE_MAX)?.takeIf { it in ALLOWED_IDENTIFIER_TYPES }
    val from = params["from"]?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val to = params["to"]?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val cursor = RejectedIdentifiersCursor.decode(params["cursor"])

    // Date boundaries interpreted in UTC: `from` inclusive from the start of that
    // UTC day; `to` inclusive of the whole UTC day via an exclusive `< to + 1 day`
    // upper bound (matching `admin-actions-log-viewer`).
    val fromInclusive = from?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
    val toExclusive = to?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()

    val query =
        RejectedIdentifiersQuery(
            reason = reason,
            identifierType = identifierType,
            fromInclusive = fromInclusive,
            toExclusive = toExclusive,
            cursor = cursor,
        )
    val page = repo.query(query)
    val summary = repo.summary(query)

    val activeFilters =
        buildList {
            reason?.let { add("reason" to it) }
            identifierType?.let { add("identifier_type" to it) }
            from?.let { add("from" to it.toString()) }
            to?.let { add("to" to it.toString()) }
        }
    val filters = activeFilters.toMap()

    val olderUrl =
        page.nextCursor?.let { next ->
            val pairs = activeFilters + ("cursor" to next.encode())
            "/admin/rejected-identifiers?" +
                pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        }

    // Owner/admin only: the clear control is rendered for this session's role.
    // Mirrors the route's owner/admin POST gate so a moderator/read_only session
    // sees the read view with no clear affordance (and a forged POST is 403'd).
    val canClear = principal<AdminPrincipal>()?.role in AdminRoleGate.OWNER_ADMIN_ROLES

    val isHtmx = request.headers[HX_REQUEST] == "true"
    val model =
        buildMap<String, Any> {
            put("rows", page.rows.map { it.toViewMap() })
            put("hasRows", page.rows.isNotEmpty())
            put("filters", filters)
            put("summary", summary.toViewMap())
            put("canClear", canClear)
            olderUrl?.let { put("olderUrl", it) }
            message?.let { put("message", it) }
            if (!isHtmx) {
                layout.putShellModel(
                    this@respondRejectedIdentifiersList,
                    this,
                    pageTitle = "Rejected identifiers",
                    activePath = "/admin/rejected-identifiers",
                )
            }
        }

    val template = if (isHtmx) "rejected-identifiers-table.peb" else "rejected-identifiers.peb"
    respond(PebbleContent(template, model))
}

private const val HX_REQUEST = "HX-Request"
private const val REASON_MAX = 32 // VARCHAR(32) column width (filter enum)
private const val IDENTIFIER_TYPE_MAX = 8 // VARCHAR(8) column width (filter enum)

/** Clear-reason length cap (`admin_actions_log.reason` is TEXT; this is the
 *  app-layer bound so an over-long reason is a clean 400, not a DB write). */
private const val REASON_MAX_LEN = 500
private const val FIELD_REASON = "reason"

private const val MSG_INVALID_ID = "Invalid identifier."
private const val MSG_INVALID_REASON = "A reason (1-500 characters) is required to clear a rejected identifier."
private const val MSG_CLEARED = "Rejected identifier cleared. The user can re-verify on a new signup."
private const val MSG_NOT_FOUND = "That rejected identifier no longer exists (already cleared?). No change made."
private const val MSG_QUOTA_EXCEEDED = "Clear quota exceeded (10/hour). Try again later. No change made."

private val REJECTED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * Pre-format a row into display strings for the template. Pebble autoescaping
 * escapes every value on output, so no value needs manual escaping here. The
 * `clearAction` is the per-row POST target the (owner/admin-only) clear control
 * submits to.
 */
private fun RejectedIdentifierRow.toViewMap(): Map<String, String> =
    mapOf(
        "rejectedAt" to REJECTED_AT_FORMAT.format(rejectedAt),
        "identifierHash" to identifierHash,
        "identifierType" to identifierType,
        "reason" to reason,
        "clearAction" to "/admin/rejected-identifiers/$id/clear",
    )

/**
 * Render the summary into view-friendly bucket lists. EVERY allowed enum bucket
 * is emitted (defaulting absent ones to 0) so a filter-narrowed scope shows the
 * excluded reason / type as an explicit `0` rather than silently dropping it.
 */
private fun RejectedIdentifiersSummary.toViewMap(): Map<String, Any> =
    mapOf(
        "total" to total,
        "reasons" to ALLOWED_REJECTION_REASONS.map { mapOf("key" to it, "count" to (byReason[it] ?: 0L)) },
        "types" to ALLOWED_IDENTIFIER_TYPES.map { mapOf("key" to it, "count" to (byType[it] ?: 0L)) },
    )
