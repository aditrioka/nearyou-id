package id.nearyou.app.post

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.engagement.PostNotFoundException
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * `GET /api/v1/posts/{post_id}` — single-post by-id read (`single-post-read` capability).
 *
 * Bearer JWT via [AUTH_PROVIDER_USER]. Error mapping:
 *  - non-UUID `post_id`        → 400 `invalid_request`
 *  - missing/invalid principal → 401
 *  - unresolvable target       → 404 [POST_NOT_FOUND_BODY]
 *
 * The 404 covers every "not visible to the viewer" cause — unknown UUID, soft-deleted, author
 * shadow-banned, auto-hidden, and blocked-in-either-direction — emitted as a CONSTANT, byte-identical
 * body via `respondText` (NOT `respond`) so a viewer cannot tell which cause applies, nor which
 * direction a block runs (mirrors [id.nearyou.app.user.userProfileRoutes]). The body literal MUST
 * stay byte-identical to the `LikeRoutes` / `ReplyRoutes` `POST_NOT_FOUND_BODY` constant — the shared
 * like/reply `post_not_found` contract (cross-route byte-equality is integration-tested).
 */
fun Application.singlePostRoutes(service: PostReadService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/posts/{post_id}") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val postId =
                    parsePostId(call.parameters["post_id"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_request", "post_id must be a UUID.")
                        return@get
                    }
                val response =
                    try {
                        service.getById(viewerId = principal.userId, postId = postId)
                    } catch (_: PostNotFoundException) {
                        // Constant body, no cause/direction hint — see KDoc above.
                        call.respondText(
                            text = POST_NOT_FOUND_BODY,
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.NotFound,
                        )
                        return@get
                    }
                call.respond(response)
            }
        }
    }
}

/**
 * Wire DTO for the single-post read. Mixed-case keys match the shipped timeline post DTO
 * (`TimelineRoutes.kt`): snake_case `city_name` / `liked_by_viewer` / `reply_count`, bare camelCase
 * otherwise. Deliberately NO author UUID and NO `latitude`/`longitude` — the projection is strictly
 * more restrictive than the timeline wire (no-PII; issue #202 + the `PostDetailRoute` discipline).
 * `distanceM` is always `null` in v1 (a by-id read has no viewer-location context) and is omitted from
 * the body under the app-wide ContentNegotiation `explicitNulls = false`.
 *
 * `editedAt` (bare camelCase, like `createdAt`) is the most-recent edit timestamp (`MAX(post_edits.edited_at)`),
 * non-null iff the post has edit history — the only edited-signal the mobile client uses for the "Diedit"
 * label (`mobile-post-editing`). Omitted when `null` under `explicitNulls = false`; no PII, no coordinate.
 *
 * `isAuthor` (bare camelCase) is the per-viewer authorship flag (true iff the JWT caller authored this post),
 * computed server-side from `author_id` WITHOUT exposing the UUID — it gates the mobile edit affordance. A
 * boolean about the viewer's relationship to the post; it leaks nothing about the author's identity.
 */
@Serializable
data class SinglePostResponse(
    val id: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val distanceM: Double? = null,
    @SerialName("city_name") val cityName: String,
    val createdAt: String,
    val editedAt: String? = null,
    @SerialName("liked_by_viewer") val likedByViewer: Boolean,
    @SerialName("reply_count") val replyCount: Int,
    val isAuthor: Boolean = false,
)

private const val POST_NOT_FOUND_BODY = """{"error":{"code":"post_not_found"}}"""

private fun parsePostId(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

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
