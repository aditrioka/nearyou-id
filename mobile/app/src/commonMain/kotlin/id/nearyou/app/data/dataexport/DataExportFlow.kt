package id.nearyou.app.data.dataexport

/**
 * The data-export orchestration contract consumed by `SettingsScreen` / `SettingsDataExportViewModel`.
 * The production binding is [DataExportRepository]; commonTest substitutes a `FakeDataExportFlow` so screen
 * / ViewModel tests can drive a specific outcome / assert (non-)invocation without a backend (mirrors
 * `AccountDeletionFlow`).
 */
interface DataExportFlow {
    /** `POST /api/v1/account/export` → the status-driven [DataExportRequestOutcome]. */
    suspend fun requestExport(): DataExportRequestOutcome

    /** `GET /api/v1/account/export` → the caller's [DataExportStatusOutcome] (drives the row + banner). */
    suspend fun fetchStatus(): DataExportStatusOutcome
}

/**
 * Maps the low-level [DataExportApiClient] results onto exactly one [DataExportRequestOutcome] /
 * [DataExportStatusOutcome], keyed on the HTTP status / transport type — exhaustive over the shipped
 * status vocabulary with no generic fallthrough (mirrors `AccountDeletionRepository`).
 *
 * The optional [diagnosticLog] is a non-user-facing sink. It MUST NOT carry the bearer token, the JWT
 * `sub`, the `downloadUrl`, or a request/response body — only a status / transport-cause-type envelope
 * (PII discipline, `mobile-settings` § data-export seam). In particular the diagnostic NEVER echoes the
 * `downloadUrl`, `downloadExpiresAt`, or `cause.message` (a transport message can embed the request URL).
 */
class DataExportRepository(
    private val apiClient: DataExportApiClient,
    private val diagnosticLog: (String) -> Unit = {},
) : DataExportFlow {
    override suspend fun requestExport(): DataExportRequestOutcome =
        when (val result = apiClient.requestExport()) {
            is DataExportRequestApiResult.Success -> DataExportRequestOutcome.Requested
            is DataExportRequestApiResult.NetworkError -> {
                diagnosticLog("data_export_network_error: ${result.cause::class.simpleName}")
                DataExportRequestOutcome.RetryableError
            }
            is DataExportRequestApiResult.HttpError ->
                when (result.status) {
                    401 -> DataExportRequestOutcome.Terminal401
                    else -> {
                        diagnosticLog("data_export_http_error: status=${result.status}")
                        DataExportRequestOutcome.RetryableError
                    }
                }
        }

    override suspend fun fetchStatus(): DataExportStatusOutcome =
        when (val result = apiClient.fetchStatus()) {
            is DataExportStatusApiResult.Success -> projectStatus(result.body)
            is DataExportStatusApiResult.NetworkError -> {
                diagnosticLog("data_export_status_network_error: ${result.cause::class.simpleName}")
                DataExportStatusOutcome.Unavailable
            }
            is DataExportStatusApiResult.HttpError ->
                when (result.status) {
                    401 -> DataExportStatusOutcome.Terminal401
                    else -> {
                        diagnosticLog("data_export_status_http_error: status=${result.status}")
                        DataExportStatusOutcome.Unavailable
                    }
                }
        }

    /**
     * Projects the `GET` body onto the exhaustive [DataExportStatusOutcome] over the shipped vocabulary
     * (`none`/`pending`/`processing`/`ready`/`expired`/`failed`). A `ready` row missing its URL/deadline,
     * or any unknown future status, degrades to [DataExportStatusOutcome.Unavailable] (defensive — the
     * banner cannot render without a URL+deadline; this is not a fallthrough over the shipped vocabulary,
     * each of which is handled explicitly).
     */
    private fun projectStatus(body: DataExportStatusResponse): DataExportStatusOutcome =
        when (body.status) {
            "none" -> DataExportStatusOutcome.None
            "pending", "processing" -> DataExportStatusOutcome.InProgress
            "ready" ->
                if (body.downloadExpiresAt != null && body.downloadUrl != null) {
                    DataExportStatusOutcome.Ready(
                        downloadExpiresAt = body.downloadExpiresAt,
                        downloadUrl = body.downloadUrl,
                    )
                } else {
                    DataExportStatusOutcome.Unavailable
                }
            "expired" -> DataExportStatusOutcome.Expired
            "failed" -> DataExportStatusOutcome.Failed
            else -> DataExportStatusOutcome.Unavailable
        }
}
