package id.nearyou.app.admin.routes

import id.nearyou.app.admin.actionslog.ActionLogCursor
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.HashUtil
import id.nearyou.app.admin.reportqueue.ReportQueueQuery
import id.nearyou.app.admin.reportqueue.ReportQueueRepository
import id.nearyou.app.admin.reportqueue.toViewMap
import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.URLEncoder
import java.time.ZoneOffset

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
        val filters = parseReportFilters { params[it] }
        val cursor = ActionLogCursor.decode(params["cursor"])

        val page = repo.query(filters.toQuery(cursor))

        val olderUrl =
            page.nextCursor?.let { next ->
                val pairs = filters.echo + ("cursor" to next.encode())
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
                put("filters", filters.echoMap)
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

// Row → template-model shaping is shared with the resolution route's
// post-action table re-render — see `ReportQueueRow.toViewMap` in
// `id.nearyou.app.admin.reportqueue.ReportQueueView`.

/**
 * The parsed, canonical report-queue filter set. Shared by `GET /admin/reports`
 * (reads query params) and the resolution route's post-action re-render (reads
 * the form body's hidden filter inputs) so the two surfaces apply IDENTICAL
 * filter semantics — including the UTC date-boundary math (`from` inclusive from
 * the start of the UTC day; `to` inclusive of the whole UTC day via an exclusive
 * `< to + 1 day` upper bound, design.md D2). [echo] is the canonical post-parse
 * key/value list for form-repopulation, the "older" link, and the no-JS
 * redirect — so filters survive a resolution round-trip.
 */
internal data class ReportFilters(
    val status: String?,
    val targetType: String?,
    val reasonCategory: String?,
    val trigger: String?,
    val fromInclusive: java.time.Instant?,
    val toExclusive: java.time.Instant?,
    val echo: List<Pair<String, String>>,
) {
    fun toQuery(cursor: ActionLogCursor? = null): ReportQueueQuery =
        ReportQueueQuery(
            status = status,
            targetType = targetType,
            reasonCategory = reasonCategory,
            trigger = trigger,
            fromInclusive = fromInclusive,
            toExclusive = toExclusive,
            cursor = cursor,
        )

    val echoMap: Map<String, String> get() = echo.toMap()
}

/**
 * Parse + canonicalize the report-queue filters from a generic [get] accessor
 * (query params for the GET listing; the consumed form body for the resolution
 * re-render). Lenient (design.md — never 4xx): each text filter is trimmed,
 * blank-dropped, and width-capped; an unparseable date is ignored; out-of-enum
 * values pass through to bind as zero-match literals downstream.
 */
internal fun parseReportFilters(get: (String) -> String?): ReportFilters {
    val status = get("status")?.trim()?.takeIf { it.isNotEmpty() }?.take(STATUS_MAX)
    val targetType = get("target_type")?.trim()?.takeIf { it.isNotEmpty() }?.take(TARGET_TYPE_MAX)
    val reasonCategory = get("reason_category")?.trim()?.takeIf { it.isNotEmpty() }?.take(REASON_CATEGORY_MAX)
    val trigger = get("trigger")?.trim()?.takeIf { it.isNotEmpty() }?.take(TRIGGER_MAX)
    val from = get("from")?.trim()?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    val to = get("to")?.trim()?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

    val echo =
        buildList {
            status?.let { add("status" to it) }
            targetType?.let { add("target_type" to it) }
            reasonCategory?.let { add("reason_category" to it) }
            trigger?.let { add("trigger" to it) }
            from?.let { add("from" to it.toString()) }
            to?.let { add("to" to it.toString()) }
        }
    return ReportFilters(
        status = status,
        targetType = targetType,
        reasonCategory = reasonCategory,
        trigger = trigger,
        fromInclusive = from?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
        toExclusive = to?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
        echo = echo,
    )
}
