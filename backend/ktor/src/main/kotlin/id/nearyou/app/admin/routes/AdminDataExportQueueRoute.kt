package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.dataexportqueue.DataExportCursor
import id.nearyou.app.admin.dataexportqueue.DataExportQuery
import id.nearyou.app.admin.dataexportqueue.DataExportQueueRepository
import id.nearyou.app.admin.dataexportqueue.DataExportRow
import id.nearyou.app.admin.dataexportqueue.TriggerOutcome
import id.nearyou.app.admin.ratelimit.DataExportTriggerRateLimiter
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
 * `GET /admin/data-exports` (the Data Export Queue list) +
 * `POST /admin/data-exports/{id}/trigger` (the manual re-run write) — the
 * `admin-data-export-queue` surface (admin board frame 16). Both wired INSIDE
 * `authenticate(ADMIN_AUTH_NAME)` (AdminModule), so the session middleware gates
 * them (302 → `/admin/login` when unauthenticated).
 *
 * The GET is another read-only-admin-viewer instance (mirrors [adminDeletionQueue]):
 * dual-mode render — `HX-Request: true` swaps only the `data-exports-table.peb`
 * fragment, otherwise the full `data-exports.peb` page (so the feature works without
 * JS and a filtered/paginated URL stays shareable). Any authenticated admin role may
 * read. The projection is identity-only — status, never the archive contents / object
 * key / signed URL (design + spec).
 *
 * The POST mirrors [adminDeletionQueue]'s gate order verbatim — CSRF FIRST
 * ([AdminCsrfGate.validateCsrf]; 403 + `admin_csrf_violation` on miss, BEFORE the role
 * gate so the violation is audited, not masked by a silent role-403) →
 * [AdminRoleGate.requireOwnerOrAdmin] (owner/admin only) → parse the path UUID
 * (malformed → 400, no write) → required `reason` (blank/missing → 400, no write) →
 * the repository transaction (which gates the distinct trigger cap, performs the
 * re-enqueue/confirm state transition + one immutable audit row, then drives the
 * producer's single-request pipeline). Unlike the deletion-queue expedite (a
 * deadline-advance), this trigger RE-RUNS the export through the producer pipeline.
 */
fun Route.adminDataExportQueue(
    repo: DataExportQueueRepository,
    rateLimiter: DataExportTriggerRateLimiter,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/data-exports") {
        call.respondDataExportList(repo, rateLimiter, layout, message = null)
    }

    post("/data-exports/{id}/trigger") {
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
            repo.trigger(
                requestId = requestId,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        val message =
            when (outcome) {
                is TriggerOutcome.Triggered ->
                    when (outcome.processOutcome) {
                        id.nearyou.app.account.DataExportProcessOutcome.FAILED -> MSG_TRIGGER_FAILED
                        else -> MSG_TRIGGER_OK
                    }
                is TriggerOutcome.NotTriggerable -> MSG_NOT_TRIGGERABLE
                TriggerOutcome.AlreadyActive -> MSG_ALREADY_ACTIVE
                TriggerOutcome.Unknown -> MSG_UNKNOWN
                TriggerOutcome.RateLimited -> MSG_RATE_LIMITED
            }
        call.respondDataExportList(repo, rateLimiter, layout, message = message)
    }
}

/** Parse `{id}` as a UUID; null when malformed (→ 400, no write). */
private fun ApplicationCall.parseRequestId(): UUID? = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun ApplicationCall.isHtmx(): Boolean = request.headers[HX_REQUEST] == "true"

/**
 * Query + render the data-export queue for THIS call's query parameters: the HTMX
 * fragment for an `HX-Request`, otherwise the full page (shell + fragment). An optional
 * in-band [message] surfaces a trigger outcome. Filter parsing is lenient (blank `q` /
 * out-of-enum `status` simply match nothing / are ignored; all applied values reach the
 * repository as positionally-bound parameters).
 */
private suspend fun ApplicationCall.respondDataExportList(
    repo: DataExportQueueRepository,
    rateLimiter: DataExportTriggerRateLimiter,
    layout: AdminLayout,
    message: String?,
) {
    val params = request.queryParameters

    // status: one of the five DB statuses; an unknown value is dropped (matches nothing
    // would also be safe, but dropping keeps the filter chip honest).
    val status = params["status"]?.trim()?.takeIf { it in DB_STATUSES }

    // q: a single-user lookup. Blank → ignored. A UUID-shaped value → user-id lookup;
    // otherwise an exact case-insensitive username lookup, length-capped ABOVE the
    // 60-char username column so a legitimate 60-char username still matches.
    val rawQ = params["q"]?.trim()?.takeIf { it.isNotEmpty() }
    val qUserId = rawQ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val qUsername = if (rawQ != null && qUserId == null) rawQ.take(Q_MAX) else null
    val cursor = DataExportCursor.decode(params["cursor"])

    val query = DataExportQuery(status = status, qUserId = qUserId, qUsername = qUsername, cursor = cursor)
    val page = repo.query(query)
    val total = repo.summary(query)

    val activeFilters =
        buildList {
            status?.let { add("status" to it) }
            (qUserId?.toString() ?: qUsername)?.let { add("q" to it) }
        }
    val filters = activeFilters.toMap()

    val nextUrl =
        page.nextCursor?.let { next ->
            val pairs = activeFilters + ("cursor" to next.encode())
            "/admin/data-exports?" +
                pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        }

    val model =
        buildMap<String, Any> {
            put("rows", page.rows.map { it.toViewMap() })
            put("hasRows", page.rows.isNotEmpty())
            put("filters", filters)
            put("total", total)
            put("statuses", DB_STATUSES)
            nextUrl?.let { put("nextUrl", it) }
            message?.let { put("message", it) }
            principal<AdminPrincipal>()?.let { p ->
                put("quotaUsed", rateLimiter.countInTrailingHour(p.adminId))
                put("quotaCap", DataExportTriggerRateLimiter.DATA_EXPORT_TRIGGER_ACTION_CAP)
            }
            if (!isHtmx()) {
                layout.putShellModel(
                    this@respondDataExportList,
                    this,
                    pageTitle = "Data export queue",
                    activePath = "/admin/data-exports",
                )
            }
        }

    val template = if (isHtmx()) FRAGMENT_TEMPLATE else PAGE_TEMPLATE
    respond(PebbleContent(template, model))
}

