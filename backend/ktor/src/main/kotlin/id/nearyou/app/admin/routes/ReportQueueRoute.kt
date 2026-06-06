package id.nearyou.app.admin.routes

import id.nearyou.app.admin.actionslog.ActionLogCursor
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.HashUtil
import id.nearyou.app.admin.reportqueue.ReportQueueQuery
import id.nearyou.app.admin.reportqueue.ReportQueueRepository
import id.nearyou.app.admin.reportqueue.ReportQueueRow
import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `GET /admin/reports` — the read-only Report Queue moderator triage view
 * (`admin-report-queue` capability, Admin #6).
 *
 * Wired INSIDE the `authenticate(ADMIN_AUTH_NAME) { ... }` block so the
 * `admin-login` session middleware gates it (302 → `/admin/login` when
 * unauthenticated; any valid admin session — NOT role-restricted, matching
 * [adminActionsLog]). Read-only: only GET is wired — no mutation handlers, no
 * resolution route (the write-back ships as `admin-report-queue-resolution-
 * actions`).
 *
 * Dual-mode rendering (design.md D7, mirroring `admin-actions-log-viewer`):
 *  - `HX-Request: true` → respond with ONLY the `reports-table.peb` fragment
 *    (the filter form + layout stay put; the table swaps in place).
 *  - otherwise → render the full `reports.peb` page (extends `layout.peb`,
 *    includes the same fragment) so the feature works without JavaScript.
 *
 * Lenient on malformed input (design.md — never 4xx/5xx): a malformed/absent
 * cursor falls back to the first page; an unparseable date is ignored; an
 * out-of-enum filter value is bound as a literal that matches no row. The
 * full-page render derives `csrfToken` from the session cookie exactly as
 * [adminActionsLog] does so the layout's CSRF meta tag + logout-form token
 * render. Reads are not auditable, so the handler writes NO audit row.
 */
fun Route.adminReportQueue(
    repo: ReportQueueRepository,
    csrfHmacKeyProvider: () -> ByteArray,
) {
    get("/reports") {
        val params = call.request.queryParameters

        val status = params["status"]?.trim()?.takeIf { it.isNotEmpty() }?.take(STATUS_MAX)
        val targetType = params["target_type"]?.trim()?.takeIf { it.isNotEmpty() }?.take(TARGET_TYPE_MAX)
        val reasonCategory = params["reason_category"]?.trim()?.takeIf { it.isNotEmpty() }?.take(REASON_CATEGORY_MAX)
        val trigger = params["trigger"]?.trim()?.takeIf { it.isNotEmpty() }?.take(TRIGGER_MAX)
        val from = params["from"]?.trim()?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val to = params["to"]?.trim()?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val cursor = ActionLogCursor.decode(params["cursor"])

        // Date boundaries are interpreted in UTC: `from` is inclusive from the
        // start of that UTC day; `to` is inclusive of the whole UTC day via an
        // exclusive `< to + 1 day` upper bound (design.md D2 / spec).
        val fromInclusive = from?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
        val toExclusive = to?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()

        val query =
            ReportQueueQuery(
                status = status,
                targetType = targetType,
                reasonCategory = reasonCategory,
                trigger = trigger,
                fromInclusive = fromInclusive,
                toExclusive = toExclusive,
                cursor = cursor,
            )
        val page = repo.query(query)

        // Echo the active filters back (canonical, post-parse) so the form
        // repopulates + the "older" link preserves them.
        val activeFilters =
            buildList {
                status?.let { add("status" to it) }
                targetType?.let { add("target_type" to it) }
                reasonCategory?.let { add("reason_category" to it) }
                trigger?.let { add("trigger" to it) }
                from?.let { add("from" to it.toString()) }
                to?.let { add("to" to it.toString()) }
            }
        val filters = activeFilters.toMap()

        val olderUrl =
            page.nextCursor?.let { next ->
                val pairs = activeFilters + ("cursor" to next.encode())
                "/admin/reports?" +
                    pairs.joinToString("&") { (k, v) ->
                        "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
                    }
            }

        val isHtmx = call.request.headers["HX-Request"] == "true"
        val model =
            buildMap<String, Any> {
                put("rows", page.rows.map { it.toViewMap() })
                put("hasRows", page.rows.isNotEmpty())
                put("filters", filters)
                olderUrl?.let { put("olderUrl", it) }

                if (!isHtmx) {
                    // Full-page render extends layout.peb → must carry the CSRF
                    // token so the layout's meta tag + logout-form token render.
                    call.request.cookies[AdminAuthProvider.COOKIE_NAME]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { HashUtil.deriveCsrfFromSessionToken(it, csrfHmacKeyProvider()) }
                        ?.let { put("csrfToken", it) }
                }
            }

        val template = if (isHtmx) "reports-table.peb" else "reports.peb"
        call.respond(PebbleContent(template, model))
    }
}

private const val STATUS_MAX = 16 // VARCHAR(16) column width
private const val TARGET_TYPE_MAX = 16 // VARCHAR(16) column width
private const val REASON_CATEGORY_MAX = 32 // VARCHAR(32) column width
private const val TRIGGER_MAX = 32 // VARCHAR(32) column width
private const val EM_DASH = "—"

private val CREATED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * Pre-format a report row into display values for the template. NULL columns
 * become an em-dash placeholder; the resolved offending-user id (for the
 * deep-link) stays null when the target was hard-deleted so the template
 * renders the bare `target_id` with no link. Pebble autoescaping escapes every
 * value on output — including the user-controlled `reason_note` and the joined
 * `reporter` username — so no value needs manual escaping here.
 */
private fun ReportQueueRow.toViewMap(): Map<String, Any?> =
    mapOf(
        "createdAt" to CREATED_AT_FORMAT.format(createdAt),
        "targetType" to targetType,
        "targetId" to targetId.toString(),
        "reasonCategory" to reasonCategory,
        "reasonNote" to (reasonNote ?: EM_DASH),
        "status" to status,
        "reporter" to (reporterUsername ?: reporterId.toString()),
        "hasQueue" to (queueTrigger != null),
        "queueTrigger" to (queueTrigger ?: EM_DASH),
        "queuePriority" to (queuePriority?.toString() ?: EM_DASH),
        "queueStatus" to (queueStatus ?: EM_DASH),
        // null → template renders the bare target_id with no action link.
        "actionUserId" to actionUserId?.toString(),
    )
