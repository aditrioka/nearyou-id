package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.ratelimit.ReferralGrantActionRateLimiter
import id.nearyou.app.admin.referralgrants.AdminReferralGrantRepository
import id.nearyou.app.admin.referralgrants.GrantOutcome
import id.nearyou.app.admin.referralgrants.GrantRow
import id.nearyou.app.admin.referralgrants.GrantsCursor
import id.nearyou.app.admin.referralgrants.GrantsQuery
import id.nearyou.app.admin.referralgrants.ReferralGrantUserContext
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
 * `GET /admin/referral-grants` (lookup + past-grants viewer) +
 * `POST /admin/referral-grants` (the manual grant write) — the
 * `admin-referral-manual-grant` surface (admin board frame 19). Both wired
 * INSIDE `authenticate(ADMIN_AUTH_NAME)` (AdminModule), so the session
 * middleware 302s an unauthenticated request to `/admin/login` before any
 * handler logic runs.
 *
 * The GET is the Nth read-only-admin-viewer instance (mirrors [adminSubscriptionGrace]
 * / [adminFeatureFlags]): dual-mode render — `HX-Request: true` swaps only the
 * `referral-grants-content.peb` fragment (lookup context + grant form + past
 * grants), otherwise the full `referral-grants.peb` page (so the feature works
 * without JS and a filtered/paginated URL stays shareable). A single `q` param
 * does double duty: it resolves the recipient (context + grant form) AND scopes
 * the past-grants list to that grantee. Any authenticated admin role may read.
 *
 * The POST mirrors [adminSubscriptionGrace]'s gate order verbatim — CSRF FIRST
 * ([AdminCsrfGate.validateCsrf]; 403 + `admin_csrf_violation` on miss, BEFORE
 * the role gate so the violation is audited, not masked by a silent role-403) →
 * [AdminRoleGate.requireOwnerOrAdmin] (owner/admin only) → parse `user_id`
 * (malformed → 400, no write) → required `reason` (blank → 400, no write) → the
 * repository grant flow (rate-limit gate → RC dispatch → one immutable audit row).
 */
fun Route.adminReferralGrants(
    repo: AdminReferralGrantRepository,
    rateLimiter: ReferralGrantActionRateLimiter,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/referral-grants") {
        call.respondReferralGrants(repo, rateLimiter, layout, message = null)
    }

    post("/referral-grants") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val userId =
            body["user_id"]?.trim()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        val reason =
            body["reason"]?.trim()?.takeIf { it.isNotEmpty() }?.take(REASON_MAX) ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_REASON_REQUIRED)
                return@post
            }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val outcome =
            repo.grant(
                userId = userId,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        val message =
            when (outcome) {
                is GrantOutcome.Dispatched -> MSG_DISPATCHED
                is GrantOutcome.DispatchSkipped -> MSG_DISPATCH_SKIPPED
                is GrantOutcome.DispatchFailed -> "$MSG_DISPATCH_FAILED (${outcome.reason})"
                GrantOutcome.NotFound -> MSG_NOT_FOUND
                GrantOutcome.DeletedUser -> MSG_DELETED_USER
                GrantOutcome.RateLimited -> MSG_RATE_LIMITED
            }
        val recorded = outcome is GrantOutcome.Dispatched || outcome is GrantOutcome.DispatchSkipped
        if (call.isHtmx() || !recorded) {
            // Failure outcomes render WITH the message on the no-JS path too
            // (mirrors the grace precedent) — a silent PRG would swallow them.
            call.respondReferralGrants(repo, rateLimiter, layout, message = message)
        } else {
            // PRG for the no-JS success path: the list re-renders with the new row.
            call.response.headers.append(HttpHeaders.Location, "/admin/referral-grants")
            call.respond(HttpStatusCode.SeeOther)
        }
    }
}

private fun ApplicationCall.isHtmx(): Boolean = request.headers["HX-Request"] == "true"

/**
 * Query + render the surface for THIS call's parameters: the HTMX content
 * fragment for an `HX-Request`, otherwise the full page. `q` resolves the
 * recipient (context + grant form) and scopes the past-grants list; `date_from`
 * / `date_to` (UTC `yyyy-MM-dd`) filter the list. Parsing is lenient — blank /
 * malformed values are dropped; every applied value reaches the repository as a
 * positionally-bound parameter.
 */
