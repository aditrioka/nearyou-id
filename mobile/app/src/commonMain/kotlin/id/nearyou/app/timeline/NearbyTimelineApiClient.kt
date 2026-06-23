package id.nearyou.app.timeline

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/**
 * One Nearby-timeline post, mirroring the **shipped** backend serialization in
 * `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineRoutes.kt` (`NearbyPostDto`) — which
 * is **mixed-case, NOT uniformly snake_case** (design D10). The field names are regenerated from the
 * shipped DTO, NOT from the `nearby-timeline` spec's snake_case JSON example (which is stale relative
 * to the deployed wire — tracked by the `timeline-response-dto-casing-drift` FOLLOW_UP):
 *  - bare camelCase (no `@SerialName`): `id`, `authorUserId`, `authorUsername`, `authorDisplayName`,
 *    `content`, `latitude`, `longitude`, `distanceM`, `createdAt`;
 *  - `@SerialName` snake_case for exactly three: `city_name`, `liked_by_viewer`, `reply_count`.
 *
 * `latitude`/`longitude` are display-only (derived from `display_location`) and MUST NOT be rendered
 * as raw coordinates; the user-facing distance string is produced by `DistanceRenderer.render(distanceM)`.
 * `authorUserId` is a UUID and MUST NOT be rendered (PII discipline). `authorUsername` /
 * `authorDisplayName` (added by `mobile-timeline-card-redesign`) are the author DISPLAY identity the
 * card renders — required non-null: the backend sends them on every post (NOT NULL since V2) and
 * mobile + backend land in the same squash-merge.
 */
@Serializable
data class NearbyPostDto(
    val id: String,
    val authorUserId: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    // Nullable as of the hide-distance capability: the backend OMITS distanceM (explicitNulls=false)
    // when the symmetric hide rule suppresses it for this (author, viewer) pair. A non-null value is
    // raw meters; an absent one means "distance hidden" → the card renders city-only (the shipped
    // null-tolerant PostCard needs no change). A non-null declaration would throw MissingFieldException.
    val distanceM: Double? = null,
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
 * The Nearby response envelope, mirroring the shipped `NearbyResponse`: `posts`, a bare camelCase
 * `nextCursor` (the backend emits camelCase here — NOT `next_cursor`), and an optional `upsell`.
 * `nextCursor` + `upsell` tolerate absence/null (the shared `Json` sets `ignoreUnknownKeys` +
 * `explicitNulls = false`; the backend strips defaults via `@EncodeDefault(NEVER)`).
 */
@Serializable
data class NearbyResponseDto(
    val posts: List<NearbyPostDto>,
    val nextCursor: String? = null,
    val upsell: UpsellDto? = null,
)

/**
 * The optional rate-limit upsell object (shipped `Upsell` — bare `soft`/`hard`). The backend omits
 * the whole object below all caps and omits each absent flag, so non-null defaults of `false` map
 * the absent → not-capped case. `soft = true` ⇒ session soft-cap nudge; `hard = true` ⇒ rolling
 * hourly hard cap (response then carries empty `posts`).
 */
@Serializable
data class UpsellDto(
    val soft: Boolean = false,
    val hard: Boolean = false,
)

/**
 * Low-level result of a Nearby fetch, mirroring `AuthApiClient`'s `SignInApiResult`: a parsed body
 * on `200`, the HTTP [status] on any non-2xx (the repository maps on [HttpError.status], never on a
 * parsed `error.code`), or a transport failure. Non-2xx is a value, NEVER a thrown exception.
 */
sealed interface NearbyApiResult {
    data class Success(val body: NearbyResponseDto) : NearbyApiResult

    /** Non-2xx response. The repository keys outcome on [status] (400 → retryable Error, 5xx →
     *  NetworkError, 401 is already handled by the shipped `Auth` plugin upstream). [errorCode] is the
     *  best-effort `error.code` parsed from the `respondError` envelope — `mobile-nearby-radius-slider`
     *  reads it ONLY to distinguish the `radius_premium_only` 403; null when absent/unparseable. */
    data class HttpError(val status: Int, val errorCode: String? = null) : NearbyApiResult

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data class NetworkError(val cause: Throwable) : NearbyApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for `GET /api/v1/timeline/nearby` (the canonical endpoint
 * per `openspec/specs/nearby-timeline/spec.md`). The Bearer `Authorization` header is attached by the
 * shipped `Auth` plugin — this client MUST NOT reimplement token attachment or 401 refresh.
 *
 * The `X-Session-Id` header carries the per-process [SessionIdProvider] id so the backend's per-session
 * soft-cap accounting works (rather than collapsing every read into the `no-session` bucket).
 */
class NearbyTimelineApiClient(
    private val client: HttpClient,
) {
    suspend fun fetchNearby(
        lat: Double,
        lng: Double,
        radiusM: Int,
        sessionId: String,
        cursor: String? = null,
    ): NearbyApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/timeline/nearby") {
                    parameter("lat", lat)
                    parameter("lng", lng)
                    parameter("radius_m", radiusM)
                    cursor?.let { parameter("cursor", it) }
                    header("X-Session-Id", sessionId)
                }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cause
            } catch (cause: Throwable) {
                return NearbyApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                NearbyApiResult.Success(response.body<NearbyResponseDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 200 whose body fails to parse is a transport/contract failure, not a Loaded.
                NearbyApiResult.NetworkError(cause)
            }
        }

        return NearbyApiResult.HttpError(
            status = response.status.value,
            errorCode = response.parseErrorCodeOrNull(),
        )
    }
}

/** Json used only for the best-effort error-envelope parse; tolerant of extra fields. */
private val ERROR_ENVELOPE_JSON = Json { ignoreUnknownKeys = true }

/**
 * Best-effort parse of `error.code` from the shared `respondError` envelope
 * (`{"error":{"code":...,"message":...}}`) on a non-2xx Nearby response. Returns null on any failure
 * (empty body, a different error shape, malformed JSON) — the repository still keys on the HTTP status;
 * only `mobile-nearby-radius-slider` reads this (for the `radius_premium_only` 403 backstop).
 */
private suspend fun HttpResponse.parseErrorCodeOrNull(): String? =
    try {
        ERROR_ENVELOPE_JSON.parseToJsonElement(bodyAsText())
            .jsonObject["error"]
            ?.jsonObject?.get("code")
            ?.jsonPrimitive?.contentOrNull
    } catch (_: Throwable) {
        null
    }
