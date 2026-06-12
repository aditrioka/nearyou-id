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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.header
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
 *  - [CannotFollowSelfException]     → 400 `cannot_follow_self`
 *  - [FollowRateLimitedException]    → 429 `rate_limited` + `Retry-After` (precedes the
 *    visibility gate — 404-probes burn the 50/h bucket)
 *  - [ProfileUserNotFoundException]  → 404 [USER_NOT_FOUND_BODY] (visibility gate:
 *    unknown / soft-deleted / shadow-banned / blocked-either-direction target)
 *  - [UserNotFoundException]         → 404 [USER_NOT_FOUND_BODY] (FK 23503 backstop)
 *  - [FollowBlockedException]        → 404 [USER_NOT_FOUND_BODY] (in-tx guard — the
 *    TOCTOU backstop for a block landing after the gate)
 *
 * All three 404 causes answer ONE constant, byte-identical body via `respondText` (NOT
 * `respond`) — the `user-profile-read` constant-404 contract (design D4). The V6-era 409
 * `follow_blocked` mapping was removed by `social-list-profile-summaries`: a 409 would
 * contradict the adjacent profile read (which 404s the same causes) and tell a caller
 * who has NOT blocked the target that the target blocked them.
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
                } catch (e: FollowRateLimitedException) {
                    call.response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, "rate_limited", "Too many follow changes. Try again later.")
                    return@post
                } catch (_: ProfileUserNotFoundException) {
                    call.respondUserNotFound()
                    return@post
                } catch (_: UserNotFoundException) {
                    call.respondUserNotFound()
                    return@post
                } catch (_: FollowBlockedException) {
                    call.respondUserNotFound()
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
                try {
                    service.unfollow(principal.userId, target)
                } catch (e: FollowRateLimitedException) {
                    call.response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, "rate_limited", "Too many follow changes. Try again later.")
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/**
 * Wire row for the follower/following lists — the embedded profile summary
 * (`social-list-profile-summaries`). Bare camelCase keys (no `@SerialName`), matching
 * `UserProfileResponse` and the timeline `author*` identity-field precedent. `isPremium`
 * uses the `user-profile-read` formula (`subscription_status = 'premium_active'` only);
 * `createdAt` is the follow-edge timestamp (cursor axis), not a profile field.
 */
@Serializable
data class FollowListItem(
    val userId: String,
    val username: String,
    val displayName: String,
    val isPremium: Boolean,
    val createdAt: String,
)

@Serializable
data class FollowListResponse(
    val users: List<FollowListItem>,
    val nextCursor: String? = null,
)

/**
 * `GET /api/v1/users/{user_id}/followers|following` — paginated lists of profile
 * followers / profile outbound follows, each row carrying the embedded profile summary.
 *
 * Rows are sourced via `visible_users` (shadow-banned / soft-deleted members never
 * appear) and filtered bidirectionally against the calling viewer's `user_blocks`
 * (repo-layer concerns). The profile target resolves under the `user-profile-read`
 * contract: self via raw `users` (a shadow-banned caller keeps their own lists),
 * others via `visible_users` + bidirectional block exclusion — an unresolvable target
 * answers the CONSTANT byte-identical 404 [USER_NOT_FOUND_BODY] (no cause hint).
 * Malformed cursor → 400 `invalid_cursor`.
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
                        call.respondUserNotFound()
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
                        call.respondUserNotFound()
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
                    username = it.username,
                    displayName = it.displayName,
                    isPremium = it.isPremium,
                    createdAt = it.createdAt.toString(),
                )
            },
        nextCursor = nextCursor?.let(::encodeCursor),
    )

/**
 * Constant, direction-less 404 body — byte-identical for every unresolvable cause.
 * MUST stay byte-identical to `UserProfileRoutes.kt`'s `USER_NOT_FOUND_BODY` (that
 * constant is file-private; the integration tests assert cross-route byte equality
 * against the live profile route).
 */
private const val USER_NOT_FOUND_BODY = """{"error":{"code":"user_not_found"}}"""

private suspend fun ApplicationCall.respondUserNotFound() {
    respondText(
        text = USER_NOT_FOUND_BODY,
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.NotFound,
    )
}

private fun parseUserId(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    respond(
        status,
        mapOf("error" to mapOf("code" to code, "message" to message)),
    )
}
