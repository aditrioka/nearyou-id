package id.nearyou.app.post

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * Minimal `single-post-read` projection the post-detail refresh consumes: the current [content] (so a
 * post edited in a prior session / on another device shows fresh text) and [editedAt] (the only
 * edited-signal that drives the "Diedit" label). Both are **bare camelCase** on the wire (the
 * `SinglePostResponse` mixed-case convention — `editedAt` like `createdAt`); `editedAt` is omitted when
 * the post has never been edited (`explicitNulls = false` on the server), so it decodes to `null` here.
 * The remaining `SinglePostResponse` fields are tolerated via the shared `Json`'s `ignoreUnknownKeys`.
 * No author UUID / coordinate is declared (the projection carries none — no-PII).
 */
@Serializable
data class SinglePostDto(
    val content: String,
    val editedAt: String? = null,
    val isAuthor: Boolean = false,
)

/**
 * Low-level result of `GET /api/v1/posts/{post_id}` (the post-detail freshness fetch). `200` → the parsed
 * projection; any non-200 (incl. `404 post_not_found`) or transport failure → the graceful
 * [Unavailable] — a freshness fetch failure MUST NOT error the post-detail surface (the header keeps the
 * nav-payload content; the "Diedit" label simply stays hidden). `401` is owned by the `Auth` plugin.
 */
sealed interface SinglePostApiResult {
    data class Success(val post: SinglePostDto) : SinglePostApiResult

    data object Unavailable : SinglePostApiResult
}

/**
 * FULL `single-post-read` projection — the no-card consumer (notification deep-links into a post,
 * `mobile-notifications-deep-link-targets`) reads every display field needed to build a post-detail
 * nav payload. Mirrors the SHIPPED `SinglePostResponse` wire's MIXED case EXACTLY (verified against
 * `backend/.../post/SinglePostRoutes.kt`): bare camelCase [id]/[authorUsername]/[authorDisplayName]/
 * [content]/[createdAt], but `@SerialName` snake_case for [cityName]/[likedByViewer]/[replyCount].
 * Decoding those three as bare camelCase would silently bind `cityName=""`/`likedByViewer=false`/
 * `replyCount=0` on the real wire (the timeline-DTO mixed-case footgun). No author UUID / coordinate
 * is declared (the by-id projection carries none — no-PII; `distanceM` is omitted server-side and the
 * caller maps it to `null`).
 */
@Serializable
data class SinglePostFullDto(
    val id: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val createdAt: String,
    @SerialName("city_name") val cityName: String,
    @SerialName("liked_by_viewer") val likedByViewer: Boolean,
    @SerialName("reply_count") val replyCount: Int,
)

/**
 * Low-level result of the FULL-projection `GET /api/v1/posts/{post_id}` read. `200` → the parsed
 * [SinglePostFullDto]; any non-200 (incl. the endpoint's single direction-less `404 post_not_found`)
 * or transport failure → the graceful [Unavailable] (the caller surfaces a non-blocking
 * "tidak tersedia" affordance, no navigation). `401` is owned by the `Auth` plugin.
 */
sealed interface SinglePostFullResult {
    data class Success(val post: SinglePostFullDto) : SinglePostFullResult

    data object Unavailable : SinglePostFullResult
}

/**
 * Thin wrapper over the shared [HttpClient] for `GET /api/v1/posts/{post_id}` (the `single-post-read`
 * capability), used only to freshen post-detail's content + read `editedAt`. The Bearer header + 401 are
 * owned by the shipped `Auth` plugin. This client never `println`s/logs (the projection is no-PII anyway).
 */
class SinglePostApiClient(
    private val client: HttpClient,
) {
    suspend fun fetchPost(postId: String): SinglePostApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/posts/$postId")
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                return SinglePostApiResult.Unavailable
            }
        if (response.status != HttpStatusCode.OK) return SinglePostApiResult.Unavailable
        return try {
            SinglePostApiResult.Success(response.body<SinglePostDto>())
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            SinglePostApiResult.Unavailable
        }
    }

    /**
     * FULL-projection read for a no-card consumer (notification deep-link into a post). `200` → the parsed
     * [SinglePostFullDto]; any non-200 / transport failure → [SinglePostFullResult.Unavailable].
     * `CancellationException` is rethrown (a superseded in-flight resolution must cancel cleanly, never map
     * to a failure). Never `println`s/logs (the projection is no-PII anyway).
     */
    suspend fun fetchFullPost(postId: String): SinglePostFullResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/posts/$postId")
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                return SinglePostFullResult.Unavailable
            }
        if (response.status != HttpStatusCode.OK) return SinglePostFullResult.Unavailable
        return try {
            SinglePostFullResult.Success(response.body<SinglePostFullDto>())
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            SinglePostFullResult.Unavailable
        }
    }
}
