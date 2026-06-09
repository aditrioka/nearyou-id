package id.nearyou.app.user

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.data.repository.ProfileUserNotFoundException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * `GET /api/v1/users/{user_id}` — single-user profile read (`user-profile-read` capability).
 *
 * Bearer JWT via [AUTH_PROVIDER_USER]. Error mapping:
 *  - non-UUID `user_id`            → 400 `invalid_request`
 *  - missing/invalid principal     → 401
 *  - unresolvable target           → 404 [USER_NOT_FOUND_BODY]
 *
 * The 404 covers four indistinguishable causes — unknown UUID, soft-deleted, shadow-banned, and
 * blocked-in-either-direction — and is emitted as a CONSTANT, byte-identical body via `respondText`
 * (NOT `respond`) so a viewer cannot tell which cause applies, nor which direction a block runs
 * (design D4, mirroring `FollowRoutes.FOLLOW_BLOCKED_BODY` leak-prevention).
 */
fun Application.userProfileRoutes(service: UserProfileService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/users/{user_id}") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val target =
                    parseUserId(call.parameters["user_id"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_request", "user_id must be a UUID.")
                        return@get
                    }
                val profile =
                    try {
                        service.getProfile(viewerId = principal.userId, targetId = target)
                    } catch (_: ProfileUserNotFoundException) {
                        // Constant body, no cause/direction hint — see KDoc above.
                        call.respondText(
                            text = USER_NOT_FOUND_BODY,
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.NotFound,
                        )
                        return@get
                    }
                call.respond(profile)
            }
        }
    }
}

/**
 * Wire DTO for the profile read. camelCase keys match the repo's mixed-case timeline-DTO convention.
 * `isPrivate` is self-only and `null` for other-user reads; `bio` is `null` when unset;
 * `followedByViewer` is `false` whenever `isSelf` is `true`.
 *
 * There is intentionally NO `suspendedUntil`: suspension is a session-terminating state (sets
 * `is_banned` + bumps `token_version`), so a suspended user is 403'd at the auth boundary and can
 * never reach this read — the suspension countdown belongs on the auth-rejection response, not on a
 * protected resource (design D2; see the `follow-up`-labelled issue for enriching the auth error).
 */
@Serializable
data class UserProfileResponse(
    val userId: String,
    val username: String,
    val displayName: String,
    val bio: String?,
    val followerCount: Long,
    val followingCount: Long,
    val isSelf: Boolean,
    val followedByViewer: Boolean,
    val isPremium: Boolean,
    val isPrivate: Boolean?,
)

private val USER_NOT_FOUND_BODY = """{"error":{"code":"user_not_found"}}"""

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
