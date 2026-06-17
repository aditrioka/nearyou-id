package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.ratelimit.GraceExpediteActionRateLimiter
import id.nearyou.app.admin.subscriptiongrace.ExpediteOutcome
import id.nearyou.app.admin.subscriptiongrace.GraceCursor
import id.nearyou.app.admin.subscriptiongrace.GraceQuery
import id.nearyou.app.admin.subscriptiongrace.GraceRow
import id.nearyou.app.admin.subscriptiongrace.SubscriptionGraceRepository
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `GET /admin/subscriptions/grace` (the Subscription Grace Monitor list) +
 * `POST /admin/subscriptions/grace/{user_id}/expedite` (the manual-expedite
 * bookkeeping write) — the `admin-subscription-grace-monitor` surface (admin
 * board frame 18). Both wired INSIDE `authenticate(ADMIN_AUTH_NAME)` (AdminModule),
 * so the session middleware gates them (302 → `/admin/login` when unauthenticated).
 *
 * The GET is the 5th read-only-admin-viewer instance (mirrors [adminPrivacyFlips]):
 * dual-mode render — `HX-Request: true` swaps only the `subscription-grace-table.peb`
 * fragment (filter form + layout stay put), otherwise the full `subscription-grace.peb`
 * page (so the feature works without JS and a filtered/paginated URL stays shareable).
 * Any authenticated admin role may read.
 *
 * The POST mirrors [adminChatRedaction]'s gate order verbatim — CSRF FIRST
 * ([AdminCsrfGate.validateCsrf]; 403 + `admin_csrf_violation` on miss, BEFORE the
 * role gate so the violation is audited, not masked by a silent role-403) →
 * [AdminRoleGate.requireOwnerOrAdmin] (owner/admin only) → parse the path UUID
 * (malformed → 400, no write) → required `ticket_ref` (blank/missing → 400, no
 * write) → the repository transaction (which validates the billing-retry target
 * state, gates the distinct expedite cap, and writes one immutable audit row).
 * Expedite is BOOKKEEPING ONLY — it never mutates entitlement.
 */
fun Route.adminSubscriptionGrace(
    repo: SubscriptionGraceRepository,
    rateLimiter: GraceExpediteActionRateLimiter,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/subscriptions/grace") {
        call.respondGraceList(repo, rateLimiter, layout, message = null)
    }

    post("/subscriptions/grace/{user_id}/expedite") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val userId =
            call.parseUserId() ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        val ticketRef =
            body[TICKET_FIELD]?.trim()?.takeIf { it.isNotEmpty() }?.take(TICKET_MAX) ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_TICKET_REQUIRED)
                return@post
            }
        val note = body[NOTE_FIELD]?.trim()?.takeIf { it.isNotEmpty() }?.take(NOTE_MAX)
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val outcome =
            repo.expedite(
                userId = userId,
                actingAdminId = principal.adminId,
                ticketRef = ticketRef,
                note = note,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            ExpediteOutcome.Applied ->
                if (call.isHtmx()) {
                    call.respondGraceList(repo, rateLimiter, layout, message = MSG_EXPEDITED_OK)
                } else {
                    // PRG for the no-JS path: redirect back to the list (the row
                    // now shows the handled indicator).
                    call.response.headers.append(HttpHeaders.Location, "/admin/subscriptions/grace")
                    call.respond(HttpStatusCode.SeeOther)
                }
            ExpediteOutcome.Ineligible ->
                call.respondGraceList(repo, rateLimiter, layout, message = MSG_INELIGIBLE)
            ExpediteOutcome.RateLimited ->
                call.respondGraceList(repo, rateLimiter, layout, message = MSG_RATE_LIMITED)
        }
    }
}

