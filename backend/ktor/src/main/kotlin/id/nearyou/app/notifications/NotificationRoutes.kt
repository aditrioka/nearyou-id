package id.nearyou.app.notifications

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.data.repository.NotificationRow
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * `/api/v1/notifications` read surface. All four routes require a valid JWT
 * (`AUTH_PROVIDER_USER = "user-jwt"`). Error envelope matches the
 * project-wide `{ "error": { "code": ..., "message": ... } }` shape.
 *
 * Cursor shape: base64url-encoded JSON `{"c":"<ISO8601>","i":"<uuid>"}` —
 * same encoding as the shared keyset `Cursor` in `app.common`, but with a
 * local encode/decode pair because the notifications page uses a composite
 * `(created_at, id)` cursor that is conceptually distinct from the shared
 * `Cursor`'s `(createdAt, id)`-as-a-single-type payload. Local helpers avoid
 * coupling the notifications module to an unrelated DTO.
 */
fun Application.notificationRoutes(service: NotificationService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/notifications") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respondError(HttpStatusCode.Unauthorized, "unauthenticated", "Authentication required.")
                        return@get
                    }
                val cursor =
                    try {
                        call.parameters["cursor"]?.let { decodeNotificationCursor(it) }
                    } catch (_: InvalidCursorException) {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_cursor", "Cursor is malformed.")
                        return@get
                    }
                val unreadOnly = call.parameters["unread"]?.equals("true", ignoreCase = true) == true
                val requestedLimit = call.parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
                val limit = requestedLimit.coerceIn(1, MAX_LIMIT)
                val page =
                    service.list(
                        userId = principal.userId,
                        cursorCreatedAt = cursor?.createdAt,
                        cursorId = cursor?.id,
                        unreadOnly = unreadOnly,
                        limit = limit,
                    )
                call.respond(
                    NotificationListResponse(
                        items = page.rows.map { it.toDto() },
                        nextCursor = page.nextCursor?.let(::encodeNotificationCursor),
                    ),
                )
            }

            get("/api/v1/notifications/unread-count") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respondError(HttpStatusCode.Unauthorized, "unauthenticated", "Authentication required.")
                        return@get
                    }
                val count = service.unreadCount(principal.userId)
                call.respond(UnreadCountResponse(count = count))
            }

            patch("/api/v1/notifications/read-all") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respondError(HttpStatusCode.Unauthorized, "unauthenticated", "Authentication required.")
                        return@patch
                    }
                val marked = service.markAllRead(principal.userId)
                call.respond(ReadAllResponse(markedRead = marked))
            }

            patch("/api/v1/notifications/{id}/read") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respondError(HttpStatusCode.Unauthorized, "unauthenticated", "Authentication required.")
                        return@patch
                    }
                val id =
                    parseUuid(call.parameters["id"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_uuid", "id must be a UUID.")
                        return@patch
                    }
                when (service.markRead(principal.userId, id)) {
                    NotificationService.MarkReadOutcome.Acknowledged ->
                        call.respond(HttpStatusCode.NoContent)
                    NotificationService.MarkReadOutcome.NotFound ->
                        call.respondError(HttpStatusCode.NotFound, "not_found", "Notification does not exist.")
                }
            }
        }
    }
}

const val DEFAULT_LIMIT: Int = 20
const val MAX_LIMIT: Int = 50

@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    @SerialName("actor_user_id") val actorUserId: String?,
    @SerialName("target_type") val targetType: String?,
    @SerialName("target_id") val targetId: String?,
    @SerialName("body_data") val bodyData: JsonElement,
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String?,
)

@Serializable
data class NotificationListResponse(
    val items: List<NotificationDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class UnreadCountResponse(val count: Long)

@Serializable
data class ReadAllResponse(
    @SerialName("marked_read") val markedRead: Int,
)

private val dtoJson = Json { ignoreUnknownKeys = true }

private fun NotificationRow.toDto(): NotificationDto =
    NotificationDto(
        id = id.toString(),
        type = type.wire,
        actorUserId = actorUserId?.toString(),
        targetType = targetType,
        targetId = targetId?.toString(),
        bodyData = parseBodyDataJson(bodyDataJson),
        createdAt = createdAt.toString(),
        readAt = readAt?.toString(),
    )

private fun parseBodyDataJson(raw: String): JsonElement =
    try {
        dtoJson.parseToJsonElement(raw)
    } catch (_: Exception) {
        buildJsonObject {}
    }

@Serializable
private data class NotificationCursorPayload(val c: String, val i: String)

class InvalidCursorException : RuntimeException("invalid cursor")

fun encodeNotificationCursor(cursor: NotificationCursor): String {
    val payload =
        NotificationCursorPayload(c = cursor.createdAt.toString(), i = cursor.id.toString())
    val bytes = dtoJson.encodeToString(NotificationCursorPayload.serializer(), payload).toByteArray()
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun decodeNotificationCursor(encoded: String): NotificationCursor {
    val bytes =
        try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw InvalidCursorException()
        }
    val payload =
        try {
            dtoJson.decodeFromString(NotificationCursorPayload.serializer(), String(bytes))
        } catch (_: Exception) {
            throw InvalidCursorException()
        }
    return try {
        NotificationCursor(createdAt = Instant.parse(payload.c), id = UUID.fromString(payload.i))
    } catch (_: DateTimeParseException) {
        throw InvalidCursorException()
    } catch (_: IllegalArgumentException) {
        throw InvalidCursorException()
    }
}

private fun parseUuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    respond(
        status,
        mapOf("error" to mapOf("code" to code, "message" to message)),
    )
}
