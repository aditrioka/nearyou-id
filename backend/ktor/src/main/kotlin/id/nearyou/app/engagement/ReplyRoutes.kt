package id.nearyou.app.engagement

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.common.InvalidCursorException
import id.nearyou.app.common.decodeCursor
import id.nearyou.app.common.encodeCursor
import id.nearyou.app.common.Cursor
import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.data.repository.PostReplyRow
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * `POST/GET /api/v1/posts/{post_id}/replies` + `DELETE /api/v1/posts/{post_id}/replies/{reply_id}`.
 *
 * Error mapping (post-replies-v8 spec):
 *  - `400 invalid_uuid` for non-UUID `{post_id}` / `{reply_id}` — runs BEFORE any repo call.
 *  - `400 invalid_request` for missing / empty / >280-cp content (mapped by StatusPages via
 *    the shared ContentLengthGuard exceptions — consistent with post creation).
 *  - `400 invalid_cursor` for a malformed `cursor` on GET.
 *  - `404 post_not_found` from [PostNotFoundException] on POST/GET. Body is the byte-identical
 *    constant `{"error":{"code":"post_not_found"}}` with no direction hint / no case split
 *    across missing / soft-deleted / auto-hidden / caller-blocked / author-blocked.
 *  - `DELETE` NEVER returns 403 or 404 — always `204` regardless of not-yours /
 *    already-tombstoned / never-existed. The repository guard scopes the UPDATE.
 */
fun Application.replyRoutes(service: ReplyService, contentGuard: ContentLengthGuard) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            post("/api/v1/posts/{post_id}/replies") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }
                val postId =
                    parseUuid(call.parameters["post_id"]) ?: run {
                        call.respondInvalidUuid("post_id must be a UUID.")
                        return@post
                    }
                val body =
                    try {
                        call.receive<ReplyCreateRequest>()
                    } catch (_: Exception) {
                        call.respondInvalidRequest("Request body must be JSON with a `content` string.")
                        return@post
                    }
                val content =
                    try {
                        contentGuard.enforce(REPLY_CONTENT_KEY, body.content)
                    } catch (_: ContentEmptyException) {
                        call.respondInvalidRequest("content is required.")
                        return@post
                    } catch (ex: ContentTooLongException) {
                        call.respondInvalidRequest("content exceeds the maximum of ${ex.limit} characters.")
                        return@post
                    }
                val row =
                    try {
                        service.post(postId = postId, authorId = principal.userId, content = content)
                    } catch (_: PostNotFoundException) {
                        call.respondPostNotFound()
                        return@post
                    }
                call.respond(HttpStatusCode.Created, row.toDto())
            }

            get("/api/v1/posts/{post_id}/replies") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val postId =
                    parseUuid(call.parameters["post_id"]) ?: run {
                        call.respondInvalidUuid("post_id must be a UUID.")
                        return@get
                    }
                val cursor: Cursor? =
                    try {
                        call.parameters["cursor"]?.let { decodeCursor(it) }
                    } catch (_: InvalidCursorException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to mapOf("code" to "invalid_cursor", "message" to "Cursor is malformed.")),
                        )
                        return@get
                    }
                val rows =
                    try {
                        service.list(
                            postId = postId,
                            viewerId = principal.userId,
                            cursorCreatedAt = cursor?.createdAt,
                            cursorReplyId = cursor?.id,
                            limit = ReplyService.PAGE_SIZE + 1,
                        )
                    } catch (_: PostNotFoundException) {
                        call.respondPostNotFound()
                        return@get
                    }
                val (page, next) =
                    if (rows.size > ReplyService.PAGE_SIZE) {
                        val truncated = rows.take(ReplyService.PAGE_SIZE)
                        val last = truncated.last()
                        truncated to Cursor(createdAt = last.createdAt, id = last.id)
                    } else {
                        rows to null
                    }
                call.respond(
                    ReplyListResponse(
                        replies = page.map { it.toDto() },
                        nextCursor = next?.let(::encodeCursor),
                    ),
                )
            }

            delete("/api/v1/posts/{post_id}/replies/{reply_id}") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@delete
                    }
                // Validate BOTH path params first; either being non-UUID is a 400.
                parseUuid(call.parameters["post_id"]) ?: run {
                    call.respondInvalidUuid("post_id must be a UUID.")
                    return@delete
                }
                val replyId =
                    parseUuid(call.parameters["reply_id"]) ?: run {
                        call.respondInvalidUuid("reply_id must be a UUID.")
                        return@delete
                    }
                service.softDelete(replyId = replyId, authorId = principal.userId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

const val REPLY_CONTENT_KEY: String = "reply.content"

@Serializable
data class ReplyCreateRequest(val content: String? = null)

@Serializable
data class ReplyDto(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    val content: String,
    @SerialName("is_auto_hidden") val isAutoHidden: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String?,
    @SerialName("deleted_at") val deletedAt: String?,
)

@Serializable
data class ReplyListResponse(
    val replies: List<ReplyDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

private fun PostReplyRow.toDto(): ReplyDto =
    ReplyDto(
        id = id.toString(),
        postId = postId.toString(),
        authorId = authorId.toString(),
        content = content,
        isAutoHidden = isAutoHidden,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt?.toString(),
        deletedAt = deletedAt?.toString(),
    )

private const val POST_NOT_FOUND_BODY = """{"error":{"code":"post_not_found"}}"""

private fun parseUuid(raw: String?): UUID? =
    raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private suspend fun io.ktor.server.application.ApplicationCall.respondInvalidUuid(message: String) {
    respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to mapOf("code" to "invalid_uuid", "message" to message)),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondInvalidRequest(message: String) {
    respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to mapOf("code" to "invalid_request", "message" to message)),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondPostNotFound() {
    respondText(
        text = POST_NOT_FOUND_BODY,
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.NotFound,
    )
}