private suspend fun ApplicationCall.respondReferralGrants(
    repo: AdminReferralGrantRepository,
    rateLimiter: ReferralGrantActionRateLimiter,
    layout: AdminLayout,
    message: String?,
) {
    val params = request.queryParameters
    val rawQ = params["q"]?.trim()?.takeIf { it.isNotEmpty() }
    val user = rawQ?.let { repo.lookup(it) }

    // Scope the past-grants list to the queried grantee: by resolved id when the
    // lookup hit, else by a raw UUID, else an exact case-insensitive username.
    val qUserId = user?.id ?: rawQ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val qUsername = if (qUserId == null && rawQ != null) rawQ.take(Q_MAX) else null
    val dateFrom = params["date_from"]?.let { parseUtcDate(it) }
    val dateTo = params["date_to"]?.let { parseUtcDate(it)?.plusSeconds(DAY_SECONDS) } // inclusive end
    val cursor = GrantsCursor.decode(params["cursor"])

    val query =
        GrantsQuery(
            qUserId = qUserId,
            qUsername = qUsername,
            dateFrom = dateFrom,
            dateTo = dateTo,
            cursor = cursor,
        )
    val page = repo.pastGrants(query)

    val activeFilters =
        buildList {
            rawQ?.let { add("q" to it) }
            params["date_from"]?.trim()?.takeIf { it.isNotEmpty() }?.let { add("date_from" to it) }
            params["date_to"]?.trim()?.takeIf { it.isNotEmpty() }?.let { add("date_to" to it) }
        }
    val nextUrl =
        page.nextCursor?.let { next ->
            val pairs = activeFilters + ("cursor" to next.encode())
            "/admin/referral-grants?" +
                pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        }

    val model =
        buildMap<String, Any> {
            put("q", rawQ ?: "")
            put("filters", activeFilters.toMap())
            user?.let { put("user", it.toViewMap()) }
            put("noMatch", rawQ != null && user == null)
            put("rows", page.rows.map { it.toViewMap() })
            put("hasRows", page.rows.isNotEmpty())
            nextUrl?.let { put("nextUrl", it) }
            message?.let { put("message", it) }
            principal<AdminPrincipal>()?.let { p ->
                put("quotaUsed", rateLimiter.countInTrailingHour(p.adminId))
                put("quotaCap", ReferralGrantActionRateLimiter.REFERRAL_MANUAL_GRANT_CAP)
            }
            if (!isHtmx()) {
                layout.putShellModel(
                    this@respondReferralGrants,
                    this,
                    pageTitle = "Referral manual grant",
                    activePath = "/admin/referral-grants",
                )
            }
        }

    val template = if (isHtmx()) FRAGMENT_TEMPLATE else PAGE_TEMPLATE
    respond(PebbleContent(template, model))
}

/** Parse a lenient `yyyy-MM-dd` UTC date to the start-of-day Instant; null when malformed. */
private fun parseUtcDate(raw: String): java.time.Instant? =
    raw.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
    }

private fun ReferralGrantUserContext.toViewMap(): Map<String, Any> =
    buildMap {
        put("id", id.toString())
        put("username", username)
        put("subscriptionStatus", subscriptionStatus)
        put("entitlementEnd", entitlementEnd?.let { TS_FORMAT.format(it) } ?: "")
        put("grantsAsInvitee", referralGrantsAsInvitee)
        put("grantsAsInviter", referralGrantsAsInviter)
        put("deleted", deleted)
    }

private fun GrantRow.toViewMap(): Map<String, Any> =
    buildMap {
        put("createdAt", TS_FORMAT.format(createdAt))
        put("grantee", granteeUsername ?: "(deleted · $granteeId)")
        put("reason", reason ?: "")
        put("adminName", adminName)
        put("dispatch", dispatch ?: "")
    }

private const val PAGE_TEMPLATE = "referral-grants.peb"
private const val FRAGMENT_TEMPLATE = "referral-grants-content.peb"

// Hygiene cap for the `q` username literal — safely ABOVE the 60-char username
// column so a legitimate 60-char username still matches while an over-long value
// matches nothing. `reason` caps the required ticket-ref field (flows into the
// unbounded `admin_actions_log.reason` TEXT column — hygiene, not overflow).
private const val Q_MAX = 100
private const val REASON_MAX = 200
private const val DAY_SECONDS = 86_400L

private val TS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

// User-facing informational messages (admin panel is English-only).
private const val MSG_INVALID_ID = "Invalid user id."
private const val MSG_REASON_REQUIRED = "A support-ticket reference is required."
private const val MSG_DISPATCHED =
    "Grant recorded. Premium activates on RevenueCat confirmation (the GRANT webhook echo)."
private const val MSG_DISPATCH_SKIPPED =
    "Grant recorded; dispatch skipped — RevenueCat is not configured in this environment."
private const val MSG_DISPATCH_FAILED =
    "Grant recorded, but RevenueCat rejected the dispatch — Premium did not activate. Retry after checking RevenueCat."
private const val MSG_NOT_FOUND = "No user matches that id. Nothing was granted."
private const val MSG_DELETED_USER = "That account is deleted (tombstoned). Nothing was granted."
private val MSG_RATE_LIMITED =
    "Manual-grant quota exceeded (${ReferralGrantActionRateLimiter.REFERRAL_MANUAL_GRANT_CAP}/hour). " +
        "Try again later. Nothing was granted."
