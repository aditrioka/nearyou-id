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
 * One Global-timeline post, mirroring the **shipped** backend serialization in
 * `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineRoutes.kt` (`GlobalPostDto`) — which
 * is **mixed-case, NOT uniformly snake_case** (design D4), and which — because Global has **no
 * spatial filter** — carries **NO `distanceM`**. The field names are regenerated from the shipped
 * DTO, NOT from any spec's snake_case JSON example (the casing-drift trap that bit Nearby):
 *  - bare camelCase (no `@SerialName`): `id`, `authorUserId`, `authorUsername`, `authorDisplayName`,
 *    `content`, `latitude`, `longitude`, `createdAt`;
 *  - `@SerialName` snake_case for exactly three: `city_name`, `liked_by_viewer`, `reply_count`.
 *
 * `latitude`/`longitude` are NOT rendered as raw coordinates; there is NO distance on this surface (no
 * `DistanceRenderer`). `authorUserId` is a UUID and MUST NOT be rendered (PII discipline).
 * `authorUsername` / `authorDisplayName` (added by `mobile-timeline-card-redesign`) are the author
 * DISPLAY identity the card renders — required non-null (NOT NULL since V2; mobile + backend land in
 * the same squash-merge).
 */
@Serializable
data class GlobalPostDto(
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
 * The Global response envelope, mirroring the shipped `GlobalResponse`: `posts`, a bare camelCase
 * `nextCursor` (the backend emits camelCase here — NOT `next_cursor`), and an optional `upsell`. The
 * [UpsellDto] is the shared shipped `Upsell` (bare `soft`/`hard`) declared by the Nearby client and
 * **reused here, NOT re-declared**. `nextCursor` + `upsell` tolerate absence/null (the shared `Json`
 * sets `ignoreUnknownKeys` + `explicitNulls = false`; the backend strips defaults via
 * `@EncodeDefault(NEVER)`).
 */
@Serializable
data class GlobalResponseDto(
    val posts: List<GlobalPostDto>,
    val nextCursor: String? = null,
    val upsell: UpsellDto? = null,
)

/**
 * Low-level result of a Global fetch, mirroring [NearbyApiResult]: a parsed body on `200`, the HTTP
 * [status] on any non-2xx (the repository maps on [HttpError.status], never on a parsed `error.code`),
 * or a transport failure. Non-2xx is a value, NEVER a thrown exception.
 */
sealed interface GlobalApiResult {
    data class Success(val body: GlobalResponseDto) : GlobalApiResult

    /** Non-2xx response. The repository keys outcome on [status] (400 → retryable Error, 5xx →
     *  NetworkError, 401 is already handled by the shipped `Auth` plugin upstream). */
    data class HttpError(val status: Int) : GlobalApiResult

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data class NetworkError(val cause: Throwable) : GlobalApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for `GET /api/v1/timeline/global` (the canonical endpoint
 * per `openspec/specs/global-timeline/spec.md`). Global has **no spatial filter**, so the request
 * carries **NO `lat`/`lng`/`radius_m`** — only an optional `cursor`. The Bearer `Authorization` header
 * is attached by the shipped `Auth` plugin — this client MUST NOT reimplement token attachment or 401
 * refresh.
 *
 * The `X-Session-Id` header carries the per-process [SessionIdProvider] id so the backend's per-session
 * soft-cap accounting works (rather than collapsing every read into the `no-session` bucket).
 */
class GlobalTimelineApiClient(
    private val client: HttpClient,
) {
    suspend fun fetchGlobal(
        sessionId: String,
        cursor: String? = null,
    ): GlobalApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/timeline/global") {
                    // Global has no spatial filter: NO lat / lng / radius_m.
                    cursor?.let { parameter("cursor", it) }
                    header("X-Session-Id", sessionId)
                }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cause
            } catch (cause: Throwable) {
                return GlobalApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                GlobalApiResult.Success(response.body<GlobalResponseDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 200 whose body fails to parse is a transport/contract failure, not a Loaded.
                GlobalApiResult.NetworkError(cause)
            }
        }

        return GlobalApiResult.HttpError(status = response.status.value)
    }
}
