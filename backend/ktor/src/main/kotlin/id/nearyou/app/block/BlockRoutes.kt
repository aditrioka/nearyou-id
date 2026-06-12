package id.nearyou.app.block

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.common.InvalidCursorException
import id.nearyou.app.common.decodeCursor
import id.nearyou.app.common.encodeCursor
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Wire row for `GET /api/v1/blocks` — the blocked user's embedded profile summary
 * (`social-list-profile-summaries`). Bare camelCase keys (no `@SerialName`), the same
 * vocabulary as `FollowListItem`/`UserProfileResponse`. Hidden blocked users arrive
 * pre-masked from the repository (`akun_dihapus` placeholders) with the row preserved —
 * `userId` stays the real UUID so `DELETE /blocks/{userId}` remains possible.
 *
 * `POST`/`DELETE /blocks/{user_id}` deliberately do NOT adopt the constant-404
 * visibility contract (`user-blocking` § "Block actions deliberately retain
 * existence-based semantics"): a shadow-banned or soft-deleted target must stay
 * blockable, so block-create keeps its raw FK existence semantics.
 */
@Serializable
data class BlockListItem(
    val userId: String,
    val username: String,
    val displayName: String,
    val isPremium: Boolean,
    val createdAt: String,
)

@Serializable
data class BlockListResponse(val blocks: List<BlockListItem>, val nextCursor: String? = null)

fun Application.blockRoutes(service: BlockService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            post("/api/v1/blocks/{user_id}") {
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
                    service.block(principal.userId, target)
                } catch (_: CannotBlockSelfException) {
                    call.respondError(HttpStatusCode.BadRequest, "cannot_block_self", "You cannot block yourself.")
                    return@post
                } catch (_: TargetUserNotFoundException) {
                    call.respondError(HttpStatusCode.NotFound, "user_not_found", "Target user does not exist.")
                    return@post
                } catch (e: BlockRateLimitedException) {
                    call.response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, "rate_limited", "Too many block changes. Try again later.")
                    return@post
                }
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/api/v1/blocks/{user_id}") {
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
                    service.unblock(principal.userId, target)
                } catch (e: BlockRateLimitedException) {
                    call.response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, "rate_limited", "Too many block changes. Try again later.")
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }

            get("/api/v1/blocks") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val cursor =
                    try {
                        call.parameters["cursor"]?.let { decodeCursor(it) }
                    } catch (_: InvalidCursorException) {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_cursor", "Cursor is malformed.")
                        return@get
                    }
                val page = service.listOutbound(principal.userId, cursor)
                call.respond(
                    BlockListResponse(
                        blocks =
                            page.rows.map {
                                BlockListItem(
                                    userId = it.blockedId.toString(),
                                    username = it.username,
                                    displayName = it.displayName,
                                    isPremium = it.isPremium,
                                    createdAt = it.createdAt.toString(),
                                )
                            },
                        nextCursor = page.nextCursor?.let(::encodeCursor),
                    ),
                )
            }
        }
    }
}

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
