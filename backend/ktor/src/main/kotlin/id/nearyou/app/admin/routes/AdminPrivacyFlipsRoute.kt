package id.nearyou.app.admin.routes

import id.nearyou.app.admin.ADMIN_TS_FORMAT
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.privacyflips.ALLOWED_PRIVACY_FLIP_STATUSES
import id.nearyou.app.admin.privacyflips.AdminPrivacyFlipsRepository
import id.nearyou.app.admin.privacyflips.PrivacyFlipRow
import id.nearyou.app.admin.privacyflips.PrivacyFlipsCursor
import id.nearyou.app.admin.privacyflips.PrivacyFlipsQuery
import id.nearyou.app.admin.privacyflips.PrivacyFlipsSummary
import id.nearyou.app.admin.privacyflips.ResolveOutcome
import id.nearyou.app.admin.privacyflips.STATUS_IN_WINDOW
import id.nearyou.app.admin.ratelimit.PrivacyFlipResolveRateLimiter
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `GET /admin/privacy-flips` (the Privacy Flip Monitor list) +
 * `POST /admin/privacy-flips/{user_id}/resolve` (the stuck-row resolve write) —
 * the `admin-privacy-flip-monitor` surface (admin board frame 17).
 *
 * Wired INSIDE the `authenticate(ADMIN_AUTH_NAME) { ... }` block so the
 * `admin-login` session middleware gates it (302 → `/admin/login` when
 * unauthenticated). The bare collection path wires only GET; mutation is
 * confined to the per-user resolve sub-route.
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
 * classification, the `status` filter, the relative-time display, AND the
 * resolve eligibility — so the row's class can never disagree with the
 * `status`-filtered set or with what the resolve accepts (design.md D3).
 *
 * Filter parsing is lenient: an unrecognized / over-long `status` and an
 * empty/blank `q` are ignored, while the remaining valid filters still apply.
 * All applied values reach the repository as positionally-bound
 * `PreparedStatement` parameters, never interpolated SQL.
 *
 * The POST mirrors [adminDeletionQueue]'s gate order verbatim — CSRF FIRST
 * ([AdminCsrfGate.validateCsrf]; 403 + `admin_csrf_violation` on miss, BEFORE the
 * role gate so the violation is audited) → [AdminRoleGate.requireOwnerOrAdmin] →
 * parse the path UUID (400) → required `reason` (400) → the repository
 * transaction (OVERDUE re-check, distinct cap, subscription-aware branch, one
 * audit row). `admin-privacy-flip-resolve` design D1/D2.
 */
fun Route.adminPrivacyFlips(
    repo: AdminPrivacyFlipsRepository,
    rateLimiter: PrivacyFlipResolveRateLimiter,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
    clock: () -> Instant = Instant::now,
) {
    get("/privacy-flips") {
        call.respondPrivacyFlipList(repo, rateLimiter, layout, clock(), message = null)
    }

    post("/privacy-flips/{user_id}/resolve") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val userId =
            call.parameters["user_id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        val reason =
            body[REASON_FIELD]?.trim()?.takeIf { it.isNotEmpty() }?.take(REASON_MAX) ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_REASON_REQUIRED)
                return@post
            }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val evalInstant = clock()
        val outcome =
            repo.resolve(
                userId = userId,
                evalInstant = evalInstant,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        val message =
            when (outcome) {
                ResolveOutcome.Applied -> MSG_APPLIED
                ResolveOutcome.Cleared -> MSG_CLEARED
                ResolveOutcome.Ineligible -> MSG_INELIGIBLE
                ResolveOutcome.RateLimited -> MSG_RATE_LIMITED
            }
        if (outcome.resolution != null && !call.isHtmx()) {
            // PRG for the no-JS path: redirect back to the monitor (the resolved
            // row no longer has a pending flip, so it drops off the list).
            call.response.headers.append(HttpHeaders.Location, "/admin/privacy-flips")
            call.respond(HttpStatusCode.SeeOther)
        } else {
            call.respondPrivacyFlipList(repo, rateLimiter, layout, evalInstant, message)
        }
    }
}

private fun ApplicationCall.isHtmx(): Boolean = request.headers[HX_REQUEST] == "true"

