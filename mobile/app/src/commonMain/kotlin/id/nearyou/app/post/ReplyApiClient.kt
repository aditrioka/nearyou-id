package id.nearyou.app.post

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * `POST /api/v1/posts/{post_id}/replies` request body. Declares `content` as **non-null**, deliberately
 * TIGHTENING the shipped backend `ReplyCreateRequest(content: String? = null)` — the client always sends
 * a non-null `content` (the wire's nullable+default is backend input-leniency, irrelevant to an
 * outbound-only request DTO). The server NFKC-normalizes + trims + length-guards (1..280 code points)
 * authoritatively; the client's pre-submit code-point gate is only a UX nicety (design D9).
 */
@Serializable
data class ReplyCreateRequest(val content: String)

/**
 * One reply, mirroring the SHIPPED backend serialization in
 * `backend/ktor/.../engagement/ReplyRoutes.kt` (`ReplyDto`) — **snake_case**, generated from the wire,
 * NOT from any stale spec JSON example (the PR #128 timeline casing-drift lesson). `id` + `content` are
 * bare; the rest carry `@SerialName` snake_case. Two wire-faithful-but-near-dead fields are parsed yet
 * NOT surfaced in v1:
 *  - [isAutoHidden] is `true` ONLY for the viewer's OWN reply on this list path (the backend's
 *    author-bypass `is_auto_hidden = FALSE OR author_id = :viewer` in
 *    `core/data/.../repository/PostReplyRepository.kt` `listByPost`), so a returned auto-hidden reply renders identically to a
 *    live one — no "under review" badge or dimming (spec § "Viewer's own auto-hidden reply…").
 *  - [deletedAt] is effectively dead on this list path (the backend excludes `deleted_at IS NOT NULL`
 *    rows); the DTO mirrors the wire faithfully but the field gets no v1 rendering.
 * [authorId] is a UUID (PII) and is NEVER rendered (consistent with the author-free post cards).
 */
@Serializable
data class ReplyDto(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    val content: String,
    @SerialName("is_auto_hidden") val isAutoHidden: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String?,
    @SerialName("deleted_at") val deletedAt: String?,
)

/**
 * The replies-list envelope, mirroring the shipped `ReplyListResponse`: a bare `replies` list and a
 * `@SerialName("next_cursor")` cursor. **Casing trap (design D4):** `next_cursor` is **snake_case** here
 * — UNLIKE the timelines' bare camelCase `nextCursor` (`NearbyResponseDto` / `GlobalResponseDto`). A
 * camelCase `nextCursor` body MUST NOT populate [nextCursor] (a commonTest negative-guard asserts this).
 * [nextCursor] is parsed + retained but cursor load-more is NOT wired in this change (deferred — extends
 * the `mobile-nearby-timeline-infinite-scroll` follow-up).
 */
@Serializable
data class ReplyListResponse(
    val replies: List<ReplyDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * Low-level result of a reply POST. `201` → [Created] (the parsed [ReplyDto]); any non-201 → [HttpError]
 * carrying status + best-effort `error.code` + parsed `Retry-After` seconds (429 only); transport →
 * [NetworkError]. A non-2xx is a VALUE, never a thrown exception.
 */
sealed interface ReplyPostApiResult {
    data class Created(val reply: ReplyDto) : ReplyPostApiResult

    /** Non-201. [errorCode] is the parsed `{ "error": { "code" } }` (e.g. `invalid_request` for empty /
     *  over-limit content), null on an empty/non-JSON body; [retryAfterSeconds] is set on a 429. */
    data class HttpError(val status: Int, val errorCode: String?, val retryAfterSeconds: Long?) : ReplyPostApiResult

    data class NetworkError(val cause: Throwable) : ReplyPostApiResult
}

/** Low-level result of a replies-list fetch. `200` → [Success]; non-200 → [HttpError]; transport →
 *  [NetworkError]. The repository maps any non-Success to the retryable error state. */
sealed interface ReplyListApiResult {
    data class Success(val body: ReplyListResponse) : ReplyListApiResult

    data class HttpError(val status: Int) : ReplyListApiResult

    data class NetworkError(val cause: Throwable) : ReplyListApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for the post-scoped reply sub-resources
 * (`POST` / `GET /api/v1/posts/{post_id}/replies`). Bearer + 401 refresh owned by the shipped `Auth`
 * plugin (MUST NOT be reimplemented). NO `X-Session-Id` header (the reply endpoints are not
 * session-soft-capped). MUST NOT `println`/log bodies or coordinates, MUST NOT widen the log level.
 */
class ReplyApiClient(
    private val client: HttpClient,
) {
    suspend fun postReply(
        postId: String,
        content: String,
    ): ReplyPostApiResult {
        val response: HttpResponse =
            try {
                client.post("/api/v1/posts/$postId/replies") {
                    contentType(ContentType.Application.Json)
                    setBody(ReplyCreateRequest(content = content))
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return ReplyPostApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.Created) {
            return try {
                ReplyPostApiResult.Created(response.body<ReplyDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 201 whose body fails to parse is a transport/contract failure, not a Created.
                ReplyPostApiResult.NetworkError(cause)
            }
        }
        val errorCode =
            try {
                response.body<PostErrorBody>().error.code
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                null
            }
        return ReplyPostApiResult.HttpError(
            status = response.status.value,
            errorCode = errorCode,
            retryAfterSeconds = response.retryAfterSeconds(),
        )
    }

    suspend fun listReplies(postId: String): ReplyListApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/posts/$postId/replies")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return ReplyListApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.OK) {
            return try {
                ReplyListApiResult.Success(response.body<ReplyListResponse>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                ReplyListApiResult.NetworkError(cause)
            }
        }
        return ReplyListApiResult.HttpError(status = response.status.value)
    }
}
