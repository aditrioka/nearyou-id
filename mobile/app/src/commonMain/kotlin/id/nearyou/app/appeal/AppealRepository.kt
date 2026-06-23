package id.nearyou.app.appeal

/**
 * Maps each appeal HTTP **status** to exactly one [AppealSubmitOutcome] / [AppealStatusOutcome] (design
 * D4; the `UsernameRepository` precedent). The nested `error.code` is read ONLY to split the two
 * same-status `409`s (appeal_already_pending vs no_actionable_moderation). No generic "load failed"
 * fallthrough; a `401` is surfaced as [AppealSubmitOutcome.SessionExpired] for the ViewModel to re-route
 * to sign-in (the appeal token is NOT auto-refreshed — it is a one-shot sign-in-issued credential).
 */
class AppealRepository(
    private val apiClient: AppealApiClient,
    // Diagnostic sink for non-user-facing error detail (no-op default for tests). MUST NOT carry tokens.
    private val diagnosticLog: (String) -> Unit = {},
) : AppealFlow {
    override suspend fun submit(
        appealText: String,
        appealToken: String,
    ): AppealSubmitOutcome =
        when (val result = apiClient.submit(appealText, appealToken)) {
            is AppealSubmitApiResult.Success -> AppealSubmitOutcome.Submitted(result.body.id)
            is AppealSubmitApiResult.NetworkError -> {
                diagnosticLog("appeal_submit_network_error: ${result.cause::class.simpleName}")
                AppealSubmitOutcome.TransportError
            }
            is AppealSubmitApiResult.HttpError ->
                when (result.status) {
                    401 -> AppealSubmitOutcome.SessionExpired
                    400 -> AppealSubmitOutcome.InvalidText
                    409 ->
                        // Split on the nested error code: appeal_already_pending vs no_actionable_moderation.
                        // An unknown 409 code defaults to NotEligible (the safe "nothing to appeal" branch).
                        if (result.errorCode == "appeal_already_pending") {
                            AppealSubmitOutcome.AlreadyPending
                        } else {
                            AppealSubmitOutcome.NotEligible
                        }
                    429 -> AppealSubmitOutcome.RateLimited(result.retryAfterSeconds ?: 0L)
                    in 500..599 -> AppealSubmitOutcome.TransportError
                    // Any other unenumerated non-2xx → the DEFINED retryable TransportError fallback.
                    else -> AppealSubmitOutcome.TransportError
                }
        }

    override suspend fun status(appealToken: String): AppealStatusOutcome =
        when (val result = apiClient.status(appealToken)) {
            is AppealStatusApiResult.Success -> {
                val body = result.body
                when {
                    !body.hasAppeal || body.status == null || body.actionType == null -> AppealStatusOutcome.None
                    body.status == "pending" ->
                        AppealStatusOutcome.Pending(actionType = body.actionType, createdAt = body.createdAt)
                    else ->
                        AppealStatusOutcome.Decided(
                            approved = body.status == "approved",
                            actionType = body.actionType,
                            decisionReason = body.decisionReason,
                            reviewedAt = body.reviewedAt,
                        )
                }
            }
            is AppealStatusApiResult.NetworkError -> {
                diagnosticLog("appeal_status_network_error: ${result.cause::class.simpleName}")
                AppealStatusOutcome.TransportError
            }
            is AppealStatusApiResult.HttpError ->
                when (result.status) {
                    401 -> AppealStatusOutcome.SessionExpired
                    else -> AppealStatusOutcome.TransportError
                }
        }
}
