package id.nearyou.app.profile

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * `GET /api/v1/users/{user_id}` body — `UserProfileResponse`, mirroring the SHIPPED backend
 * serialization in `backend/ktor/.../user/` (`user-profile-read`). **camelCase** (matching the repo's
 * timeline-DTO wire convention), generated from the wire, NOT a stale spec JSON example. [bio] and
 * [isPrivate] are nullable-with-default because the app-wide `Json { explicitNulls = false }` OMITS them
 * from the JSON when null — an absent key MUST decode to null, not throw (so they MUST be `= null`).
 * [isPrivate] is self-only (absent on every other-user read). [userId] is the profile's own id (a UUID);
 * it is the route resource key, never rendered as a UI string.
 */
@Serializable
data class UserProfileResponse(
    val userId: String,
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val followerCount: Int,
    val followingCount: Int,
    val isSelf: Boolean,
    val followedByViewer: Boolean,
    val isPremium: Boolean,
    val isPrivate: Boolean? = null,
)

/**
 * `POST /api/v1/reports` request body, mirroring the SHIPPED `reports` wire — **snake_case**
 * (`target_type`, `target_id`, `reason_category`, `reason_note`). The profile surface always sends
 * `target_type = "user"`. [reasonNote] is omitted from the JSON when null (the app-wide
 * `Json { explicitNulls = false }`), so a blank note is normalized to null by the caller.
 */
@Serializable
data class ReportRequest(
    @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: String,
    @SerialName("reason_category") val reasonCategory: String,
    @SerialName("reason_note") val reasonNote: String? = null,
)

/** The `{ "error": { "code" } }` envelope (profile-local mirror of the per-package error bodies). */
@Serializable
data class ProfileErrorBody(val error: ProfileErrorDetail)

@Serializable
data class ProfileErrorDetail(val code: String? = null)

/**
 * Low-level result of the profile read. `200` → [Success]; any non-200 → [HttpError] (carrying the
 * status + best-effort `error.code` — `user_not_found` for the constant 404); transport/parse failure →
 * [NetworkError]. A non-2xx is a VALUE, never a thrown exception (mirrors `LikeApiResult`). A `200` whose
 * body fails to parse (e.g. a snake_case wire-drift body) is a [NetworkError], not a [Success].
 */
sealed interface ProfileReadApiResult {
    data class Success(val body: UserProfileResponse) : ProfileReadApiResult

    data class HttpError(val status: Int, val errorCode: String?) : ProfileReadApiResult

    data class NetworkError(val cause: Throwable) : ProfileReadApiResult
}

/**
 * Low-level result of a no-body action (follow / unfollow / block / report). `204` → [NoContent]; any
 * non-204 → [HttpError] (status + best-effort `error.code` + parsed `Retry-After` seconds, present on a
 * 429); transport → [NetworkError]. A non-2xx is a VALUE, never a thrown exception.
 */
sealed interface ActionApiResult {
    data object NoContent : ActionApiResult

    data class HttpError(val status: Int, val errorCode: String?, val retryAfterSeconds: Long?) : ActionApiResult

    data class NetworkError(val cause: Throwable) : ActionApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for the profile surface's reads + actions
 * (`GET /api/v1/users/{id}`, `POST`/`DELETE /api/v1/follows/{id}`, `POST /api/v1/blocks/{id}`,
 * `POST /api/v1/reports`). The Bearer `Authorization` header is attached by the shipped `Auth` plugin
 * and a terminal 401 is handled there (→ `SessionInvalidator` → `SignInScreen`) — this client MUST NOT
 * reimplement token attachment or 401 refresh. NO `X-Session-Id` header (none of these endpoints are
 * per-session soft-capped). This client MUST NOT `println`/log bodies, the `userId`, or any coordinate,
 * and MUST NOT widen the client log level (PII discipline).
 */
class ProfileApiClient(
    private val client: HttpClient,
) {
    suspend fun getProfile(userId: String): ProfileReadApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/users/$userId")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return ProfileReadApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.OK) {
            return try {
                ProfileReadApiResult.Success(response.body<UserProfileResponse>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 200 whose body fails to parse (e.g. snake_case wire drift) is a transport/contract
                // failure, NOT a Success — the casing-drift negative guard (PR #128 precedent).
                ProfileReadApiResult.NetworkError(cause)
            }
        }
        return ProfileReadApiResult.HttpError(status = response.status.value, errorCode = response.errorCode())
    }

    suspend fun follow(userId: String): ActionApiResult = action { client.post("/api/v1/follows/$userId") }

    suspend fun unfollow(userId: String): ActionApiResult = action { client.delete("/api/v1/follows/$userId") }

    suspend fun block(userId: String): ActionApiResult = action { client.post("/api/v1/blocks/$userId") }

    suspend fun report(
        userId: String,
        reasonCategory: String,
        reasonNote: String?,
    ): ActionApiResult =
        action {
            client.post("/api/v1/reports") {
                contentType(ContentType.Application.Json)
                setBody(
                    ReportRequest(
                        targetType = "user",
                        targetId = userId,
                        reasonCategory = reasonCategory,
                        // A blank note is normalized to null so the key is omitted from the body.
                        reasonNote = reasonNote?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }

    private suspend fun action(execute: suspend () -> HttpResponse): ActionApiResult {
        val response: HttpResponse =
            try {
                execute()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return ActionApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.NoContent) return ActionApiResult.NoContent
        return ActionApiResult.HttpError(
            status = response.status.value,
            errorCode = response.errorCode(),
            retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull(),
        )
    }

    /** Best-effort parse of the `{ "error": { "code" } }` envelope; null on an empty/non-JSON body. */
    private suspend fun HttpResponse.errorCode(): String? =
        try {
            body<ProfileErrorBody>().error.code
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            null
        }
}
