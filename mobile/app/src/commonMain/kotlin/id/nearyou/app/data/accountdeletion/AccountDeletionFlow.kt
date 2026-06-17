package id.nearyou.app.data.accountdeletion

/**
 * The account-deletion orchestration contract consumed by `SettingsScreen` / `SettingsAccountDeletionViewModel`.
 * The production binding is [AccountDeletionRepository]; commonTest substitutes a `FakeAccountDeletionFlow`
 * so screen tests can drive a specific outcome / assert (non-)invocation without a backend (mirrors
 * `BlockedUsersFlow` / `ConsentFlow`).
 */
interface AccountDeletionFlow {
    /** `POST /api/v1/account/deletion-request` → the status-driven [AccountDeletionOutcome]. */
    suspend fun requestDeletion(): AccountDeletionOutcome

    /** `GET /api/v1/account/deletion-request` → the viewer's [DeletionStatusOutcome] (drives the banner). */
    suspend fun fetchStatus(): DeletionStatusOutcome

    /** `DELETE /api/v1/account/deletion-request` (restore) → the status-driven [DeletionCancelOutcome]. */
    suspend fun cancelDeletion(): DeletionCancelOutcome
}

/**
 * Maps the low-level [AccountDeletionApiClient] results onto exactly one [AccountDeletionOutcome] /
 * [DeletionStatusOutcome] / [DeletionCancelOutcome], keyed on the HTTP status / transport type — no
 * generic fallthrough (mirrors `BlockedUsersRepository` / `ConsentRepository`).
 *
 * The optional [diagnosticLog] is a non-user-facing sink. It MUST NOT carry the bearer token, the JWT
 * `sub`, or a request/response body — only a status / transport-cause-type envelope (PII discipline,
 * `mobile-settings` § account-deletion seam). In particular the diagnostic NEVER echoes
 * `scheduledHardDeleteAt` or `cause.message` (a transport message can embed the request URL).
 */
class AccountDeletionRepository(
    private val apiClient: AccountDeletionApiClient,
    private val diagnosticLog: (String) -> Unit = {},
) : AccountDeletionFlow {
    override suspend fun requestDeletion(): AccountDeletionOutcome =
        when (val result = apiClient.requestDeletion()) {
            is DeletionRequestApiResult.Success ->
                AccountDeletionOutcome.Scheduled(scheduledHardDeleteAt = result.body.scheduledHardDeleteAt)
            is DeletionRequestApiResult.NetworkError -> {
                diagnosticLog("account_deletion_network_error: ${result.cause::class.simpleName}")
                AccountDeletionOutcome.RetryableError
            }
            is DeletionRequestApiResult.HttpError ->
                when (result.status) {
                    401 -> AccountDeletionOutcome.Terminal401
                    else -> {
                        diagnosticLog("account_deletion_http_error: status=${result.status}")
                        AccountDeletionOutcome.RetryableError
                    }
                }
        }

    override suspend fun fetchStatus(): DeletionStatusOutcome =
        when (val result = apiClient.fetchStatus()) {
            is DeletionStatusApiResult.Success ->
                if (result.body.pending && result.body.scheduledHardDeleteAt != null) {
                    DeletionStatusOutcome.Pending(scheduledHardDeleteAt = result.body.scheduledHardDeleteAt)
                } else {
                    // pending=false, OR a malformed pending row with no date → treat as not pending
                    // (the banner needs a restore-by date to render; absent one, do not show it).
                    DeletionStatusOutcome.NotPending
                }
            is DeletionStatusApiResult.NetworkError -> {
                diagnosticLog("account_deletion_status_network_error: ${result.cause::class.simpleName}")
                DeletionStatusOutcome.Unavailable
            }
            is DeletionStatusApiResult.HttpError ->
                when (result.status) {
                    401 -> DeletionStatusOutcome.Terminal401
                    else -> {
                        diagnosticLog("account_deletion_status_http_error: status=${result.status}")
                        DeletionStatusOutcome.Unavailable
                    }
                }
        }

    override suspend fun cancelDeletion(): DeletionCancelOutcome =
        when (val result = apiClient.cancelDeletion()) {
            is DeletionCancelApiResult.Success -> DeletionCancelOutcome.Success
            is DeletionCancelApiResult.NetworkError -> {
                diagnosticLog("account_deletion_cancel_network_error: ${result.cause::class.simpleName}")
                DeletionCancelOutcome.RetryableError
            }
            is DeletionCancelApiResult.HttpError ->
                when (result.status) {
                    401 -> DeletionCancelOutcome.Terminal401
                    else -> {
                        diagnosticLog("account_deletion_cancel_http_error: status=${result.status}")
                        DeletionCancelOutcome.RetryableError
                    }
                }
        }
}
