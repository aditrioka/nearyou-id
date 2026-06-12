package id.nearyou.app.admin.routes

import id.nearyou.app.admin.actionslog.ActionLogCursor
import id.nearyou.app.admin.actionslog.ActionLogQuery
import id.nearyou.app.admin.actionslog.ActionLogRow
import id.nearyou.app.admin.actionslog.AdminActionsLogRepository
import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `GET /admin/actions-log` — the read-only audit-log viewer
 * (`admin-actions-log-viewer` capability, Admin #4).
 *
 * Wired INSIDE the `authenticate(ADMIN_AUTH_NAME) { ... }` block so the
 * `admin-login` session middleware gates it (302 → `/admin/login` when
 * unauthenticated). Read-only: only GET is wired — no mutation handlers.
 *
 * Dual-mode rendering (design.md D3):
 *  - `HX-Request: true` → respond with ONLY the `actions-log-table.peb`
 *    fragment (the filter form + layout stay put; the table swaps in place).
 *  - otherwise → render the full `actions-log.peb` page (extends `layout.peb`,
 *    includes the same fragment) so the feature works without JavaScript.
 *
 * CSRF token: the full-page render derives `csrfToken` from the session
 * cookie exactly as [adminIndex] does, so `layout.peb`'s CSRF meta tag +
 * htmx hook + logout-form token render (design.md D3 — the full page extends
 * the authenticated layout). The fragment response omits it (renders no
 * layout). Reads are not auditable, so the handler writes NO audit row.
 */
fun Route.adminActionsLog(
    repo: AdminActionsLogRepository,
    layout: AdminLayout,
) {
    get("/actions-log") {
        val params = call.request.queryParameters

        val actionType = params["action_type"]?.trim()?.takeIf { it.isNotEmpty() }?.take(ACTION_TYPE_MAX)
        val adminId = params["admin_id"]?.trim()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val targetType = params["target_type"]?.trim()?.takeIf { it.isNotEmpty() }?.take(TARGET_TYPE_MAX)
        val targetId = params["target_id"]?.trim()?.takeIf { it.isNotEmpty() }?.take(TARGET_ID_MAX)
        val from = params["from"]?.trim()?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val to = params["to"]?.trim()?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val cursor = ActionLogCursor.decode(params["cursor"])

        // Date boundaries are interpreted in UTC: `from` is inclusive from the
        // start of that UTC day; `to` is inclusive of the whole UTC day via an
        // exclusive `< to + 1 day` upper bound (design.md D2).
        val fromInclusive = from?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
        val toExclusive = to?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()

        val query =
            ActionLogQuery(
                actionType = actionType,
                adminId = adminId,
                targetType = targetType,
                targetId = targetId,
                fromInclusive = fromInclusive,
                toExclusive = toExclusive,
                cursor = cursor,
            )
        val page = repo.query(query)

        // Echo the active filters back (canonical, post-parse) so the form
        // repopulates + the "older" link preserves them.
        val activeFilters =
            buildList {
                actionType?.let { add("action_type" to it) }
                adminId?.let { add("admin_id" to it.toString()) }
                targetType?.let { add("target_type" to it) }
                targetId?.let { add("target_id" to it) }
                from?.let { add("from" to it.toString()) }
                to?.let { add("to" to it.toString()) }
            }
        val filters = activeFilters.toMap()

        val olderUrl =
            page.nextCursor?.let { next ->
                val pairs = activeFilters + ("cursor" to next.encode())
                "/admin/actions-log?" +
                    pairs.joinToString("&") { (k, v) ->
                        "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
                    }
            }

        val model =
            buildMap<String, Any> {
                put("rows", page.rows.map { it.toViewMap() })
                put("hasRows", page.rows.isNotEmpty())
                put("filters", filters)
                olderUrl?.let { put("olderUrl", it) }

                val isHtmx = call.request.headers["HX-Request"] == "true"
                if (!isHtmx) {
                    layout.putShellModel(call, this, pageTitle = "Audit log", activePath = "/admin/actions-log")
                }
            }

        val template = if (call.request.headers["HX-Request"] == "true") "actions-log-table.peb" else "actions-log.peb"
        call.respond(PebbleContent(template, model))
    }
}

private const val ACTION_TYPE_MAX = 64 // VARCHAR(64) column width
private const val TARGET_TYPE_MAX = 32 // VARCHAR(32) column width
private const val TARGET_ID_MAX = 256 // TEXT column — clamp an unbounded query param to a sane bound
private const val EM_DASH = "—"

private val CREATED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * Pre-format a row into display strings for the template. NULL columns
 * become an em-dash placeholder (design.md D8). Pebble autoescaping escapes
 * every value on output — including the client-controlled `user_agent` and
 * free-text `reason` — so no value needs manual escaping here.
 */
private fun ActionLogRow.toViewMap(): Map<String, String> =
    mapOf(
        "createdAt" to CREATED_AT_FORMAT.format(createdAt),
        "adminDisplay" to "$adminDisplayName <$adminEmail>",
        "actionType" to actionType,
        "target" to
            when {
                targetType == null -> EM_DASH
                targetId == null -> targetType
                else -> "$targetType / $targetId"
            },
        "reason" to (reason ?: EM_DASH),
        "ip" to (ip ?: EM_DASH),
        "userAgent" to (userAgent ?: EM_DASH),
        "beforeState" to (beforeState ?: EM_DASH),
        "afterState" to (afterState ?: EM_DASH),
    )
