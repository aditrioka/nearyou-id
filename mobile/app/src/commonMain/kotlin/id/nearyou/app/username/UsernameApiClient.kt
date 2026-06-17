package id.nearyou.app.username

import id.nearyou.app.post.retryAfterSeconds
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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
 * `PATCH /api/v1/user/username` request body, mirroring the SHIPPED `UsernameChangeRequest` wire in
 * `backend/ktor/.../user/UserUsernameRoutes.kt` — **snake_case** `new_username` (NOT camelCase). The
 * field name is generated from the shipped DTO, NOT a stale spec JSON example (the PR #128 casing-drift
 * trap).
 */
@Serializable
data class UsernameChangeRequestDto(
    @SerialName("new_username") val newUsername: String,
)

/** `200` change-success body — the shipped `UsernameChangeResponse` (`{ "username": "<new>" }`, bare). */
@Serializable
data class UsernameChangeResponseDto(
    val username: String,
)

/** `200` probe body — the shipped `UsernameCheckResponse` (`{ "available": <bool> }`, bare). */
@Serializable
data class UsernameCheckResponseDto(
    val available: Boolean,
)

/**
 * The shipped error envelope is a FLAT `{ "error": "<code>" }` (e.g. `{"error":"premium_required"}`) —
 * NOT the nested `{ "error": { "code" } }` some other packages use. The repository reads [error] only to
 * split the two same-status pairs (429 cooldown_active/rate_limited; 422 invalid_username/username_rejected).
 */
@Serializable
data class UsernameErrorBody(val error: String? = null)

/**
 * Low-level result of the `PATCH` change. `200` → [Success]; any non-200 → [HttpError] (status + the flat
 * `error` code + parsed `Retry-After` seconds, present on a 429); transport/parse → [NetworkError]. A
 * non-2xx is a VALUE, never a thrown exception (mirrors `ProfileReadApiResult` / `SearchApiResult`). A
 * `200` whose body fails to parse is a [NetworkError], not a [Success].
 */
sealed interface UsernameChangeApiResult {
    data class Success(val body: UsernameChangeResponseDto) : UsernameChangeApiResult

    data class HttpError(val status: Int, val errorCode: String?, val retryAfterSeconds: Long?) : UsernameChangeApiResult

    data class NetworkError(val cause: Throwable) : UsernameChangeApiResult
}

/** Low-level result of the `GET` probe — same shape as [UsernameChangeApiResult] over the probe body. */
sealed interface UsernameCheckApiResult {
    data class Success(val body: UsernameCheckResponseDto) : UsernameCheckApiResult

    data class HttpError(val status: Int, val errorCode: String?, val retryAfterSeconds: Long?) : UsernameCheckApiResult

    data class NetworkError(val cause: Throwable) : UsernameCheckApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for the two SHIPPED, FROZEN premium-username endpoints
 * (`openspec/specs/premium-username-customization/spec.md`). The Bearer `Authorization` header is
 * attached by the shipped `Auth` plugin and a terminal 401 is handled there (→ `SessionInvalidator` →
 * `SignInScreen`) — this client MUST NOT reimplement token attachment or 401 refresh.
 *
 * PII / logging posture: this client MUST NOT log bodies or widen the client log level. The probe's
 * `?candidate=<handle>` travels in the request line under the inherited `LogLevel.HEADERS` posture (a
 * user-typed term, not a token/UUID/coordinate) — the same posture as `SearchApiClient`'s `q=`; no
 * `candidate`-masking is added.
 */
class UsernameApiClient(
    private val client: HttpClient,
) {
    suspend fun change(newUsername: String): UsernameChangeApiResult {
        val response: HttpResponse =
            try {
                client.patch("/api/v1/user/username") {
                    contentType(ContentType.Application.Json)
                    setBody(UsernameChangeRequestDto(newUsername = newUsername))
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return UsernameChangeApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.OK) {
            return try {
                UsernameChangeApiResult.Success(response.body<UsernameChangeResponseDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                UsernameChangeApiResult.NetworkError(cause)
            }
        }
        return UsernameChangeApiResult.HttpError(
            status = response.status.value,
            errorCode = response.errorCode(),
            retryAfterSeconds = response.retryAfterSeconds(),
        )
    }

    suspend fun check(candidate: String): UsernameCheckApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/username/check") {
                    parameter("candidate", candidate)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return UsernameCheckApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.OK) {
            return try {
                UsernameCheckApiResult.Success(response.body<UsernameCheckResponseDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                UsernameCheckApiResult.NetworkError(cause)
            }
        }
        return UsernameCheckApiResult.HttpError(
            status = response.status.value,
            errorCode = response.errorCode(),
            retryAfterSeconds = response.retryAfterSeconds(),
        )
    }

    /** Best-effort parse of the flat `{ "error": "<code>" }` envelope; null on an empty/non-JSON body. */
    private suspend fun HttpResponse.errorCode(): String? =
        try {
            body<UsernameErrorBody>().error
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            null
        }
}
