package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.deletionqueue.DeletionCursor
import id.nearyou.app.admin.deletionqueue.DeletionQuery
import id.nearyou.app.admin.deletionqueue.DeletionQueueRepository
import id.nearyou.app.admin.deletionqueue.DeletionRow
import id.nearyou.app.admin.deletionqueue.ExpediteOutcome
import id.nearyou.app.admin.ratelimit.DeletionQueueExpediteRateLimiter
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `GET /admin/deletion-requests` (the Hard Delete Queue list) +
 * `POST /admin/deletion-requests/{id}/expedite` (the manual-expedite write) —
 * the `admin-hard-delete-queue` surface (admin board frame 15). Both wired
 * INSIDE `authenticate(ADMIN_AUTH_NAME)` (AdminModule), so the session middleware
 * gates them (302 → `/admin/login` when unauthenticated).
 *
 * The GET is the 6th read-only-admin-viewer instance (mirrors [adminSubscriptionGrace]):
 * dual-mode render — `HX-Request: true` swaps only the `deletion-requests-table.peb`
 * fragment (filter form + layout stay put), otherwise the full `deletion-requests.peb`
 * page (so the feature works without JS and a filtered/paginated URL stays shareable).
 * Any authenticated admin role may read.
 *
 * The POST mirrors [adminSubscriptionGrace]'s gate order verbatim — CSRF FIRST
 * ([AdminCsrfGate.validateCsrf]; 403 + `admin_csrf_violation` on miss, BEFORE the
 * role gate so the violation is audited, not masked by a silent role-403) →
 * [AdminRoleGate.requireOwnerOrAdmin] (owner/admin only) → parse the path UUID
 * (malformed → 400, no write) → required `reason` (blank/missing → 400, no write)
 * → the repository transaction (which validates the pending/future target state,
 * gates the distinct expedite cap, advances the deadline, and writes one immutable
 * audit row). Unlike the grace expedite, this one MUTATES — it advances
 * `scheduled_hard_delete_at` so the existing daily worker erases the account; the
 * route itself never tombstones or cascades.
 */
fun Route.adminDeletionQueue(
    repo: DeletionQueueRepository,
    rateLimiter: DeletionQueueExpediteRateLimiter,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
    clock: () -> Instant = Instant::now,
) {
    get("/deletion-requests") {
        call.respondDeletionList(repo, rateLimiter, layout, clock(), message = null)
    }

    post("/deletion-requests/{id}/expedite") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val requestId =
            call.parseRequestId() ?: run {
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

        val outcome =
            repo.expedite(
                requestId = requestId,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            ExpediteOutcome.Applied ->
                if (call.isHtmx()) {
                    call.respondDeletionList(repo, rateLimiter, layout, clock(), message = MSG_EXPEDITED_OK)
                } else {
                    // PRG for the no-JS path: redirect back to the list (the row
                    // now shows the handled indicator).
                    call.response.headers.append(HttpHeaders.Location, "/admin/deletion-requests")
                    call.respond(HttpStatusCode.SeeOther)
                }
            ExpediteOutcome.Ineligible ->
                call.respondDeletionList(repo, rateLimiter, layout, clock(), message = MSG_INELIGIBLE)
            ExpediteOutcome.RateLimited ->
                call.respondDeletionList(repo, rateLimiter, layout, clock(), message = MSG_RATE_LIMITED)
        }
    }
}

/** Parse `{id}` as a UUID; null when malformed (→ 400, no write). */
private fun ApplicationCall.parseRequestId(): UUID? = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun ApplicationCall.isHtmx(): Boolean = request.headers[HX_REQUEST] == "true"

/**
 * Query + render the deletion queue for THIS call's query parameters: the HTMX
 * fragment for an `HX-Request`, otherwise the full page (shell + fragment). An
 * optional in-band [message] surfaces an expedite outcome (success / ineligible /
 * rate-limited). Filter parsing is lenient (blank `q` / over-long values ignored;
 * all applied values reach the repository as positionally-bound parameters). The
 * [now] instant (from the route clock) computes each row's countdown.
 */