/** Parse `{user_id}` as a UUID; null when malformed (→ 400, no write). */
private fun ApplicationCall.parseUserId(): UUID? = parameters["user_id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun ApplicationCall.isHtmx(): Boolean = request.headers[HX_REQUEST] == "true"

/**
 * Query + render the grace list for THIS call's query parameters: the HTMX
 * fragment for an `HX-Request`, otherwise the full page (shell + fragment). An
 * optional in-band [message] surfaces an expedite outcome (success / ineligible /
 * rate-limited). Filter parsing is lenient (blank `q` / over-long values ignored;
 * all applied values reach the repository as positionally-bound parameters).
 */
private suspend fun ApplicationCall.respondGraceList(
    repo: SubscriptionGraceRepository,
    rateLimiter: GraceExpediteActionRateLimiter,
    layout: AdminLayout,
    message: String?,
) {
    val params = request.queryParameters

    // q: a single-user lookup. Blank → ignored (NOT bound as `username = ''`). A
    // UUID-shaped value → id lookup; otherwise an exact case-insensitive username
    // lookup, length-capped ABOVE the 60-char username column so a legitimate
    // 60-char username still matches while an over-long value matches nothing.
    val rawQ = params["q"]?.trim()?.takeIf { it.isNotEmpty() }
    val qUserId = rawQ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val qUsername = if (rawQ != null && qUserId == null) rawQ.take(Q_MAX) else null
    val store = params["store"]?.trim()?.takeIf { it.isNotEmpty() }?.take(STORE_MAX)
    val cursor = GraceCursor.decode(params["cursor"])

    val query = GraceQuery(qUserId = qUserId, qUsername = qUsername, store = store, cursor = cursor)
    val page = repo.query(query)
    val total = repo.summary(query)

    val activeFilters =
        buildList {
            (qUserId?.toString() ?: qUsername)?.let { add("q" to it) }
            store?.let { add("store" to it) }
        }
    val filters = activeFilters.toMap()

    val nextUrl =
        page.nextCursor?.let { next ->
            val pairs = activeFilters + ("cursor" to next.encode())
            "/admin/subscriptions/grace?" +
                pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        }

    val model =
        buildMap<String, Any> {
            put("rows", page.rows.map { it.toViewMap() })
            put("hasRows", page.rows.isNotEmpty())
            put("filters", filters)
            put("total", total)
            nextUrl?.let { put("nextUrl", it) }
            message?.let { put("message", it) }
            principal<AdminPrincipal>()?.let { p ->
                put("quotaUsed", rateLimiter.countInTrailingHour(p.adminId))
                put("quotaCap", GraceExpediteActionRateLimiter.GRACE_EXPEDITE_ACTION_CAP)
            }
            if (!isHtmx()) {
                layout.putShellModel(
                    this@respondGraceList,
                    this,
                    pageTitle = "Subscription grace monitor",
                    activePath = "/admin/subscriptions/grace",
                )
            }
        }

    val template = if (isHtmx()) FRAGMENT_TEMPLATE else PAGE_TEMPLATE
    respond(PebbleContent(template, model))
}

/**
 * Pre-format a row into display strings for the template. Pebble autoescaping
 * escapes every value on output — including the user-controlled `username`, where
 * escaping is load-bearing. The `usersLink` `q` value is URL-encoded here (not
 * left to the template escaper) so the deep-link stays correct even for a
 * query-significant username.
 */
private fun GraceRow.toViewMap(): Map<String, Any> =
    buildMap {
        put("id", id.toString())
        put("username", username)
        put("usersLink", "/admin/users?q=${URLEncoder.encode(username, Charsets.UTF_8)}")
        put("expediteAction", "/admin/subscriptions/grace/$id/expedite")
        put("store", platform ?: "")
        put("retrySince", retrySince?.let { TS_FORMAT.format(it) } ?: "")
        put(
            "lastWebhook",
            if (lastEventType != null && lastEventAt != null) {
                "$lastEventType · ${TS_FORMAT.format(lastEventAt)}"
            } else {
                ""
            },
        )
        put("expedited", expeditedBy != null && expeditedAt != null)
        if (expeditedBy != null && expeditedAt != null) {
            put("expeditedBy", expeditedBy)
            put("expeditedAt", TS_FORMAT.format(expeditedAt))
        }
    }

private const val HX_REQUEST = "HX-Request"
private const val TICKET_FIELD = "ticket_ref"
private const val NOTE_FIELD = "note"
private const val PAGE_TEMPLATE = "subscription-grace.peb"
private const val FRAGMENT_TEMPLATE = "subscription-grace-table.peb"

// Hygiene cap for the `q` username literal — safely ABOVE the 60-char username
// column width (never truncate into the matchable range). `store` cap matches
// the V21 `platform VARCHAR(16)` column width.
private const val Q_MAX = 100
private const val STORE_MAX = 16

// Server-side caps for the expedite write fields (match the template's
// client-side maxlengths). Both flow into the unbounded `admin_actions_log.reason`
// TEXT column, so this is hygiene/consistency with the q/store read caps, not an
// overflow guard.
private const val TICKET_MAX = 64
private const val NOTE_MAX = 200

private val TS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

// User-facing informational messages (admin panel is English-only).
private const val MSG_INVALID_ID = "Invalid user id."
private const val MSG_TICKET_REQUIRED = "A support-ticket reference is required."
private const val MSG_EXPEDITED_OK = "Expedite recorded. Entitlement is unchanged (RevenueCat remains the source of truth)."
private const val MSG_INELIGIBLE = "User is not in the billing-retry window. Nothing was recorded."
private const val MSG_RATE_LIMITED = "Expedite quota exceeded (20/hour). Try again later. Nothing was recorded."
