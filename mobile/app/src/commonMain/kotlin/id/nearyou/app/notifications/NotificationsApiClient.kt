package id.nearyou.app.notifications

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * One in-app notification, mirroring the **shipped** backend serialization in
 * `backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationRoutes.kt` (`NotificationDto`) —
 * which is **mixed-case, NOT uniformly snake_case** (design D2), and which the stale `in-app-notifications`
 * spec prose does NOT accurately describe. The field names are regenerated from the shipped DTO:
 *  - bare camelCase (no `@SerialName`): `id`, `type`;
 *  - `@SerialName` snake_case: `actor_user_id`, `target_type`, `target_id`, `body_data`, `created_at`,
 *    `read_at`.
 *
 * [actorUserId] is a UUID and MUST NOT be rendered (PII discipline, design D4); [targetId] likewise.
 * [bodyData] is **non-null** (the backend defaults absent/invalid to `{}`); it defaults to an empty JSON
 * object here so an absent key tolerates as `{}` rather than failing the decode.
 */
@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    @SerialName("actor_user_id") val actorUserId: String? = null,
    @SerialName("target_type") val targetType: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("body_data") val bodyData: JsonElement = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
)

/**
 * The notifications list envelope, mirroring the shipped `NotificationListResponse`: `items` + an opaque
 * `@SerialName("next_cursor")` base64url token (NOT an ISO timestamp — design D2). `next_cursor` tolerates
 * absence/null (the shared `Json` sets `ignoreUnknownKeys` + `explicitNulls = false`). The cursor is
 * retained on [NotificationsOutcome.Loaded] but NOT consumed for load-more (infinite scroll deferred).
 */
@Serializable
data class NotificationListResponse(
    val items: List<NotificationDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * The unread-count body, mirroring the shipped `UnreadCountResponse`: `{ "count": <Long> }` — NOT the
 * stale spec's `unread_count`. The default `0` makes the stale-key shape parse to an absent count (the
 * negative-regression guard), and tolerates a malformed body without throwing.
 */
@Serializable
data class UnreadCountDto(val count: Long = 0)

/**
 * The read-all body, mirroring the shipped `ReadAllResponse`: `{ "marked_read": <Int> }` — NOT the stale
 * spec's `marked`. Default `0` for the same negative-regression + tolerance reasons as [UnreadCountDto].
 */
@Serializable
data class ReadAllResponseDto(
    @SerialName("marked_read") val markedRead: Int = 0,
)

/**
 * Low-level result of a notifications list fetch, mirroring [id.nearyou.app.timeline.GlobalApiResult]: a
 * parsed body on `200`, the HTTP [status] on any non-2xx (the repository keys outcome on [status], never
 * on a parsed `error.code`), or a transport failure. Non-2xx is a value, NEVER a thrown exception.
 */
sealed interface NotificationsApiResult {
    data class Success(val body: NotificationListResponse) : NotificationsApiResult

    /** Non-2xx response. The repository keys outcome on [status] (400 → retryable Error, 5xx →
     *  NetworkError, 401 is already handled by the shipped `Auth` plugin upstream). */
    data class HttpError(val status: Int) : NotificationsApiResult

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data class NetworkError(val cause: Throwable) : NotificationsApiResult
}

/** Result of the unread-count fetch (badge source). On any non-200 / transport failure → [Failure]
 *  (the badge simply shows nothing — the count is advisory, one-shot, never blocking). */
sealed interface UnreadCountResult {
    data class Success(val count: Long) : UnreadCountResult

    data object Failure : UnreadCountResult
}

/**
 * Result of `PATCH /{id}/read`. The shipped wire is `204` (acknowledged) / `404 not_found` (already-read,
 * not-owned, or non-existent — an idempotent no-op) / anything else (5xx, transport, `400 invalid_uuid`).
 * The caller keeps the optimistic read flip on [Acknowledged]/[NotFound] and reverts on [Failed].
 */
sealed interface MarkReadResult {
    /** HTTP 204 — the row is now read. */
    data object Acknowledged : MarkReadResult

    /** HTTP 404 `not_found` — already-read / not-owned / missing; treated as a no-op (row stays read). */
    data object NotFound : MarkReadResult

    /** 5xx / transport / any other status — the optimistic flip is reverted; no blocking error surfaced. */
    data object Failed : MarkReadResult
}

/** Result of `PATCH /read-all`. On the `200 { "marked_read": <Int> }` response → [Success]; any other
 *  status / transport failure → [Failed] (the optimistic mark-all is reverted). */
sealed interface MarkAllReadResult {
    data class Success(val markedRead: Int) : MarkAllReadResult

    data object Failed : MarkAllReadResult
}

/**
 * Thin wrapper over the shared [HttpClient] for the four shipped `/api/v1/notifications` routes (the
 * canonical paths per the SHIPPED `NotificationRoutes.kt`). The Bearer `Authorization` header is attached
 * by the shipped `Auth` plugin — this client MUST NOT reimplement token attachment or 401 refresh. Unlike
 * the timeline clients there is **no** `X-Session-Id` header (the notifications routes carry no per-session
 * soft-cap accounting).
 *
 * The list first page omits `cursor`; a subsequent page passes the prior response's opaque `next_cursor`
 * back **verbatim** as `cursor=` (NO decoding / reformatting / timestamp parsing). The optional `unread`
 * filter, when used, is `unread=true` (NOT `unread_only`).
 */
class NotificationsApiClient(
    private val client: HttpClient,
) {
    suspend fun fetch(
        cursor: String? = null,
        unreadOnly: Boolean = false,
    ): NotificationsApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/notifications") {
                    // First page omits cursor; a passed cursor is the opaque next_cursor sent back verbatim.
                    cursor?.let { parameter("cursor", it) }
                    if (unreadOnly) parameter("unread", "true")
                }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cause
            } catch (cause: Throwable) {
                return NotificationsApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                NotificationsApiResult.Success(response.body<NotificationListResponse>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 200 whose body fails to parse is a transport/contract failure, not a Loaded.
                NotificationsApiResult.NetworkError(cause)
            }
        }

        return NotificationsApiResult.HttpError(status = response.status.value)
    }

    suspend fun unreadCount(): UnreadCountResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/notifications/unread-count")
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                return UnreadCountResult.Failure
            }
        if (response.status != HttpStatusCode.OK) return UnreadCountResult.Failure
        return try {
            UnreadCountResult.Success(response.body<UnreadCountDto>().count)
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            UnreadCountResult.Failure
        }
    }

    suspend fun markRead(id: String): MarkReadResult {
        val response: HttpResponse =
            try {
                client.patch("/api/v1/notifications/$id/read")
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                return MarkReadResult.Failed
            }
        return when (response.status) {
            HttpStatusCode.NoContent -> MarkReadResult.Acknowledged
            HttpStatusCode.NotFound -> MarkReadResult.NotFound
            else -> MarkReadResult.Failed
        }
    }

    suspend fun markAllRead(): MarkAllReadResult {
        val response: HttpResponse =
            try {
                client.patch("/api/v1/notifications/read-all")
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                return MarkAllReadResult.Failed
            }
        if (response.status != HttpStatusCode.OK) return MarkAllReadResult.Failed
        return try {
            MarkAllReadResult.Success(response.body<ReadAllResponseDto>().markedRead)
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            MarkAllReadResult.Failed
        }
    }
}
