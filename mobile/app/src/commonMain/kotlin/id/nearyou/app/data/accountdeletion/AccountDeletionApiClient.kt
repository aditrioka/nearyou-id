package id.nearyou.app.data.accountdeletion

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * `POST /api/v1/account/deletion-request` response — the scheduled hard-delete instant the backend
 * computes (`NOW() + INTERVAL '30 days'`). Bare camelCase to match the shipped `account` route wire
 * (the shared `Json` sets `ignoreUnknownKeys` + `explicitNulls = false`, so an extra/absent key is
 * tolerated). The request body is empty — the server derives `source='user'` from the bearer subject.
 */
@Serializable
data class DeletionRequestResponse(
    val scheduledHardDeleteAt: String,
)

/**
 * `GET /api/v1/account/deletion-request` response — the viewer's pending-deletion state. [pending] is
 * `false` when no in-grace row exists; when `true`, [scheduledHardDeleteAt] carries the restore-by
 * instant. Both fields are nullable-with-default so an absent key (e.g. a body that omits
 * `scheduledHardDeleteAt` when not pending) decodes cleanly under the shared `ignoreUnknownKeys` +
 * `explicitNulls = false` `Json`.
 */
@Serializable
data class DeletionStatusResponse(
    val pending: Boolean = false,
    val scheduledHardDeleteAt: String? = null,
)

/**
 * Low-level result of the deletion `POST` / status `GET`, mirroring `BlocksApiResult`: a parsed body on
 * `200`, the HTTP [status] on any non-2xx (the repository keys outcome on [status], never on a parsed
 * `error.code`), or a transport failure. Non-2xx is a value, NEVER a thrown exception.
 */
sealed interface DeletionRequestApiResult {
    data class Success(val body: DeletionRequestResponse) : DeletionRequestApiResult

    data class HttpError(val status: Int) : DeletionRequestApiResult

    data class NetworkError(val cause: Throwable) : DeletionRequestApiResult
}

/** Low-level result of the status `GET` — a parsed body on `200`, else [HttpError] / [NetworkError]. */
sealed interface DeletionStatusApiResult {
    data class Success(val body: DeletionStatusResponse) : DeletionStatusApiResult

    data class HttpError(val status: Int) : DeletionStatusApiResult

    data class NetworkError(val cause: Throwable) : DeletionStatusApiResult
}

/** Low-level result of the cancel/restore `DELETE` — success on any 2xx (the backend returns `200`/`204`). */
sealed interface DeletionCancelApiResult {
    data object Success : DeletionCancelApiResult

    data class HttpError(val status: Int) : DeletionCancelApiResult

    data class NetworkError(val cause: Throwable) : DeletionCancelApiResult
}

/**
 * Thin wrapper over the shared (bearer-authed) [HttpClient] for the account-deletion endpoints
 * (`POST` / `DELETE` / `GET /api/v1/account/deletion-request` — the `account` capability). The Bearer
 * `Authorization` header is attached by the shipped `Auth` plugin — this client MUST NOT reimplement
 * token attachment or 401 refresh, and never logs the bearer token, the `Authorization` header, the
 * JWT `sub`, or any request/response body (PII discipline, `mobile-settings` § account-deletion seam).
 */
class AccountDeletionApiClient(
    private val client: HttpClient,
) {
    /** `POST /api/v1/account/deletion-request` — schedule the deletion; the request carries no body. */
    suspend fun requestDeletion(): DeletionRequestApiResult {
        val response: HttpResponse =
            try {
                client.post("/api/v1/account/deletion-request")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return DeletionRequestApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                DeletionRequestApiResult.Success(response.body<DeletionRequestResponse>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 200 whose body fails to parse is a transport/contract failure, not a Success.
                DeletionRequestApiResult.NetworkError(cause)
            }
        }

        return DeletionRequestApiResult.HttpError(status = response.status.value)
    }

    /** `DELETE /api/v1/account/deletion-request` — cancel/restore within the grace window. */
    suspend fun cancelDeletion(): DeletionCancelApiResult {
        val response: HttpResponse =
            try {
                client.delete("/api/v1/account/deletion-request")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return DeletionCancelApiResult.NetworkError(cause)
            }

        return if (response.status.value in 200..299) {
            DeletionCancelApiResult.Success
        } else {
            DeletionCancelApiResult.HttpError(status = response.status.value)
        }
    }

    /** `GET /api/v1/account/deletion-request` — the viewer's pending-deletion state (drives the banner). */
    suspend fun fetchStatus(): DeletionStatusApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/account/deletion-request")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return DeletionStatusApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                DeletionStatusApiResult.Success(response.body<DeletionStatusResponse>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                DeletionStatusApiResult.NetworkError(cause)
            }
        }

        return DeletionStatusApiResult.HttpError(status = response.status.value)
    }
}
