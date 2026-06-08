package id.nearyou.app.post

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * `GET /api/v1/posts/{post_id}/likes/count` body — `{ "count": <Long> }`, mirroring the SHIPPED
 * backend `LikesCountResponse` in `backend/ktor/.../engagement/LikeRoutes.kt`. A bare `count` (no
 * `@SerialName`); the shared `Json` (`ignoreUnknownKeys = true`) tolerates any future sibling fields.
 */
@Serializable
data class LikesCountResponse(val count: Long)

/**
 * Low-level result of a like / unlike call. `204 No Content` → [NoContent] (both verbs' happy path;
 * `DELETE` is a pure no-op that NEVER 404s per the shipped contract); any non-204 → [HttpError]
 * carrying the status + the parsed `Retry-After` seconds (non-null only on a 429); transport failure →
 * [NetworkError]. A non-2xx is a VALUE, never a thrown exception (mirrors `PostCreationApiResult`).
 */
sealed interface LikeApiResult {
    data object NoContent : LikeApiResult

    /** Non-204 response. [retryAfterSeconds] is the parsed `Retry-After` header (seconds), present on
     *  a 429 (the limiter always sets it ≥ 1), else null. The like endpoints key outcome on [status]
     *  (no `error.code` distinction — 404/429/5xx are status-only). */
    data class HttpError(val status: Int, val retryAfterSeconds: Long?) : LikeApiResult

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data class NetworkError(val cause: Throwable) : LikeApiResult
}

/**
 * Low-level result of a like-count fetch. `200` → [Success]; non-200 → [HttpError]; transport →
 * [NetworkError]. The repository degrades a non-Success to "no count shown" (the toggle stays usable).
 */
sealed interface LikeCountApiResult {
    data class Success(val count: Long) : LikeCountApiResult

    data class HttpError(val status: Int) : LikeCountApiResult

    data class NetworkError(val cause: Throwable) : LikeCountApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for the post-scoped like sub-resources
 * (`POST` / `DELETE /api/v1/posts/{post_id}/like` + `GET /api/v1/posts/{post_id}/likes/count`). The
 * Bearer `Authorization` header is attached by the shipped `Auth` plugin and a terminal 401 is handled
 * there (→ `SessionInvalidator` → `SignInScreen`) — this client MUST NOT reimplement token attachment
 * or 401 refresh. NO `X-Session-Id` header: the like endpoints are NOT per-session soft-capped (unlike
 * the timeline reads). This client MUST NOT `println`/log bodies or coordinates, and MUST NOT widen the
 * client log level (PII discipline; `display_location` / coordinates never travel on these calls).
 */
class LikeApiClient(
    private val client: HttpClient,
) {
    suspend fun like(postId: String): LikeApiResult = toggle { client.post(likePath(postId)) }

    suspend fun unlike(postId: String): LikeApiResult = toggle { client.delete(likePath(postId)) }

    suspend fun count(postId: String): LikeCountApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/posts/$postId/likes/count")
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cause
            } catch (cause: Throwable) {
                return LikeCountApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.OK) {
            return try {
                LikeCountApiResult.Success(response.body<LikesCountResponse>().count)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 200 whose body fails to parse is a transport/contract failure, not a Success.
                LikeCountApiResult.NetworkError(cause)
            }
        }
        return LikeCountApiResult.HttpError(status = response.status.value)
    }

    private suspend fun toggle(execute: suspend () -> HttpResponse): LikeApiResult {
        val response: HttpResponse =
            try {
                execute()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return LikeApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.NoContent) return LikeApiResult.NoContent
        return LikeApiResult.HttpError(status = response.status.value, retryAfterSeconds = response.retryAfterSeconds())
    }

    private fun likePath(postId: String): String = "/api/v1/posts/$postId/like"
}

/** Parses the `Retry-After` header as whole seconds (the backend limiter emits a seconds integer ≥ 1).
 *  Returns null when absent or non-numeric. Shared by [LikeApiClient] + `ReplyApiClient`. */
internal fun HttpResponse.retryAfterSeconds(): Long? = headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
