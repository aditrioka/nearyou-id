package id.nearyou.app.admin.routes

import id.nearyou.app.admin.rejectedidentifiers.ALLOWED_IDENTIFIER_TYPES
import id.nearyou.app.admin.rejectedidentifiers.ALLOWED_REJECTION_REASONS
import id.nearyou.app.admin.rejectedidentifiers.AdminRejectedIdentifiersRepository
import id.nearyou.app.admin.rejectedidentifiers.RejectedIdentifierRow
import id.nearyou.app.admin.rejectedidentifiers.RejectedIdentifiersCursor
import id.nearyou.app.admin.rejectedidentifiers.RejectedIdentifiersQuery
import id.nearyou.app.admin.rejectedidentifiers.RejectedIdentifiersSummary
import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `GET /admin/rejected-identifiers` — the read-only rejected-identifiers viewer
 * (`admin-rejected-identifiers-viewer` capability, Admin #3.5).
 *
 * Wired INSIDE the `authenticate(ADMIN_AUTH_NAME) { ... }` block so the
 * `admin-login` session middleware gates it (302 → `/admin/login` when
 * unauthenticated). Read-only: only GET is wired — no mutation handlers. The
 * manual support-clear `DELETE` is the deferred fast-follow
 * `admin-rejected-identifiers-clear-action` (design.md D3).
 *
 * Dual-mode rendering (mirrors `adminActionsLog`):
 *  - `HX-Request: true` → respond with ONLY the `rejected-identifiers-table.peb`
 *    fragment (the filter form + layout stay put; the table + summary swap in
 *    place — the summary lives inside the swapped `#rejected-identifiers-table`
 *    element so it stays in sync with the active filters).
 *  - otherwise → render the full `rejected-identifiers.peb` page (extends
 *    `layout.peb`, includes the same fragment) so the feature works without
 *    JavaScript and a filtered/paginated URL stays shareable.
 *
 * Filter parsing is lenient: an unrecognized / over-long `reason` or
 * `identifier_type` (not in the V3 CHECK allowlist) and an unparseable
 * `from` / `to` date are each ignored, while the remaining valid filters still
 * apply. All applied values reach the repository as positionally-bound
 * `PreparedStatement` parameters, never interpolated SQL.
 *
 * CSRF token: the full-page render derives `csrfToken` from the session cookie
 * exactly as [adminIndex] does, so `layout.peb`'s CSRF meta tag + htmx hook +
 * logout-form token render. The fragment response omits it. Reads are not
 * auditable, so the handler writes NO audit row.
 */
fun Route.adminRejectedIdentifiers(
    repo: AdminRejectedIdentifiersRepository,
    layout: AdminLayout,
) {
    get("/rejected-identifiers") {
        val params = call.request.queryParameters

        // Enum filters: exact membership in the V3 CHECK allowlist. An
        // over-long value is length-bounded first (defense-in-depth) and then,
        // matching no allowed enum, is ignored — never bound, never errored.
        val reason =
            params["reason"]?.trim()?.take(REASON_MAX)?.takeIf { it in ALLOWED_REJECTION_REASONS }
        val identifierType =
            params["identifier_type"]?.trim()?.take(IDENTIFIER_TYPE_MAX)?.takeIf { it in ALLOWED_IDENTIFIER_TYPES }
        val from = params["from"]?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val to = params["to"]?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val cursor = RejectedIdentifiersCursor.decode(params["cursor"])

        // Date boundaries are interpreted in UTC: `from` is inclusive from the
        // start of that UTC day; `to` is inclusive of the whole UTC day via an
        // exclusive `< to + 1 day` upper bound — matching the shipped
        // `admin-actions-log-viewer` convention (design.md D1).
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
        // Summary uses the SAME query value-object; the repo's summary() ignores
        // the cursor + page size, counting the whole filtered set (design.md D1).
        val summary = repo.summary(query)

        // Echo the active filters back (canonical, post-parse) so the form
        // repopulates + the "older" link preserves them.
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
                    pairs.joinToString("&") { (k, v) ->
                        "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
                    }
            }

        val model =
            buildMap<String, Any> {
                put("rows", page.rows.map { it.toViewMap() })
                put("hasRows", page.rows.isNotEmpty())
                put("filters", filters)
                put("summary", summary.toViewMap())
                olderUrl?.let { put("olderUrl", it) }

                val isHtmx = call.request.headers["HX-Request"] == "true"
                if (!isHtmx) {
                    layout.putShellModel(
                        call,
                        this,
                        pageTitle = "Rejected identifiers",
                        activePath = "/admin/rejected-identifiers",
                    )
                }
            }

        val template =
            if (call.request.headers["HX-Request"] == "true") {
                "rejected-identifiers-table.peb"
            } else {
                "rejected-identifiers.peb"
            }
        call.respond(PebbleContent(template, model))
    }
}

private const val REASON_MAX = 32 // VARCHAR(32) column width
private const val IDENTIFIER_TYPE_MAX = 8 // VARCHAR(8) column width

private val REJECTED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * Pre-format a row into display strings for the template. Pebble autoescaping
 * escapes every value on output, so no value needs manual escaping here
 * (defense-in-depth — `rejected_identifiers` has no free-text column).
 */
private fun RejectedIdentifierRow.toViewMap(): Map<String, String> =
    mapOf(
        "rejectedAt" to REJECTED_AT_FORMAT.format(rejectedAt),
        "identifierHash" to identifierHash,
        "identifierType" to identifierType,
        "reason" to reason,
    )

/**
 * Render the summary into view-friendly bucket lists. EVERY allowed enum bucket
 * is emitted (defaulting absent ones to 0) so a filter-narrowed scope shows the
 * excluded reason / type as an explicit `0` rather than silently dropping it —
 * keeps the spike-detection columns stable across filters.
 */
private fun RejectedIdentifiersSummary.toViewMap(): Map<String, Any> =
    mapOf(
        "total" to total,
        "reasons" to ALLOWED_REJECTION_REASONS.map { mapOf("key" to it, "count" to (byReason[it] ?: 0L)) },
        "types" to ALLOWED_IDENTIFIER_TYPES.map { mapOf("key" to it, "count" to (byType[it] ?: 0L)) },
    )
