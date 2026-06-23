package id.nearyou.app.timeline

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * One Following-timeline post, mirroring the **shipped** backend serialization in
 * `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineRoutes.kt` (`FollowingPostDto`) — which
 * is **mixed-case, NOT uniformly snake_case** (design D1), and which — because Following has **no
 * spatial filter** — carries **NO `distanceM`**. The field names are regenerated from the shipped DTO,
 * NOT from any spec's snake_case JSON example (the casing-drift trap that bit Nearby):
 *  - bare camelCase (no `@SerialName`): `id`, `authorUserId`, `authorUsername`, `authorDisplayName`,
 *    `content`, `latitude`, `longitude`, `createdAt`;
 *  - `@SerialName` snake_case for exactly three: `city_name`, `liked_by_viewer`, `reply_count`.
 *
 * `latitude`/`longitude` are NOT rendered as raw coordinates; there is NO distance on this surface (no
 * `DistanceRenderer`). `authorUserId` is a UUID and MUST NOT be rendered (PII discipline).
 * `authorUsername` / `authorDisplayName` (added to this endpoint by `mobile-timeline-card-redesign` for
 * exactly this consumer) are the author DISPLAY identity the card renders — required non-null (NOT NULL
 * since V2).
 */
@Serializable
data class FollowingPostDto(
    val id: String,
    val authorUserId: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("city_name") val cityName: String,
    val createdAt: String,
    @SerialName("liked_by_viewer") val likedByViewer: Boolean,
    @SerialName("reply_count") val replyCount: Int,
    // image-attached-posts (D4): the public image delivery URL, BARE camelCase on the wire (the
    // TimelineRoutes.kt mixed-case precedent — NOT snake_case). The backend OMITS it (explicitNulls=false)
    // for a text-only post, so it decodes to null. NOT a raw coordinate — safe to carry/render.
    val imageUrl: String? = null,
)

/**
 * The Following response envelope, mirroring the shipped `FollowingResponse`: `posts`, a bare camelCase
 * `nextCursor` (the backend emits camelCase here — NOT `next_cursor`), and an optional `upsell`. The
 * [UpsellDto] is the shared shipped `Upsell` (bare `soft`/`hard`) declared by the Nearby client and
 * **reused here, NOT re-declared**. `nextCursor` + `upsell` tolerate absence/null (the shared `Json`
 * sets `ignoreUnknownKeys` + `explicitNulls = false`; the backend strips defaults via
 * `@EncodeDefault(NEVER)`).
 */
@Serializable
data class FollowingResponseDto(
    val posts: List<FollowingPostDto>,
    val nextCursor: String? = null,
    val upsell: UpsellDto? = null,
)

/**
 * Low-level result of a Following fetch, mirroring [GlobalApiResult]: a parsed body on `200`, the HTTP
 * [status] on any non-2xx (the repository maps on [HttpError.status], never on a parsed `error.code`),
 * or a transport failure. Non-2xx is a value, NEVER a thrown exception.
 */
sealed interface FollowingApiResult {
    data class Success(val body: FollowingResponseDto) : FollowingApiResult

    /** Non-2xx response. The repository keys outcome on [status] (400 → retryable Error, 5xx →
     *  NetworkError, 401 → SessionExpired ahead of the fallback). */
    data class HttpError(val status: Int) : FollowingApiResult

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data class NetworkError(val cause: Throwable) : FollowingApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for `GET /api/v1/timeline/following` (the canonical endpoint
 * per `openspec/specs/following-timeline/spec.md`). Following has **no spatial filter**, so the request
 * carries **NO `lat`/`lng`/`radius_m`** — only an optional `cursor`. The Bearer `Authorization` header
 * is attached by the shipped `Auth` plugin — this client MUST NOT reimplement token attachment or 401
 * refresh.
 *
 * The `X-Session-Id` header carries the per-process [SessionIdProvider] id so the backend's per-session
 * soft-cap accounting works (rather than collapsing every read into the `no-session` bucket).
 */
class FollowingTimelineApiClient(
    private val client: HttpClient,
) {
    suspend fun fetchFollowing(
        sessionId: String,
        cursor: String? = null,
    ): FollowingApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/timeline/following") {
                    // Following has no spatial filter: NO lat / lng / radius_m.
                    cursor?.let { parameter("cursor", it) }
                    header("X-Session-Id", sessionId)
                }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cause
            } catch (cause: Throwable) {
                return FollowingApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                FollowingApiResult.Success(response.body<FollowingResponseDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 200 whose body fails to parse is a transport/contract failure, not a Loaded.
                FollowingApiResult.NetworkError(cause)
            }
        }

        return FollowingApiResult.HttpError(status = response.status.value)
    }
}
