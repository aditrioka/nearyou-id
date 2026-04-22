package id.nearyou.app.follow

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.common.InvalidCursorException
import id.nearyou.app.common.decodeCursor
import id.nearyou.app.common.encodeCursor
import id.nearyou.data.repository.FollowBlockedException
import id.nearyou.data.repository.ProfileUserNotFoundException
import id.nearyou.data.repository.UserNotFoundException
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
 * `POST/DELETE /api/v1/follows/{user_id}` — create and remove follow edges.
 *
 * Error mapping:
 *  - [CannotFollowSelfException] → 400 `cannot_follow_self`
 *  - [UserNotFoundException]     → 404 `user_not_found`
 *  - [FollowBlockedException]    → 409 `follow_blocked`
 *    Body is the constant `{ "error": { "code": "follow_blocked" } }` with NO direction
 *    hint — revealing which side initiated the block would leak block state across the
 *    other user. `respondText` is used (not `respond`) so the body is byte-for-byte
 *    identical regardless of which direction of the block exists.
 */
fun Application.followRoutes(service: FollowService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            post("/api/v1/follows/{user_id}") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }
                val target =
                    parseUserId(call.parameters["user_id"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_request", "user_id must be a UUID.")
                        return@post
                    }
                try {
                    service.follow(principal.userId, target)
                } catch (_: CannotFollowSelfException) {
                    call.respondError(HttpStatusCode.BadRequest, "cannot_follow_self", "You cannot follow yourself.")
                    return@post
                } catch (_: UserNotFoundException) {
                    call.respondError(HttpStatusCode.NotFound, "user_not_found", "Target user does not exist.")
                    return@post
                } catch (_: FollowBlockedException) {
                    // Constant body, no direction hint — see KDoc above.
                    call.respondText(
                        text = FOLLOW_BLOCKED_BODY,
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.Conflict,
                    )
                    return@post
                }
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/api/v1/follows/{user_id}") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@delete
                    }
                val target =
                    parseUserId(call.parameters["user_id"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_request", "user_id must be a UUID.")
                        return@delete
                    }
                service.unfollow(principal.userId, target)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

@Serializable
data class FollowListItem(val userId: String, val createdAt: String)

@Serializable
data class FollowListResponse(
    val users: List<FollowListItem>,
    val nextCursor: String? = null,
)

/**
 * `GET /api/v1/users/{user_id}/followers|following` — paginated, viewer-block-filtered
 * lists of profile followers / profile outbound follows.
 *
 * Missing profile → 404 `user_not_found`. Malformed cursor → 400 `invalid_cursor`. All
 * visible users are filtered bidirectionally against the calling viewer's `user_blocks`
 * rows (repo-layer concern).
 */
fun Application.userSocialRoutes(service: FollowService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/users/{user_id}/followers") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val profileId =
                    parseUserId(call.parameters["user_id"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_request", "user_id must be a UUID.")
                        return@get
                    }
                val cursor =
                    try {
                        call.parameters["cursor"]?.let { decodeCursor(it) }
                    } catch (_: InvalidCursorException) {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_cursor", "Cursor is malformed.")
                        return@get
                    }
                val page =
                    try {
                        service.listFollowers(profileId = profileId, viewerId = principal.userId, cursor = cursor)
                    } catch (_: ProfileUserNotFoundException) {
                        call.respondError(HttpStatusCode.NotFound, "user_not_found", "Profile user does not exist.")
                        return@get
                    }
                call.respond(page.toResponse())
            }

            get("/api/v1/users/{user_id}/following") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val profileId =
                    parseUserId(call.parameters["user_id"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_request", "user_id must be a UUID.")
                        return@get
                    }
                val cursor =
                    try {
                        call.parameters["cursor"]?.let { decodeCursor(it) }
                    } catch (_: InvalidCursorException) {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_cursor", "Cursor is malformed.")
                        return@get
                    }
                val page =
                    try {
                        service.listFollowing(profileId = profileId, viewerId = principal.userId, cursor = cursor)
                    } catch (_: ProfileUserNotFoundException) {
                        call.respondError(HttpStatusCode.NotFound, "user_not_found", "Profile user does not exist.")
                        return@get
                    }
                call.respond(page.toResponse())
            }
        }
    }
}

private fun FollowPage.toResponse(): FollowListResponse =
    FollowListResponse(
        users =
            rows.map {
                FollowListItem(
                    userId = it.userId.toString(),
                    createdAt = it.createdAt.toString(),
                )
            },
        nextCursor = nextCursor?.let(::encodeCursor),
    )

private const val FOLLOW_BLOCKED_BODY = """{"error":{"code":"follow_blocked"}}"""

private fun parseUserId(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

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
