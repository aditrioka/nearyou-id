package id.nearyou.app.followlist

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * One follower/following row — the embedded profile summary, mirroring the **shipped** backend
 * serialization in `backend/ktor/.../follow/FollowRoutes.kt` (`FollowListItem`): **bare camelCase**, NO
 * `@SerialName` (matching `UserProfileResponse` / the timeline `author*` identity precedent), regenerated
 * from the wire — NOT a stale snake_case spec example (the PR #128 / `mobile-timeline-card-redesign`
 * casing-drift trap). [userId] is the row user's UUID — the `ProfileRoute` resource key (the row tap
 * target) — and MUST NEVER be rendered as a UI string. [createdAt] is the follow-edge timestamp (the
 * cursor axis), not a profile field and not rendered. [username] / [displayName] are NOT NULL on the wire
 * (V2); there is no `akun_dihapus` placeholder on these lists — hidden members are excluded server-side.
 */
@Serializable
data class FollowListItemDto(
    val userId: String,
    val username: String,
    val displayName: String,
    val isPremium: Boolean,
    val createdAt: String,
)

/**
 * The `/followers` + `/following` page envelope, mirroring the shipped `FollowListResponse`: `users` + a
 * bare camelCase nullable [nextCursor] (null on the last page; the app-wide `Json { explicitNulls = false }`
 * omits it, so an absent key MUST decode to `null`, not throw — hence `= null`).
 */
@Serializable
data class FollowListPageDto(
    val users: List<FollowListItemDto>,
    val nextCursor: String? = null,
)

/**
 * Low-level result of a list fetch, mirroring `NearbyApiResult`: a parsed body on `200`, the HTTP [status]
 * on any non-2xx (the repository keys outcome on [status] — the constant `404` is the only 404 cause), or a
 * transport/parse failure. Non-2xx is a VALUE, never a thrown exception; `CancellationException` is rethrown.
 */
sealed interface FollowListApiResult {
    data class Success(val body: FollowListPageDto) : FollowListApiResult

    /** Non-2xx response. The repository maps `404` → `NotFound`; `400 invalid_cursor` / `5xx` / any other →
     *  the retryable `NetworkError`; `401` is already handled by the shipped `Auth` plugin upstream. */
    data class HttpError(val status: Int) : FollowListApiResult

    /** Transport-level failure (IOException, timeout, host unreachable) or a 200-body parse failure. */
    data class NetworkError(val cause: Throwable) : FollowListApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for `GET /api/v1/users/{id}/followers` + `/following`
 * (`follow-system`). The Bearer `Authorization` header is attached by the shipped `Auth` plugin and a
 * terminal `401` is handled there — this client MUST NOT reimplement token attachment or 401 refresh. NO
 * `X-Session-Id` header (these reads are not per-session soft-capped). This client MUST NOT `println`/log
 * bodies or the `userId`, and MUST NOT widen the client log level (PII discipline).
 */
class FollowListApiClient(
    private val client: HttpClient,
) {
    suspend fun fetch(
        userId: String,
        tab: FollowListTab,
        cursor: String?,
    ): FollowListApiResult {
        val path =
            when (tab) {
                FollowListTab.Followers -> "/api/v1/users/$userId/followers"
                FollowListTab.Following -> "/api/v1/users/$userId/following"
            }
        val response: HttpResponse =
            try {
                client.get(path) { cursor?.let { parameter("cursor", it) } }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors NearbyTimelineApiClient).
                throw cause
            } catch (cause: Throwable) {
                return FollowListApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                FollowListApiResult.Success(response.body<FollowListPageDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 200 whose body fails to parse (e.g. a snake_case wire-drift body) is a transport/contract
                // failure, NOT a Success — the casing-drift negative guard (PR #128 precedent).
                FollowListApiResult.NetworkError(cause)
            }
        }

        return FollowListApiResult.HttpError(status = response.status.value)
    }
}
