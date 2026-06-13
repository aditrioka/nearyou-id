package id.nearyou.app.data.block

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * One row of the viewer's outbound block list, mirroring the **shipped** backend serialization in
 * `backend/ktor/src/main/kotlin/id/nearyou/app/block/BlockRoutes.kt` (`BlockListItem`) — bare
 * camelCase keys, NO `@SerialName` (the shipped wire; verified field-for-field against
 * `BlockRoutes.kt:34-44` — `social-list-profile-summaries` register).
 *
 * [userId] is the blocked user's UUID. It is the path parameter for the unblock `DELETE` ONLY — it
 * MUST NOT be rendered or logged (PII discipline, `mobile-settings` § "Block-list management"). The
 * displayed identity is [displayName] + the @-handle of [username].
 */
@Serializable
data class BlockListItem(
    val userId: String,
    val username: String,
    val displayName: String,
    val isPremium: Boolean,
    val createdAt: String,
)

/**
 * The block-list response envelope, mirroring the shipped `BlockListResponse`: `blocks` + a bare
 * camelCase `nextCursor` (the backend emits camelCase). `nextCursor` tolerates absence/null (the
 * shared `Json` sets `ignoreUnknownKeys` + `explicitNulls = false`).
 */
@Serializable
data class BlockListResponse(
    val blocks: List<BlockListItem>,
    val nextCursor: String? = null,
)

/**
 * Low-level result of the block-list `GET`, mirroring `NearbyApiResult`: a parsed body on `200`, the
 * HTTP [status] on any non-2xx (the repository keys outcome on [status], never on a parsed
 * `error.code`), or a transport failure. Non-2xx is a value, NEVER a thrown exception.
 */
sealed interface BlocksApiResult {
    data class Success(val body: BlockListResponse) : BlocksApiResult

    data class HttpError(val status: Int) : BlocksApiResult

    data class NetworkError(val cause: Throwable) : BlocksApiResult
}

/** Low-level result of the unblock `DELETE` — success on any 2xx (the backend returns `204`). */
sealed interface UnblockApiResult {
    data object Success : UnblockApiResult

    data class HttpError(val status: Int) : UnblockApiResult

    data class NetworkError(val cause: Throwable) : UnblockApiResult
}

/**
 * Thin wrapper over the shared (bearer-authed) [HttpClient] for the block-list endpoints
 * (`GET /api/v1/blocks`, `DELETE /api/v1/blocks/{user_id}` — `user-blocking` capability). The Bearer
 * `Authorization` header is attached by the shipped `Auth` plugin — this client MUST NOT reimplement
 * token attachment or 401 refresh, and never logs a blocked user's `userId`/`username`/`displayName`
 * (PII discipline).
 */
class BlockedUsersApiClient(
    private val client: HttpClient,
) {
    suspend fun fetchBlocks(cursor: String? = null): BlocksApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/blocks") {
                    cursor?.let { parameter("cursor", it) }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return BlocksApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                BlocksApiResult.Success(response.body<BlockListResponse>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 200 whose body fails to parse is a transport/contract failure, not a Loaded.
                BlocksApiResult.NetworkError(cause)
            }
        }

        return BlocksApiResult.HttpError(status = response.status.value)
    }

    suspend fun unblock(userId: String): UnblockApiResult {
        val response: HttpResponse =
            try {
                // userId is a server-issued UUID; path-encode defensively (never interpolate raw).
                client.delete("/api/v1/blocks/${userId.encodeURLPathPart()}")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return UnblockApiResult.NetworkError(cause)
            }

        return if (response.status.isSuccess()) {
            UnblockApiResult.Success
        } else {
            UnblockApiResult.HttpError(status = response.status.value)
        }
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
