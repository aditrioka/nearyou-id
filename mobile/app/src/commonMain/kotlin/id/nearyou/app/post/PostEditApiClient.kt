package id.nearyou.app.post

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * `PATCH /api/v1/posts/{post_id}` request body. The single wire key is `content` (bare camelCase),
 * matching the shipped backend `EditPostRequestDto`. The server NFKC-normalizes + length-guards +
 * re-moderates authoritatively (the `post-editing` capability), so the client gate is only a UX nicety.
 */
@Serializable
data class EditPostRequestDto(
    val content: String,
)

/**
 * Minimal `200` edit body — the editor only needs the updated content back. The PATCH success body is a
 * manual `buildJsonObject` `{ id, content, updated_at }` (snake_case `updated_at`); this DTO reads only
 * [content] and relies on the shared `Json`'s `ignoreUnknownKeys = true` for the rest. Do NOT add an
 * `editedAt`/`updatedAt` field here — the label's edited-signal comes from the `single-post-read`
 * projection ([SinglePostApiClient]), not this response.
 */
@Serializable
data class EditedPostDto(
    val content: String,
)

/** One edit-history version. The history wire is **snake_case** (`version_label` / `content` / `edited_at`),
 *  a manual `buildJsonObject` on the backend (NOT the stale spec JSON example — the PR #128 casing trap);
 *  the `@SerialName`s pin those exact keys. Content-only + version label + timestamp — NO location (D10). */
@Serializable
data class EditVersionDto(
    @SerialName("version_label") val versionLabel: String,
    val content: String,
    @SerialName("edited_at") val editedAt: String,
)

/** `GET /api/v1/posts/{post_id}/edits` body: `{ "edits": [ … ] }`. */
@Serializable
data class EditHistoryResponseDto(
    val edits: List<EditVersionDto> = emptyList(),
)

/**
 * Low-level result of `PATCH /api/v1/posts/{post_id}` (mirrors [PostCreationApiResult]): the updated
 * [content] on `200`; the HTTP [status] + best-effort parsed [errorCode] + the `Retry-After` seconds on
 * any non-2xx; or a transport failure. A non-2xx is a value, NEVER a thrown exception. `401` is handled
 * by the shipped `Auth` plugin and never reaches here.
 */
sealed interface EditPostApiResult {
    data class Success(val content: String) : EditPostApiResult

    data class HttpError(
        val status: Int,
        val errorCode: String?,
        val retryAfterSeconds: Long?,
    ) : EditPostApiResult

    data class NetworkError(val cause: Throwable) : EditPostApiResult
}

/** Low-level result of `GET /api/v1/posts/{post_id}/edits`. `200` → the parsed versions; any non-200 or
 *  transport failure → the retryable error (the modal's spec'd states are loading / loaded / empty / error). */
sealed interface EditHistoryApiResult {
    data class Success(val versions: List<EditVersionDto>) : EditHistoryApiResult

    data class HttpError(val status: Int) : EditHistoryApiResult

    data class NetworkError(val cause: Throwable) : EditHistoryApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for the two `post-editing` endpoints. The Bearer header +
 * terminal 401 are owned by the shipped `Auth` plugin — this client MUST NOT reimplement them. The edit
 * content travels in the request body (not logged at `LogLevel.HEADERS`); this client never `println`s/logs.
 */
class PostEditApiClient(
    private val client: HttpClient,
) {
    suspend fun editPost(
        postId: String,
        content: String,
    ): EditPostApiResult {
        val response: HttpResponse =
            try {
                client.patch("/api/v1/posts/$postId") {
                    contentType(ContentType.Application.Json)
                    setBody(EditPostRequestDto(content = content))
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return EditPostApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                EditPostApiResult.Success(response.body<EditedPostDto>().content)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                EditPostApiResult.NetworkError(cause)
            }
        }

        val errorCode =
            try {
                response.body<PostErrorBody>().error.code
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                null
            }
        return EditPostApiResult.HttpError(
            status = response.status.value,
            errorCode = errorCode,
            retryAfterSeconds = response.retryAfterSeconds(),
        )
    }

    suspend fun loadHistory(postId: String): EditHistoryApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/posts/$postId/edits")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return EditHistoryApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                EditHistoryApiResult.Success(response.body<EditHistoryResponseDto>().edits)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                EditHistoryApiResult.NetworkError(cause)
            }
        }
        return EditHistoryApiResult.HttpError(status = response.status.value)
    }
}
