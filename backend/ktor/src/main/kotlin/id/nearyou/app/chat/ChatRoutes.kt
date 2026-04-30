package id.nearyou.app.chat

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.common.InvalidCursorException
import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * `POST/GET /api/v1/conversations` + `GET/POST /api/v1/chat/{conversation_id}/messages`.
 *
 * Per `chat-foundation/specs/chat-conversations/spec.md`:
 *
 *  - **POST /api/v1/conversations** — create-or-return 1:1 conversation. 201 (new) / 200
 *    (existing) / 403 (block) / 400 (self-DM, malformed body) / 404 (recipient missing) /
 *    401 (unauth). The 201/200 differential is the ONLY observable difference between new
 *    and existing — the body shape is identical.
 *  - **GET /api/v1/conversations** — paginated active-participant list, ordered
 *    `last_message_at DESC NULLS LAST, created_at DESC`. Default 50, hard cap 100 (silent
 *    clamp, NOT 400). Malformed cursor → 400.
 *  - **GET /api/v1/chat/{id}/messages** — paginated chat messages, `(created_at, id) DESC`
 *    composite cursor. Active-participant required (403 otherwise). Unknown conv → 404.
 *    Malformed UUID path → 400. Malformed cursor → 400. Default 50, hard cap 100.
 *  - **POST /api/v1/chat/{id}/messages** — send message. 201 + canonical block (403) +
 *    length-guard (400) + active-participant (403) + unknown conv (404). Embed fields are
 *    silently ignored with a structured WARN log per ignored field.
 *
 * Block enforcement: send + create return 403 with body `{"error":"<canonical>"}`. List
 * endpoints DO NOT block-filter (per `docs/02-Product.md:234`).
 */
fun Application.chatRoutes(
    service: ChatService,
    contentGuard: ContentLengthGuard,
) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            post("/api/v1/conversations") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }
                val body =
                    try {
                        call.receive<CreateConversationRequest>()
                    } catch (_: Exception) {
                        call.respondInvalidRequest("Request body must be JSON with `recipient_user_id`.")
                        return@post
                    }
                val recipientId =
                    parseUuid(body.recipientUserId) ?: run {
                        call.respondInvalidUuid("recipient_user_id must be a UUID.")
                        return@post
                    }
                when (val result = service.createConversation(principal.userId, recipientId)) {
                    is ChatService.CreateConversationResult.Created ->
                        call.respond(HttpStatusCode.Created, result.outcome.toResponse())
                    is ChatService.CreateConversationResult.Returned ->
                        call.respond(HttpStatusCode.OK, result.outcome.toResponse())
                    ChatService.CreateConversationResult.Blocked ->
                        call.respondBlocked()
                    ChatService.CreateConversationResult.SelfDm ->
                        call.respondInvalidRequest("recipient_user_id must differ from caller.")
                    ChatService.CreateConversationResult.RecipientNotFound ->
                        call.respond(HttpStatusCode.NotFound)
                }
            }

            get("/api/v1/conversations") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val cursor: ConversationsCursor? =
                    try {
                        call.parameters["cursor"]?.let { decodeConversationsCursor(it) }
                    } catch (_: InvalidCursorException) {
                        call.respondInvalidCursor()
                        return@get
                    }
                val limit = clampLimit(call.parameters["limit"])
                val rows =
                    service.listMyConversations(
                        viewerId = principal.userId,
                        cursor = cursor,
                        limit = limit + 1,
                    )
                val (page, next) =
                    if (rows.size > limit) {
                        val truncated = rows.take(limit)
                        val last = truncated.last()
                        truncated to
                            encodeConversationsCursor(
                                lastMessageAt = last.conversation.lastMessageAt,
                                createdAt = last.conversation.createdAt,
                                id = last.conversation.id,
                            )
                    } else {
                        rows to null
                    }
                call.respond(
                    ConversationListResponse(
                        conversations = page.map { it.toListItemDto() },
                        nextCursor = next,
                    ),
                )
            }

            get("/api/v1/chat/{conversation_id}/messages") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val conversationId =
                    parseUuid(call.parameters["conversation_id"]) ?: run {
                        call.respondInvalidUuid("conversation_id must be a UUID.")
                        return@get
                    }
                val cursor: MessagesCursor? =
                    try {
                        call.parameters["cursor"]?.let { decodeMessagesCursor(it) }
                    } catch (_: InvalidCursorException) {
                        call.respondInvalidCursor()
                        return@get
                    }
                val limit = clampLimit(call.parameters["limit"])
                val rows =
                    try {
                        service.listMessages(
                            conversationId = conversationId,
                            viewerId = principal.userId,
                            cursor = cursor,
                            limit = limit + 1,
                        )
                    } catch (_: ConversationNotFoundException) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    } catch (_: NotParticipantException) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }
                val (page, next) =
                    if (rows.size > limit) {
                        val truncated = rows.take(limit)
                        val last = truncated.last()
                        truncated to encodeMessagesCursor(last.createdAt, last.id)
                    } else {
                        rows to null
                    }
                call.respond(
                    MessageListResponse(
                        messages = page.map { it.toJson() },
                        nextCursor = next,
                    ),
                )
            }

            post("/api/v1/chat/{conversation_id}/messages") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }
                val conversationId =
                    parseUuid(call.parameters["conversation_id"]) ?: run {
                        call.respondInvalidUuid("conversation_id must be a UUID.")
                        return@post
                    }
                // Rate limit gate (chat-rate-limit § Daily send-rate cap). MUST run BEFORE
                // JSON body parse / content guard / sendMessage so a Free attacker spamming
                // oversized payloads / non-existent conversation UUIDs / blocked recipients
                // still consumes daily slots and hits 429 at the cap (design.md Decision 5).
                when (val outcome = service.tryRateLimit(principal.userId, principal.subscriptionStatus)) {
                    is ChatService.RateLimitOutcome.RateLimited -> {
                        call.response.headers.append("Retry-After", outcome.retryAfterSeconds.toString())
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            mapOf("error" to mapOf("code" to "rate_limited")),
                        )
                        return@post
                    }
                    ChatService.RateLimitOutcome.Allowed -> Unit
                }
                val body =
                    try {
                        call.receive<SendMessageRequest>()
                    } catch (_: Exception) {
                        call.respondInvalidRequest("Request body must be JSON with a `content` string.")
                        return@post
                    }
                val content =
                    try {
                        contentGuard.enforce(CHAT_CONTENT_KEY, body.content)
                    } catch (_: ContentEmptyException) {
                        call.respondInvalidRequest("content is required.")
                        return@post
                    } catch (ex: ContentTooLongException) {
                        call.respondInvalidRequest("content exceeds the maximum of ${ex.limit} characters.")
                        return@post
                    }
                val row =
                    try {
                        service.sendMessage(
                            conversationId = conversationId,
                            senderId = principal.userId,
                            content = content,
                        )
                    } catch (_: ConversationNotFoundException) {
                        call.respond(HttpStatusCode.NotFound)
                        return@post
                    } catch (_: NotParticipantException) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@post
                    } catch (_: BlockedException) {
                        call.respondBlocked()
                        return@post
                    }
                // Embed fields are silently ignored on this cut (deferred to chat-embedded-posts).
                // Per spec: structured WARN log per ignored field with the resulting message-id.
                logIgnoredEmbedFields(body, row.id)
                call.respond(HttpStatusCode.Created, row.toJson())
            }
        }
    }
}