private suspend fun ApplicationCall.respondDeletionList(
    repo: DeletionQueueRepository,
    rateLimiter: DeletionQueueExpediteRateLimiter,
    layout: AdminLayout,
    now: Instant,
    message: String?,
) {
    val params = request.queryParameters

    // q: a single-user lookup. Blank → ignored (NOT bound as `username = ''`). A
    // UUID-shaped value → user-id lookup; otherwise an exact case-insensitive
    // username lookup, length-capped ABOVE the 60-char username column so a
    // legitimate 60-char username still matches while an over-long value matches
    // nothing.
    val rawQ = params["q"]?.trim()?.takeIf { it.isNotEmpty() }
    val qUserId = rawQ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val qUsername = if (rawQ != null && qUserId == null) rawQ.take(Q_MAX) else null
    val source = params["source"]?.trim()?.takeIf { it.isNotEmpty() }?.take(SOURCE_MAX)
    val cursor = DeletionCursor.decode(params["cursor"])

    val query = DeletionQuery(qUserId = qUserId, qUsername = qUsername, source = source, cursor = cursor)
    val page = repo.query(query)
    val total = repo.summary(query)

    val activeFilters =
        buildList {
            (qUserId?.toString() ?: qUsername)?.let { add("q" to it) }
            source?.let { add("source" to it) }
        }
    val filters = activeFilters.toMap()

    val nextUrl =
        page.nextCursor?.let { next ->
            val pairs = activeFilters + ("cursor" to next.encode())
            "/admin/deletion-requests?" +
                pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        }

    val model =
        buildMap<String, Any> {
            put("rows", page.rows.map { it.toViewMap(now) })
            put("hasRows", page.rows.isNotEmpty())
            put("filters", filters)
            put("total", total)
            nextUrl?.let { put("nextUrl", it) }
            message?.let { put("message", it) }
            principal<AdminPrincipal>()?.let { p ->
                put("quotaUsed", rateLimiter.countInTrailingHour(p.adminId))
                put("quotaCap", DeletionQueueExpediteRateLimiter.DELETION_EXPEDITE_ACTION_CAP)
            }
            if (!isHtmx()) {
                layout.putShellModel(
                    this@respondDeletionList,
                    this,
                    pageTitle = "Hard delete queue",
                    activePath = "/admin/deletion-requests",
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
 * query-significant username. The countdown + its urgency class are computed
 * against [now].
 */
private fun DeletionRow.toViewMap(now: Instant): Map<String, Any> =
    buildMap {
        put("id", id.toString())
        put("userIdShort", userId.toString().take(8))
        put("username", username)
        put("usersLink", "/admin/users?q=${URLEncoder.encode(username, Charsets.UTF_8)}")
        put("expediteAction", "/admin/deletion-requests/$id/expedite")
        put("requestedAt", TS_FORMAT.format(requestedAt))
        put("scheduledAt", TS_FORMAT.format(scheduledHardDeleteAt))
        put("source", source)
        put("countdown", countdownLabel(now, scheduledHardDeleteAt))
        put("countdownClass", countdownClass(now, scheduledHardDeleteAt))
        put("dueNow", !scheduledHardDeleteAt.isAfter(now))
        put("expedited", expeditedBy != null && expeditedAt != null)
        if (expeditedBy != null && expeditedAt != null) {
            put("expeditedBy", expeditedBy)
            put("expeditedAt", TS_FORMAT.format(expeditedAt))
        }
    }

/** Human countdown to the scheduled hard-delete; "Due now" once the deadline has arrived. */
private fun countdownLabel(
    now: Instant,
    deadline: Instant,
): String {
    if (!deadline.isAfter(now)) return "Due now"
    val d = Duration.between(now, deadline)
    val days = d.toDays()
    if (days >= 1) return if (days == 1L) "1 day" else "$days days"
    val hours = d.toHours()
    if (hours >= 1) return if (hours == 1L) "1 hour" else "$hours hours"
    val minutes = d.toMinutes()
    return if (minutes <= 1L) "< 1 minute" else "$minutes minutes"
}

/**
 * Urgency class mirroring frame 15's status pills: `pend` (amber — imminent or
 * due, ≤ 3 days) vs `neut` (further out). Drives the `st {{ countdownClass }}`
 * span in the template.
 */
private fun countdownClass(
    now: Instant,
    deadline: Instant,
): String = if (Duration.between(now, deadline).toDays() <= IMMINENT_DAYS) "pend" else "neut"

private const val HX_REQUEST = "HX-Request"
private const val REASON_FIELD = "reason"
private const val PAGE_TEMPLATE = "deletion-requests.peb"
private const val FRAGMENT_TEMPLATE = "deletion-requests-table.peb"

// Hygiene cap for the `q` username literal — safely ABOVE the 60-char username
// column width (never truncate into the matchable range). `source` cap matches
// the V27 `source VARCHAR(32)` column width.
private const val Q_MAX = 100
private const val SOURCE_MAX = 32

// Server-side cap for the expedite reason (matches the template's client-side
// maxlength). Flows into the unbounded `admin_actions_log.reason` TEXT column, so
// this is hygiene/consistency with the q/source read caps, not an overflow guard.
private const val REASON_MAX = 200

// Countdown rows within this many days render with the amber `pend` urgency pill.
private const val IMMINENT_DAYS = 3L

private val TS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

// User-facing informational messages (admin panel is English-only).
private const val MSG_INVALID_ID = "Invalid deletion-request id."
private const val MSG_REASON_REQUIRED = "A reason is required."
private const val MSG_EXPEDITED_OK =
    "Expedite recorded. The account is now scheduled for the next hard-delete worker run (≤24h); the deletion is irreversible."
private const val MSG_INELIGIBLE =
    "This request is no longer pending (already executed, cancelled, or due). Nothing was changed."
private const val MSG_RATE_LIMITED = "Expedite quota exceeded (10/hour). Try again later. Nothing was changed."