/**
 * Query + render the monitor for THIS call's query parameters: the HTMX
 * fragment for an `HX-Request`, otherwise the full page (shell + fragment). An
 * optional in-band [message] surfaces a resolve outcome. The resolve control +
 * quota chip render only for owner/admin sessions (`canResolve`).
 */
private suspend fun ApplicationCall.respondPrivacyFlipList(
    repo: AdminPrivacyFlipsRepository,
    rateLimiter: PrivacyFlipResolveRateLimiter,
    layout: AdminLayout,
    evalInstant: Instant,
    message: String?,
) {
    val params = request.queryParameters

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

    val principal = principal<AdminPrincipal>()
    // Owner/admin only: the resolve control + quota chip render for this role.
    val canResolve = principal?.role in AdminRoleGate.OWNER_ADMIN_ROLES

    val model =
        buildMap<String, Any> {
            put("rows", page.rows.map { it.toViewMap(evalInstant) })
            put("hasRows", page.rows.isNotEmpty())
            put("filters", filters)
            put("summary", summary.toViewMap())
            put("canResolve", canResolve)
            nextUrl?.let { put("nextUrl", it) }
            message?.let { put("message", it) }
            if (canResolve && principal != null) {
                put("quotaUsed", rateLimiter.countInTrailingHour(principal.adminId))
                put("quotaCap", PrivacyFlipResolveRateLimiter.PRIVACY_FLIP_RESOLVE_CAP)
            }
            if (!isHtmx()) {
                layout.putShellModel(
                    this@respondPrivacyFlipList,
                    this,
                    pageTitle = "Privacy flip monitor",
                    activePath = "/admin/privacy-flips",
                )
            }
        }

    val template = if (isHtmx()) FRAGMENT_TEMPLATE else PAGE_TEMPLATE
    respond(PebbleContent(template, model))
}

private const val HX_REQUEST = "HX-Request"
private const val PAGE_TEMPLATE = "privacy-flips.peb"
private const val FRAGMENT_TEMPLATE = "privacy-flips-table.peb"
private const val REASON_FIELD = "reason"

// Server-side cap for the resolve reason (matches the template's maxlength). It
// flows into the unbounded `admin_actions_log.reason` TEXT column, so this is
// hygiene/consistency with the sibling writes, not an overflow guard.
private const val REASON_MAX = 200

// User-facing informational messages (admin panel is English-only).
private const val MSG_INVALID_ID = "Invalid user id."
private const val MSG_REASON_REQUIRED = "A reason is required."
private const val MSG_APPLIED = "Flip applied: the profile opt-in was reset and the schedule cleared (the subscription reads free)."
private const val MSG_CLEARED =
    "Schedule cleared; the privacy opt-in was kept because the stored subscription reads premium. " +
        "If that looks stale, check the Subscription Grace monitor / RevenueCat."
private const val MSG_INELIGIBLE =
    "Not resolvable: the user has no overdue flip (still in window, already resolved, or unknown). Nothing was changed."
private const val MSG_RATE_LIMITED = "Resolve quota exceeded (10/hour). Try again later. Nothing was changed."

private const val STATUS_MAX = 16 // longest allowed value ("in_window") fits well within

// Hygiene cap for the `q` username literal — safely ABOVE the 60-char username
// column width so an over-long value can never collide with a real username
// (it matches nothing → empty state), while a maximum-width 60-char username is
// left intact and still matches exactly. Never truncate at/below 60.
private const val Q_MAX = 100

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
        // The deep-link target's `q` value is URL-encoded here (not left to the
        // template's HTML-escaper) so the link stays correct even if a username
        // ever carries a query-significant character (`&` / `#` / space) —
        // robust independent of the signup-time username charset constraint, and
        // consistent with the `nextUrl` encoding above.
        "usersLink" to "/admin/users?q=${URLEncoder.encode(username, Charsets.UTF_8)}",
        "displayName" to displayName,
        "isPrivate" to privateProfileOptIn,
        "scheduledAt" to ADMIN_TS_FORMAT.format(scheduledAt),
        "deleted" to deleted,
        "status" to status,
        "relativeTime" to relativeTime(evalInstant),
        "resolveAction" to "/admin/privacy-flips/$id/resolve",
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