/**
 * Content-length guard key for the chat send path. Registered in
 * [id.nearyou.app.guard.installContentLengthGuard] with a 2000-char cap per
 * `docs/02-Product.md:319`.
 */
const val CHAT_CONTENT_KEY: String = "chat.content"

private const val MAX_PAGE: Int = 100
private const val DEFAULT_PAGE: Int = 50

private val log = LoggerFactory.getLogger("id.nearyou.app.chat.ChatRoutes")

private fun parseUuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun clampLimit(raw: String?): Int {
    val parsed = raw?.toIntOrNull() ?: DEFAULT_PAGE
    return parsed.coerceIn(1, MAX_PAGE)
}

private suspend fun ApplicationCall.respondInvalidUuid(message: String) {
    respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to mapOf("code" to "invalid_uuid", "message" to message)),
    )
}

private suspend fun ApplicationCall.respondInvalidRequest(message: String) {
    respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to mapOf("code" to "invalid_request", "message" to message)),
    )
}

private suspend fun ApplicationCall.respondInvalidCursor() {
    respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to mapOf("code" to "invalid_cursor", "message" to "Cursor is malformed.")),
    )
}

private suspend fun ApplicationCall.respondBlocked() {
    respondText(
        text = """{"error":"$BLOCKED_BODY_TEXT"}""",
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.Forbidden,
    )
}

private fun logIgnoredEmbedFields(
    body: SendMessageRequest,
    messageId: UUID,
) {
    if (body.embeddedPostId != null) {
        log.warn(
            "event=chat_send_embedded_field_ignored field=embedded_post_id message_id={}",
            messageId,
        )
    }
    if (body.embeddedPostSnapshot != null) {
        log.warn(
            "event=chat_send_embedded_field_ignored field=embedded_post_snapshot message_id={}",
            messageId,
        )
    }
    if (body.embeddedPostEditId != null) {
        log.warn(
            "event=chat_send_embedded_field_ignored field=embedded_post_edit_id message_id={}",
            messageId,
        )
    }
}

// ---- DTO converters ----------------------------------------------------------

private fun CreateConversationOutcome.toResponse(): CreateConversationResponse =
    CreateConversationResponse(
        conversation = conversation.toDto(),
        participants = participants.map { it.toDto() },
    )

private fun ConversationRow.toDto(): ConversationDto =
    ConversationDto(
        id = id.toString(),
        createdAt = createdAt.toString(),
        lastMessageAt = lastMessageAt?.toString(),
    )

private fun ParticipantRow.toDto(): ParticipantDto =
    ParticipantDto(
        userId = userId.toString(),
        slot = slot,
        joinedAt = joinedAt.toString(),
        leftAt = leftAt?.toString(),
    )

private fun ConversationListRow.toListItemDto(): ConversationListItemDto =
    ConversationListItemDto(
        conversation = conversation.toDto(),
        partner =
            PartnerProfileDto(
                id = partnerId.toString(),
                username = partnerUsername,
                displayName = partnerDisplayName,
                isPremium = partnerIsPremium,
            ),
    )

private fun ChatMessageRow.toJson(): JsonObject =
    chatMessageJson(
        id = id.toString(),
        conversationId = conversationId.toString(),
        senderId = senderId.toString(),
        content = content,
        createdAt = createdAt.toString(),
        redactedAt = redactedAt?.toString(),
    )
