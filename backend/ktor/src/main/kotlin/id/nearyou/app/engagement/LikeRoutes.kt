package id.nearyou.app.engagement

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * `POST/DELETE /api/v1/posts/{post_id}/like` + `GET /api/v1/posts/{post_id}/likes/count`.
 *
 * Error mapping:
 *  - `400 invalid_uuid` for non-UUID `{post_id}` paths; MUST run BEFORE any repo call.
 *  - `404 post_not_found` from [PostNotFoundException]. Body is the constant
 *    `{"error":{"code":"post_not_found"}}` with no additional fields — no direction
 *    hint, no case distinction across missing/soft-deleted/caller-blocked/author-blocked.
 *    `respondText` is used so the body is byte-for-byte identical in all four cases.
 *  - `DELETE /like` deliberately never returns 404 — it is always 204 (pure no-op on
 *    zero rows; caller can always clean up their own like regardless of block state).
 */
fun Application.likeRoutes(service: LikeService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            post("/api/v1/posts/{post_id}/like") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }
                val postId =
                    parseUuid(call.parameters["post_id"]) ?: run {
                        call.respondInvalidUuid()
                        return@post
                    }
                try {
                    service.like(postId, principal.userId)
                } catch (_: PostNotFoundException) {
                    call.respondPostNotFound()
                    return@post
                }
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/api/v1/posts/{post_id}/like") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@delete
                    }
                val postId =
                    parseUuid(call.parameters["post_id"]) ?: run {
                        call.respondInvalidUuid()
                        return@delete
                    }
                service.unlike(postId, principal.userId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/api/v1/posts/{post_id}/likes/count") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val postId =
                    parseUuid(call.parameters["post_id"]) ?: run {
                        call.respondInvalidUuid()
                        return@get
                    }
                val count =
                    try {
                        service.countLikes(postId, principal.userId)
                    } catch (_: PostNotFoundException) {
                        call.respondPostNotFound()
                        return@get
                    }
                call.respond(LikesCountResponse(count = count))
            }
        }
    }
}

@Serializable
data class LikesCountResponse(val count: Long)

private const val POST_NOT_FOUND_BODY = """{"error":{"code":"post_not_found"}}"""

private fun parseUuid(raw: String?): UUID? =
    raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private suspend fun io.ktor.server.application.ApplicationCall.respondInvalidUuid() {
    respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to mapOf("code" to "invalid_uuid", "message" to "post_id must be a UUID.")),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondPostNotFound() {
    // Constant body, byte-identical across all four invisibility cases.
    respondText(
        text = POST_NOT_FOUND_BODY,
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.NotFound,
    )
}
