package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.chatredaction.ChatRedactionContextMessage
import id.nearyou.app.admin.chatredaction.ChatRedactionPageData
import id.nearyou.app.admin.chatredaction.ChatRedactionRepository
import id.nearyou.app.admin.chatredaction.RedactionOutcome
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `GET /admin/chat-messages/{id}` (redaction confirmation page, reported message
 * ±2 context) + `POST /admin/chat-messages/{id}/redact` (the redaction write) —
 * the `admin-chat-message-redaction` surface (admin mockup frame 9). Both are
 * wired INSIDE `authenticate(ADMIN_AUTH_NAME)` (AdminModule), so the session
 * middleware gates them.
 *
 * The whole surface is OWNER/ADMIN tier (design D2): the GET page discloses
 * private 1:1 chat content, so a `moderator`/`read_only` session is 403'd at
 * BOTH the GET page and the POST. The POST mirrors [adminReportResolution]'s gate
 * order verbatim — CSRF FIRST ([AdminCsrfGate.validateCsrf]; 403 +
 * `admin_csrf_violation` on miss, BEFORE the role gate so the violation is
 * audited, not masked by a silent role-403) → [AdminRoleGate.requireOwnerOrAdmin]
 * → parse the path UUID (malformed → 400, no write) → required `reason`
 * (blank/missing → 400, no write) → the repository transaction (which gates the
 * destructive cap + performs the idempotent atomic write). The bare collection
 * `/admin/chat-messages` exposes no write route (a bare POST is a routing-layer
 * 405).
 *
 * Dual-mode response (mirrors [adminReportResolution]): a successful redaction
 * re-renders the page fragment (now showing the message redacted) for an
 * `HX-Request`, or 303-redirects back to `GET /admin/chat-messages/{id}` for the
 * no-JS path. No-op / not-found / rate-limit outcomes re-render with an in-band
 * message.
 */
fun Route.adminChatRedaction(
    chatRedactionRepository: ChatRedactionRepository,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/chat-messages/{id}") {
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@get
        val messageId =
            call.parseMessageId() ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@get
            }
        val page =
            chatRedactionRepository.loadRedactionPage(messageId) ?: run {
                call.respond(HttpStatusCode.NotFound, MSG_NOT_FOUND)
                return@get
            }
        call.respondRedactionPage(layout, page, message = null)
    }

    post("/chat-messages/{id}/redact") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val messageId =
            call.parseMessageId() ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        val reason =
            body[REASON_FIELD]?.trim()?.takeIf { it.isNotEmpty() } ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_REASON_REQUIRED)
                return@post
            }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val outcome =
            chatRedactionRepository.redact(
                messageId = messageId,
                actingAdminId = principal.adminId,
                reason = reason,
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
        when (outcome) {
            RedactionOutcome.Applied -> call.respondRedactionSuccess(chatRedactionRepository, messageId)
            RedactionOutcome.AlreadyRedacted ->
                call.respondRedactionView(chatRedactionRepository, layout, messageId, MSG_ALREADY_REDACTED)
            RedactionOutcome.NotFound ->
                // The message vanished between the GET and the POST (e.g. its
                // conversation was hard-deleted, cascading the row). 404.
                call.respond(HttpStatusCode.NotFound, MSG_NOT_FOUND)
            RedactionOutcome.RateLimited ->
                call.respondRedactionView(chatRedactionRepository, layout, messageId, MSG_RATE_LIMITED)
        }
    }
}

/** Parse `{id}` as a UUID; null when malformed (→ 400, no write). */
private fun ApplicationCall.parseMessageId(): UUID? = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/**
 * Successful redaction: re-render the page fragment (re-queried, now showing the
 * message redacted + the redact action gone) for an `HX-Request`, or 303-redirect
 * back to `GET /admin/chat-messages/{id}` for the no-JS path.
 */
private suspend fun ApplicationCall.respondRedactionSuccess(
    repo: ChatRedactionRepository,
    messageId: UUID,
) {
    if (request.headers[HX_REQUEST] == "true") {
        val page = repo.loadRedactionPage(messageId)
        if (page == null) {
            respond(HttpStatusCode.NotFound, MSG_NOT_FOUND)
        } else {
            respond(HttpStatusCode.OK, PebbleContent(FRAGMENT_TEMPLATE, fragmentModel(page, MSG_REDACTED_OK)))
        }
    } else {
        response.headers.append(HttpHeaders.Location, "/admin/chat-messages/$messageId")
        respond(HttpStatusCode.SeeOther)
    }
}

/**
 * Re-render the redaction surface with an in-band [message] (no-op / rate-limit):
 * the page fragment for an `HX-Request`, otherwise the full page (so the no-JS
 * path sees the message). Re-queries so the displayed state is current.
 */
private suspend fun ApplicationCall.respondRedactionView(
    repo: ChatRedactionRepository,
    layout: AdminLayout,
    messageId: UUID,
    message: String,
) {
    val page =
        repo.loadRedactionPage(messageId) ?: run {
            respond(HttpStatusCode.NotFound, MSG_NOT_FOUND)
            return
        }
    if (request.headers[HX_REQUEST] == "true") {
        respond(HttpStatusCode.OK, PebbleContent(FRAGMENT_TEMPLATE, fragmentModel(page, message)))
    } else {
        respondRedactionPage(layout, page, message)
    }
}

/** Render the full redaction page (shell + panel fragment data). */
private suspend fun ApplicationCall.respondRedactionPage(
    layout: AdminLayout,
    page: ChatRedactionPageData,
    message: String?,
) {
    val model =
        fragmentModel(page, message).toMutableMap().apply {
            layout.putShellModel(
                this@respondRedactionPage,
                this,
                pageTitle = "Chat Message Redaction",
                activePath = "/admin/chat-messages",
            )
        }
    respond(HttpStatusCode.OK, PebbleContent(PAGE_TEMPLATE, model))
}

/** The panel/fragment model shared by the full page + the HTMX fragment swap. */
private fun fragmentModel(
    page: ChatRedactionPageData,
    message: String?,
): Map<String, Any> =
    buildMap {
        put("messageId", page.messageId.toString())
        put("conversationId", page.conversationId.toString())
        put("targetIsRedacted", page.targetIsRedacted)
        put("context", page.context.map { it.toViewMap() })
        if (message != null) put("message", message)
    }

private fun ChatRedactionContextMessage.toViewMap(): Map<String, Any> =
    buildMap {
        put("id", id.toString())
        put("isTarget", isTarget)
        put("isRedacted", isRedacted)
        put("hasEmbed", hasEmbed)
        put("content", content ?: "")
        put("hasContent", content != null)
        put("createdAt", CREATED_AT_FORMAT.format(createdAt))
    }

private const val HX_REQUEST = "HX-Request"
private const val REASON_FIELD = "reason"
private const val PAGE_TEMPLATE = "chat-redaction.peb"
private const val FRAGMENT_TEMPLATE = "chat-redaction-panel.peb"

private val CREATED_AT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

// User-facing informational messages (admin panel is English-only).
private const val MSG_INVALID_ID = "Invalid id."
private const val MSG_NOT_FOUND = "No matching chat message."
private const val MSG_REASON_REQUIRED = "A redaction reason is required."
private const val MSG_REDACTED_OK = "Message redacted."
private const val MSG_ALREADY_REDACTED = "Message is already redacted. No change made."
private const val MSG_RATE_LIMITED =
    "Destructive-action quota exceeded (20/hour). Try again later. No change made."
