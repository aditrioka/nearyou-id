package id.nearyou.app.data.dataexport

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * `POST /api/v1/account/export` `202` body — the enqueued (or existing single-active) request id + its
 * status. Bare camelCase to match the shipped `account-data-export` route wire (the shared `Json` sets
 * `ignoreUnknownKeys` + `explicitNulls = false`, so an extra/absent key is tolerated). The request body
 * is empty — the server derives the subject from the bearer token.
 */
@Serializable
data class DataExportRequestResponse(
    val requestId: String,
    val status: String,
)

/**
 * `GET /api/v1/account/export` `200` body — the caller's latest export status, with the download deadline
 * + a freshly-presigned URL present only when `status = ready`. [status] ∈
 * `none | pending | processing | ready | expired | failed`. [downloadExpiresAt]/[downloadUrl] are
 * nullable-with-default so an in-progress/absent request (which omits them) decodes cleanly under the
 * shared `ignoreUnknownKeys` + `explicitNulls = false` `Json`.
 */
@Serializable
data class DataExportStatusResponse(
    val status: String,
    val downloadExpiresAt: String? = null,
    val downloadUrl: String? = null,
)

/**
 * Low-level result of the export `POST`, mirroring `DeletionRequestApiResult`: a parsed body on any 2xx
 * (the route returns `202 Accepted`), the HTTP [status] on any non-2xx (the repository keys outcome on
 * [status], never on a parsed `error.code`), or a transport failure. Non-2xx is a value, NEVER a thrown
 * exception.
 */
sealed interface DataExportRequestApiResult {
    data class Success(val body: DataExportRequestResponse) : DataExportRequestApiResult

    data class HttpError(val status: Int) : DataExportRequestApiResult

    data class NetworkError(val cause: Throwable) : DataExportRequestApiResult
}

/** Low-level result of the status `GET` — a parsed body on `200`, else [HttpError] / [NetworkError]. */
sealed interface DataExportStatusApiResult {
    data class Success(val body: DataExportStatusResponse) : DataExportStatusApiResult

    data class HttpError(val status: Int) : DataExportStatusApiResult

    data class NetworkError(val cause: Throwable) : DataExportStatusApiResult
}

/**
 * Thin wrapper over the shared (bearer-authed) [HttpClient] for the data-export endpoints
 * (`POST` / `GET /api/v1/account/export` — the `account-data-export` capability). The Bearer
 * `Authorization` header is attached by the shipped `Auth` plugin — this client MUST NOT reimplement
 * token attachment or 401 refresh, and never logs the bearer token, the `Authorization` header, the
 * JWT `sub`, the `downloadUrl`, or any request/response body (PII discipline, `mobile-settings`
 * § data-export seam).
 */
class DataExportApiClient(
    private val client: HttpClient,
) {
    /** `POST /api/v1/account/export` — enqueue (or return the single-active) export; no request body. */
    suspend fun requestExport(): DataExportRequestApiResult {
        val response: HttpResponse =
            try {
                client.post("/api/v1/account/export")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return DataExportRequestApiResult.NetworkError(cause)
            }

        // The route returns 202 Accepted (not 200) — accept any 2xx, then parse the body.
        if (response.status.value in 200..299) {
            return try {
                DataExportRequestApiResult.Success(response.body<DataExportRequestResponse>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 2xx whose body fails to parse is a transport/contract failure, not a Success.
                DataExportRequestApiResult.NetworkError(cause)
            }
        }

        return DataExportRequestApiResult.HttpError(status = response.status.value)
    }

    /** `GET /api/v1/account/export` — the caller's latest export status (drives the row + ready banner). */
    suspend fun fetchStatus(): DataExportStatusApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/account/export")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return DataExportStatusApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                DataExportStatusApiResult.Success(response.body<DataExportStatusResponse>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                DataExportStatusApiResult.NetworkError(cause)
            }
        }

        return DataExportStatusApiResult.HttpError(status = response.status.value)
    }
}
