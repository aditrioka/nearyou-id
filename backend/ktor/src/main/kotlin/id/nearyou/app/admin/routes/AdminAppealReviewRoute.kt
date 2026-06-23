package id.nearyou.app.admin.routes

import id.nearyou.app.admin.appealreview.AppealQueueRow
import id.nearyou.app.admin.appealreview.AppealReviewRepository
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
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
import java.util.UUID

/**
 * `GET /admin/appeals` (pending review queue) + `POST /admin/appeals/{id}/approve`
 * + `POST /admin/appeals/{id}/reject` — the admin appeals-review surface
 * (`admin-appeal-review`). All wired INSIDE `authenticate(ADMIN_AUTH_NAME)`, so the
 * `admin-login` session middleware gates them (302 → `/admin/login` when
 * unauthenticated).
 *
 * The two POSTs are state-changing, so they follow the established admin gate order
 * (mirrors [adminReportResolution], design D6): CSRF FIRST
 * ([AdminCsrfGate.validateCsrf] — 403 + `admin_csrf_violation` on miss), THEN the
 * base write-role gate ([AdminRoleGate.requireWriteRole]), THEN the path UUID
 * (malformed → 400, no write), THEN the repository transaction. Approve/reject are
 * NOT counted by the destructive-action cap (design D6 — approve is restorative,
 * reject alters no user state).
 *
 * Post-Redirect-Get (no-JS path): every decision outcome 303-redirects back to
 * `/admin/appeals`, so the re-queried queue reflects the result (a no-op /
 * not-found simply shows the current pending set). The unstyled surface (the admin
 * board has no appeal frame yet — docs/11 §3.6) uses plain forms; HTMX follows the
 * redirect identically.
 */
fun Route.adminAppealReview(
    repository: AppealReviewRepository,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/appeals") {
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val rows = repository.listPending(limit = PAGE_SIZE, offset = offset)
        val model =
            buildMap<String, Any> {
                put("rows", rows.map { it.toViewMap() })
                put("hasRows", rows.isNotEmpty())
                if (rows.size == PAGE_SIZE) put("nextOffset", offset + PAGE_SIZE)
                if (offset > 0) put("prevOffset", (offset - PAGE_SIZE).coerceAtLeast(0))
                layout.putShellModel(call, this, pageTitle = "Appeals", activePath = "/admin/appeals")
            }
        call.respond(HttpStatusCode.OK, PebbleContent("appeals.peb", model))
    }

    post("/appeals/{id}/approve") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        val appealId =
            call.parseAppealId() ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
        repository.approve(
            appealId = appealId,
            actingAdminId = principal.adminId,
            ip = call.clientIp,
            userAgent = call.request.headers[HttpHeaders.UserAgent],
        )
        call.redirectToQueue()
    }

    post("/appeals/{id}/reject") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireWriteRole(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val appealId =
            call.parseAppealId() ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        val reason = body["reason"]?.trim()?.takeIf { it.isNotEmpty() }
        if (reason != null && reason.length > MAX_REASON) {
            call.respond(HttpStatusCode.BadRequest, MSG_REASON_TOO_LONG)
            return@post
        }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
        repository.reject(
            appealId = appealId,
            actingAdminId = principal.adminId,
            decisionReason = reason,
            ip = call.clientIp,
            userAgent = call.request.headers[HttpHeaders.UserAgent],
        )
        call.redirectToQueue()
    }
}

private fun AppealQueueRow.toViewMap(): Map<String, Any> =
    mapOf(
        "id" to id.toString(),
        "userId" to userId.toString(),
        "actionType" to actionType,
        "appealText" to appealText,
        "createdAt" to createdAt.toString(),
    )

/** Parse the `{id}` path segment as a UUID; null when malformed (→ 400, no write). */
private fun ApplicationCall.parseAppealId(): UUID? = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** Post-Redirect-Get back to the pending queue (303 SeeOther). */
private suspend fun ApplicationCall.redirectToQueue() {
    response.headers.append(HttpHeaders.Location, "/admin/appeals")
    respond(HttpStatusCode.SeeOther)
}

private const val PAGE_SIZE = 50
private const val MAX_REASON = 1000

private const val MSG_INVALID_ID = "Invalid id."
private const val MSG_REASON_TOO_LONG = "Decision reason must be 1000 characters or fewer."
