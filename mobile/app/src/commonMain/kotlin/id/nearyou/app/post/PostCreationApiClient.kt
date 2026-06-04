package id.nearyou.app.post

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * `POST /api/v1/posts` request body. The wire keys are exactly `content`, `latitude`, `longitude` —
 * **bare camelCase**, matching the shipped backend `CreatePostRequestDto` (NOT snake_case, NOT
 * `lat`/`lng`). [content] is the raw composer text; the server NFKC-normalizes + trims + length-guards
 * it (1..280 code points) authoritatively, so the client gate is only a UX nicety (design D5).
 */
@Serializable
data class CreatePostRequestDto(
    val content: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Minimal 201-body DTO — the composer only needs the success signal (the new post id), so this relies
 * on the shared `Json`'s `ignoreUnknownKeys = true` to tolerate the rest of the create response.
 *
 * Casing trap (design D4): on THIS create response `distance_m` and `created_at` are **snake_case**
 * (the backend builds the 201 body via a manual `buildJsonObject`), whereas the Nearby timeline wire
 * emits them camelCase (`distanceM`/`createdAt`). This DTO intentionally ignores both — do NOT add
 * `distanceM`/`createdAt` fields here "to harmonize" with the timeline DTO; the two wires genuinely
 * differ and a shared field would break one of them. The echoed `latitude`/`longitude` (the author's
 * actual coordinate) are likewise NOT read here — they must never reach a rendered field (PII).
 */
@Serializable
data class CreatedPostDto(
    val id: String,
)

/** The backend error envelope `{ "error": { "code", "message" } }`; only [PostErrorEnvelope.code] is
 *  load-bearing (the message is shown via a local canonical string, never the server's). */
@Serializable
data class PostErrorBody(
    val error: PostErrorEnvelope,
)

@Serializable
data class PostErrorEnvelope(
    val code: String,
    val message: String? = null,
)

/**
 * Low-level result of a create-post call, mirroring `AuthApiClient`'s `SignInApiResult`: the parsed
 * id on `201`, the HTTP [status] + best-effort parsed [errorCode] on any non-2xx, or a transport
 * failure. Unlike the Nearby `NearbyApiResult` (status-only), this carries [HttpError.errorCode]
 * because the composer's 400s are user-actionable and each needs distinct copy (design D3). A non-2xx
 * is a value, NEVER a thrown exception.
 */
sealed interface PostCreationApiResult {
    data class Success(val id: String) : PostCreationApiResult

    /** Non-2xx response. [errorCode] is the parsed `error.code` when the envelope decodes, else null
     *  (e.g. a 5xx with an empty/non-JSON body); the repository keys 400 outcomes on it. */
    data class HttpError(val status: Int, val errorCode: String?) : PostCreationApiResult

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data class NetworkError(val cause: Throwable) : PostCreationApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for `POST /api/v1/posts` (the canonical endpoint per
 * `openspec/specs/post-creation/spec.md`). The Bearer `Authorization` header is attached by the
 * shipped `Auth` plugin and a terminal 401 is handled there (→ `SessionInvalidator` → `SignInScreen`)
 * — this client MUST NOT reimplement token attachment or 401 refresh.
 *
 * PII discipline (design D7): the coordinate travels in the request **body**, which is not logged at
 * `LogLevel.HEADERS`; this client MUST NOT `println`/log the body, the coordinate, or the serialized
 * payload, and MUST NOT widen the client log level.
 */
class PostCreationApiClient(
    private val client: HttpClient,
) {
    suspend fun createPost(
        content: String,
        lat: Double,
        lng: Double,
    ): PostCreationApiResult {
        val response: HttpResponse =
            try {
                client.post("/api/v1/posts") {
                    contentType(ContentType.Application.Json)
                    setBody(CreatePostRequestDto(content = content, latitude = lat, longitude = lng))
                }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cause
            } catch (cause: Throwable) {
                return PostCreationApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.Created) {
            return try {
                PostCreationApiResult.Success(response.body<CreatedPostDto>().id)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 201 whose body fails to parse is a transport/contract failure, not a Success.
                PostCreationApiResult.NetworkError(cause)
            }
        }

        // Best-effort parse of the `{ "error": { "code" } }` envelope; an empty/non-JSON body
        // (e.g. a bare 5xx) yields null — the repository maps those to NetworkError on status.
        val errorCode =
            try {
                response.body<PostErrorBody>().error.code
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                null
            }
        return PostCreationApiResult.HttpError(status = response.status.value, errorCode = errorCode)
    }
}
