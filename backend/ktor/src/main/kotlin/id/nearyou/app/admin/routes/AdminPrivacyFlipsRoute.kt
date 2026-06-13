package id.nearyou.app.admin.routes

import id.nearyou.app.admin.privacyflips.ALLOWED_PRIVACY_FLIP_STATUSES
import id.nearyou.app.admin.privacyflips.AdminPrivacyFlipsRepository
import id.nearyou.app.admin.privacyflips.PrivacyFlipRow
import id.nearyou.app.admin.privacyflips.PrivacyFlipsCursor
import id.nearyou.app.admin.privacyflips.PrivacyFlipsQuery
import id.nearyou.app.admin.privacyflips.PrivacyFlipsSummary
import id.nearyou.app.admin.privacyflips.STATUS_IN_WINDOW
import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `GET /admin/privacy-flips` — the read-only Privacy Flip Monitor
 * (`admin-privacy-flip-monitor` capability, admin board frame 17).
 *
 * Wired INSIDE the `authenticate(ADMIN_AUTH_NAME) { ... }` block so the
 * `admin-login` session middleware gates it (302 → `/admin/login` when
 * unauthenticated). Read-only: only GET is wired — no mutation handlers. It
 * neither clears nor expedites a scheduled flip; anomalies escalate to an
 * out-of-band worker fix (frame 17).
 *
 * Dual-mode rendering (mirrors [adminRejectedIdentifiers]):
 *  - `HX-Request: true` → respond with ONLY the `privacy-flips-table.peb`
 *    fragment (the filter form + layout stay put; the table + count summary
 *    swap in place — the summary lives inside the swapped `#privacy-flips-table`
 *    element so it stays in sync with the active filters).
 *  - otherwise → render the full `privacy-flips.peb` page (extends `layout.peb`,
 *    includes the same fragment) so the feature works without JavaScript and a
 *    filtered/paginated URL stays shareable.
 *
 * A single request-scoped `evalInstant` (from [clock]; `Instant.now()` in
 * production, a fixed instant in tests) drives the IN_WINDOW / OVERDUE
 * classification, the `status` filter, AND the relative-time display — so the
 * row's class can never disagree with the `status`-filtered set, and the
 * boundary is deterministically testable (design.md D3).
 *
 * Filter parsing is lenient: an unrecognized / over-long `status` and an
 * empty/blank `q` are ignored, while the remaining valid filters still apply.
 * All applied values reach the repository as positionally-bound
 * `PreparedStatement` parameters, never interpolated SQL.
 */
