package id.nearyou.app.search

import id.nearyou.app.post.retryAfterSeconds
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * One search hit, mirroring the **shipped** backend serialization in
 * `backend/ktor/src/main/kotlin/id/nearyou/app/search/SearchRoutes.kt` (`SearchResultDto`) — which is
 * **snake_case** (`post_id`/`author_id`/`author_username`/`author_display_name`/`created_at`) with bare
 * `content`/`rank`, like the post-detail reply wire and NOT the timelines' camelCase. The field names are
 * regenerated from the shipped DTO, NOT from any spec JSON example (the PR #128 casing-drift trap). The
 * shared `Json` sets `ignoreUnknownKeys` + `explicitNulls = false`.
 *
 * [authorId] is a UUID and [rank] is an internal FTS score — both parsed but NEVER rendered (PII /
 * internal-ranking discipline; the `SearchUiState` projection strips them so the rendered state cannot
 * leak them). [authorUsername] / [authorDisplayName] are the author DISPLAY identity the card renders.
 */
@Serializable
data class SearchResultDto(
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_username") val authorUsername: String,
    @SerialName("author_display_name") val authorDisplayName: String,
    val content: String,
    @SerialName("created_at") val createdAt: String,
    val rank: Float,
)

/**
 * The search response envelope, mirroring the shipped `SearchResponse`: bare `results` and a snake_case
 * `@SerialName("next_offset") nextOffset` (NOT the timelines' camelCase `nextCursor`). [nextOffset] is
 * the integer to pass as the next page's `offset`, or `null` when fewer than a full page was returned
 * (terminal).
 */
@Serializable
data class SearchResponseDto(
    val results: List<SearchResultDto>,
    @SerialName("next_offset") val nextOffset: Int? = null,
)

/**
 * Low-level result of a search fetch, mirroring [id.nearyou.app.timeline.GlobalApiResult]: a parsed body
 * on `200`, the HTTP [HttpError.status] on any non-2xx (the repository maps on status, never on a parsed
 * `error.code`), or a transport failure. Non-2xx is a value, NEVER a thrown exception. The 429 carries the
 * parsed `Retry-After` seconds ([HttpError.retryAfterSeconds]) — the rate-limit reset's only signal.
 */
sealed interface SearchApiResult {
    data class Success(val body: SearchResponseDto) : SearchApiResult

    /** Non-2xx response. The repository keys outcome on [status]; [retryAfterSeconds] is the parsed
     *  `Retry-After` header (seconds), present on a 429 (the limiter sets it ≥ 1), else null. */
    data class HttpError(val status: Int, val retryAfterSeconds: Long?) : SearchApiResult

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data class NetworkError(val cause: Throwable) : SearchApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for `GET /api/v1/search?q=<query>&offset=<n>` (the canonical
 * Premium-search endpoint per `openspec/specs/premium-search/spec.md`). Search is **global** — no spatial
 * filter, so NO `lat`/`lng`/`radius_m`. The Bearer `Authorization` header is attached by the shipped `Auth`
 * plugin — this client MUST NOT reimplement token attachment or 401 refresh. The first-page request sends
 * `offset=0`; a load-more sends the retained `next_offset`. PII discipline: the query travels in the
 * request line per the inherited `LogLevel.HEADERS` posture (a user-typed term, not a token/UUID).
 */
class SearchApiClient(
    private val client: HttpClient,
) {
    suspend fun search(
        query: String,
        offset: Int,
    ): SearchApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/search") {
                    parameter("q", query)
                    parameter("offset", offset)
                }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors GlobalTimelineApiClient).
                throw cause
            } catch (cause: Throwable) {
                return SearchApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                SearchApiResult.Success(response.body<SearchResponseDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 200 whose body fails to parse is a transport/contract failure, not a Results.
                SearchApiResult.NetworkError(cause)
            }
        }

        return SearchApiResult.HttpError(
            status = response.status.value,
            retryAfterSeconds = response.retryAfterSeconds(),
        )
    }
}
