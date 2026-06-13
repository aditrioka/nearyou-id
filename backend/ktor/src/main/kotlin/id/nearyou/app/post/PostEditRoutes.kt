package id.nearyou.app.post

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.infra.repo.PostEditHistoryEntry
import id.nearyou.app.infra.repo.PostEditHistoryQuery
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

@Serializable
data class EditPostRequestDto(
    val content: String? = null,
)

/**
 * `PATCH /api/v1/posts/{post_id}` (Premium content edit) +
 * `GET /api/v1/posts/{post_id}/edits` (visibility-checked edit history) — the
 * `premium-post-editing` capability (design D3 / D5 / D6 / D10).
 *
 * Both mount under the existing `authenticate(AUTH_PROVIDER_USER)` group. The
 * principal carries `userId` + `subscriptionStatus`; the service performs the
 * premium gate (D2) and every other gate (rate limit, length, window/author).
 *
 * Wire format follows the live convention: manual `buildJsonObject` /
 * `buildJsonArray` (the app-wide ContentNegotiation has `explicitNulls = false`).
 * Malformed `{post_id}` → 400; the service exceptions are mapped by `AppStatusPages`
 * (403 / 429 / 400 / 404 / 409). The history read returns content snapshot +
 * "Versi ke-N" label + `edited_at` ONLY — NO location field (D10).
 */
fun Application.postEditRoutes(
    service: PostEditService,
    history: PostEditHistoryQuery,
) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            patch("/api/v1/posts/{post_id}") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@patch
                    }
                val postId =
                    parseUuid(call.parameters["post_id"]) ?: run {
                        call.respondInvalidUuid()
                        return@patch
                    }
                val req =
                    try {
                        call.receive<EditPostRequestDto>()
                    } catch (_: Exception) {
                        call.respondText(
                            text = MALFORMED_BODY,
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.BadRequest,
                        )
                        return@patch
                    }
                // Service throws the typed failure exceptions (premium / rate-limit /
                // length / no-op / window / not-found / conflict) → AppStatusPages maps
                // each to its code + Bahasa Indonesia message.
                val edited =
                    service.edit(
                        authorId = principal.userId,
                        postId = postId,
                        rawContent = req.content,
                        subscriptionStatus = principal.subscriptionStatus,
                    )
                val body =
                    buildJsonObject {
                        put("id", JsonPrimitive(edited.id.toString()))
                        put("content", JsonPrimitive(edited.content))
                        put("updated_at", JsonPrimitive(edited.updatedAt.toString()))
                    }
                call.respondText(
                    text = body.toString(),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                )
            }

            get("/api/v1/posts/{post_id}/edits") {
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
                // null → the viewer can't see the post (shadow-ban / block either
                // direction / non-existent) → uniform 404, no existence reveal (D5).
                val entries =
                    history.history(postId = postId, viewerId = principal.userId)
                        ?: run {
                            call.respondText(
                                text = POST_NOT_FOUND_BODY,
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.NotFound,
                            )
                            return@get
                        }
                // Content-only projection (D10): content snapshot + "Versi ke-N" +
                // edited_at; NO location field. Empty history → empty list (200).
                val body =
                    buildJsonObject {
                        put(
                            "edits",
                            buildJsonArray {
                                entries.forEach { add(it.toWire()) }
                            },
                        )
                    }
                call.respondText(
                    text = body.toString(),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                )
            }
        }
    }
}

private fun PostEditHistoryEntry.toWire() =
    buildJsonObject {
        put("version_label", JsonPrimitive("Versi ke-$versionIndex"))
        put("content", JsonPrimitive(contentSnapshot))
        put("edited_at", JsonPrimitive(editedAt.toString()))
    }

private const val MALFORMED_BODY = """{"error":{"code":"invalid_json","message":"Malformed request body."}}"""
private const val INVALID_UUID_BODY = """{"error":{"code":"invalid_request","message":"post_id must be a UUID."}}"""
private const val POST_NOT_FOUND_BODY = """{"error":{"code":"post_not_found"}}"""

private fun parseUuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private suspend fun io.ktor.server.application.ApplicationCall.respondInvalidUuid() {
    respondText(
        text = INVALID_UUID_BODY,
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.BadRequest,
    )
}