fun Route.adminPrivacyFlips(
    repo: AdminPrivacyFlipsRepository,
    layout: AdminLayout,
    clock: () -> Instant = Instant::now,
) {
    get("/privacy-flips") {
        val params = call.request.queryParameters
        val evalInstant = clock()

        // Status: exact membership in the allowed bucket set; over-long values
        // are length-bounded first and then, matching no allowed value, ignored.
        val status =
            params["status"]?.trim()?.take(STATUS_MAX)?.takeIf { it in ALLOWED_PRIVACY_FLIP_STATUSES }

        // q: a single-user lookup. Blank → ignored entirely (NOT bound as
        // `username = ''`). A UUID-shaped value → id lookup; otherwise an exact
        // case-insensitive username lookup bound as a literal. The value is
        // length-capped (hygiene) at a width safely ABOVE the 60-char username
        // column, NEVER truncated into the matchable range — so a legitimate
        // 60-char username still matches exactly, while an over-long value
        // matches no user (empty state) rather than a truncated prefix.
        val rawQ = params["q"]?.trim()?.takeIf { it.isNotEmpty() }
        val qUserId = rawQ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val qUsername = if (rawQ != null && qUserId == null) rawQ.take(Q_MAX) else null

        val cursor = PrivacyFlipsCursor.decode(params["cursor"])

        val query =
            PrivacyFlipsQuery(
                evalInstant = evalInstant,
                status = status,
                qUserId = qUserId,
                qUsername = qUsername,
                cursor = cursor,
            )
        val page = repo.query(query)
        val summary = repo.summary(query)

        // Echo the active filters back (canonical, post-parse) so the form
        // repopulates + the "next" link preserves them. `q` echoes whichever of
        // the id/username lookup was applied (an over-long/blank q is dropped).
        val activeFilters =
            buildList {
                status?.let { add("status" to it) }
                (qUserId?.toString() ?: qUsername)?.let { add("q" to it) }
            }
        val filters = activeFilters.toMap()

        val nextUrl =
            page.nextCursor?.let { next ->
                val pairs = activeFilters + ("cursor" to next.encode())
                "/admin/privacy-flips?" +
                    pairs.joinToString("&") { (k, v) ->
                        "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
                    }
            }

        val model =
            buildMap<String, Any> {
                put("rows", page.rows.map { it.toViewMap(evalInstant) })
                put("hasRows", page.rows.isNotEmpty())
                put("filters", filters)
                put("summary", summary.toViewMap())
                nextUrl?.let { put("nextUrl", it) }

                val isHtmx = call.request.headers["HX-Request"] == "true"
                if (!isHtmx) {
                    layout.putShellModel(
                        call,
                        this,
                        pageTitle = "Privacy flip monitor",
                        activePath = "/admin/privacy-flips",
                    )
                }
            }

        val template =
            if (call.request.headers["HX-Request"] == "true") {
                "privacy-flips-table.peb"
            } else {
                "privacy-flips.peb"
            }
        call.respond(PebbleContent(template, model))
    }
}

private const val STATUS_MAX = 16 // longest allowed value ("in_window") fits well within

// Hygiene cap for the `q` username literal — safely ABOVE the 60-char username
// column width so an over-long value can never collide with a real username
// (it matches nothing → empty state), while a maximum-width 60-char username is
// left intact and still matches exactly. Never truncate at/below 60.
private const val Q_MAX = 100

private val SCHEDULED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * Pre-format a row into display strings for the template. Pebble autoescaping
 * escapes every value on output — including the user-controlled `username` /
 * `displayName` free-text columns, where escaping is load-bearing (design D7).
 * The relative-time string is derived from the SAME `evalInstant` that
 * classified the row, so "overdue by" / "flips in" agrees with the status chip.
 */
private fun PrivacyFlipRow.toViewMap(evalInstant: Instant): Map<String, Any> =
    mapOf(
        "id" to id.toString(),
        "username" to username,
        "displayName" to displayName,
        "isPrivate" to privateProfileOptIn,
        "scheduledAt" to SCHEDULED_AT_FORMAT.format(scheduledAt),
        "deleted" to deleted,
        "status" to status,
        "relativeTime" to relativeTime(evalInstant),
    )

/**
 * Human-readable relative time vs the evaluation instant: "flips in Xh Ym" for
 * an IN_WINDOW row (deadline ahead), "overdue by Xh Ym" for an OVERDUE row
 * (deadline passed). Computed from the absolute gap so it never shows a
 * negative duration.
 */
private fun PrivacyFlipRow.relativeTime(evalInstant: Instant): String {
    val gap = Duration.between(evalInstant, scheduledAt).abs()
    val hours = gap.toHours()
    val minutes = gap.toMinutes() % 60
    val span = "${hours}h ${minutes}m"
    return if (status == STATUS_IN_WINDOW) "flips in $span" else "overdue by $span"
}

/**
 * Render the summary into view-friendly fields. Both buckets are always
 * emitted (defaulting to 0) so a filter-narrowed scope shows the excluded
 * bucket as an explicit `0` rather than dropping it — keeping the OVERDUE
 * anomaly column stable across filters.
 */
private fun PrivacyFlipsSummary.toViewMap(): Map<String, Any> =
    mapOf(
        "total" to total,
        "inWindow" to inWindow,
        "overdue" to overdue,
    )