/**
 * Pre-format a row into display strings for the template. Pebble autoescaping escapes
 * every value on output — including the user-controlled `username`, where escaping is
 * load-bearing. The `usersLink` `q` value is URL-encoded here (not left to the template
 * escaper) so the deep-link stays correct even for a query-significant username. The DB
 * status maps to the frame-16 label + urgency-pill class; the delivered-via + trigger
 * affordances are status-gated.
 */
private fun DataExportRow.toViewMap(): Map<String, Any> =
    buildMap {
        put("id", id.toString())
        put("userIdShort", userId.toString().take(8))
        put("username", username)
        put("usersLink", "/admin/users?q=${URLEncoder.encode(username, Charsets.UTF_8)}")
        put("triggerAction", "/admin/data-exports/$id/trigger")
        put("requestedAt", TS_FORMAT.format(requestedAt))
        put("statusLabel", statusLabel(status))
        put("statusClass", statusClass(status))
        // Delivered-via indicator only once `ready`/DELIVERED (design D2).
        put("delivered", status == DataExportQueueRepository.STATUS_READY)
        // Trigger affordance only for a re-runnable target (`failed`/`pending`); design D1.
        put("triggerable", status in TRIGGERABLE_STATUSES)
    }

/** DB status → frame-16 UI label (design D2). The mapping lives here, not in the DB. */
private fun statusLabel(status: String): String =
    when (status) {
        DataExportQueueRepository.STATUS_PENDING -> "QUEUED"
        DataExportQueueRepository.STATUS_PROCESSING -> "RUNNING"
        DataExportQueueRepository.STATUS_READY -> "DELIVERED"
        DataExportQueueRepository.STATUS_EXPIRED -> "EXPIRED"
        DataExportQueueRepository.STATUS_FAILED -> "FAILED"
        else -> status.uppercase()
    }

/** Status pill urgency class mirroring frame 16's pills. */
private fun statusClass(status: String): String =
    when (status) {
        DataExportQueueRepository.STATUS_PENDING -> "pend"
        DataExportQueueRepository.STATUS_PROCESSING -> "info"
        DataExportQueueRepository.STATUS_READY -> "ok"
        DataExportQueueRepository.STATUS_EXPIRED -> "neut"
        DataExportQueueRepository.STATUS_FAILED -> "err"
        else -> "neut"
    }

private const val HX_REQUEST = "HX-Request"
private const val REASON_FIELD = "reason"
private const val PAGE_TEMPLATE = "data-exports.peb"
private const val FRAGMENT_TEMPLATE = "data-exports-table.peb"

/** The five DB lifecycle statuses, surfaced as the status-filter dropdown options. */
private val DB_STATUSES =
    listOf(
        DataExportQueueRepository.STATUS_PENDING,
        DataExportQueueRepository.STATUS_PROCESSING,
        DataExportQueueRepository.STATUS_READY,
        DataExportQueueRepository.STATUS_EXPIRED,
        DataExportQueueRepository.STATUS_FAILED,
    )

/** Statuses a manual trigger may act on (mirrors the repository's triggerable set). */
private val TRIGGERABLE_STATUSES =
    setOf(DataExportQueueRepository.STATUS_FAILED, DataExportQueueRepository.STATUS_PENDING)

// Hygiene cap for the `q` username literal — safely ABOVE the 60-char username column
// width (never truncate into the matchable range).
private const val Q_MAX = 100

// Server-side cap for the trigger reason (matches the template's client-side maxlength).
// Flows into the unbounded `admin_actions_log.reason` TEXT column, so this is hygiene
// consistency with the q read cap, not an overflow guard.
private const val REASON_MAX = 200

private val TS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

// User-facing informational messages (admin panel is English-only).
private const val MSG_INVALID_ID = "Invalid data-export request id."
private const val MSG_REASON_REQUIRED = "A reason is required."
private const val MSG_TRIGGER_OK =
    "Export re-run triggered. The request was processed through the producer pipeline and delivered (in-app notification + email)."
private const val MSG_TRIGGER_FAILED =
    "Export re-run triggered, but processing failed (e.g. object storage unconfigured). The request is marked FAILED; check the logs."
private const val MSG_NOT_TRIGGERABLE =
    "This request is not re-runnable (it is processing, already delivered, or expired). Nothing was changed."
private const val MSG_ALREADY_ACTIVE =
    "The user already has another active export request, so this one was not re-enqueued. Nothing was changed."
private const val MSG_UNKNOWN =
    "No such export request (it may have been removed when the user was deleted). Nothing was changed."
private const val MSG_RATE_LIMITED = "Trigger quota exceeded (10/hour). Try again later. Nothing was changed."
