package id.nearyou.app.timeline

/**
 * Orchestrates a Global first-page fetch: call `GET /api/v1/timeline/global` (per-process
 * [SessionIdProvider] header, **NO spatial params** — Global has no spatial filter) → map the HTTP
 * **status** to exactly one [GlobalTimelineOutcome] (design D4). There is no generic "load failed"
 * fallthrough; `401` is delegated to the shipped `Auth` plugin (this repository MUST NOT reimplement
 * token refresh / re-route). Unlike the Nearby repository, Global needs **no** `LocationProvider`
 * (there is no coordinate to acquire).
 */
class GlobalTimelineRepository(
    private val apiClient: GlobalTimelineApiClient,
    private val sessionIdProvider: SessionIdProvider,
    // Diagnostic sink for non-user-facing error detail. Wired to Sentry / OTel when that lands;
    // no-op for now. MUST NOT carry tokens or coordinates (none are passed here).
    private val diagnosticLog: (String) -> Unit = {},
) : GlobalTimelineFlow {
    override suspend fun loadFirstPage(): GlobalTimelineOutcome {
        val result =
            apiClient.fetchGlobal(
                sessionId = sessionIdProvider.sessionId,
                cursor = null,
            )
        return when (result) {
            is GlobalApiResult.Success ->
                GlobalTimelineOutcome.Loaded(
                    posts = result.body.posts,
                    nextCursor = result.body.nextCursor,
                    upsell = result.body.upsell,
                )
            is GlobalApiResult.NetworkError -> {
                diagnosticLog("global_network_error: ${result.cause.message}")
                GlobalTimelineOutcome.NetworkError
            }
            is GlobalApiResult.HttpError ->
                when {
                    // 400 invalid_cursor — not expected from the always-valid first page; surface as
                    // retryable WITH a logged diagnostic (NOT a silent no-op, NOT a crash).
                    result.status == 400 -> {
                        diagnosticLog("global_invalid_request: status=400")
                        GlobalTimelineOutcome.Error
                    }
                    result.status in 500..599 -> GlobalTimelineOutcome.NetworkError
                    // 401 is handled upstream by the shipped Auth plugin + SessionInvalidator
                    // (terminal 401 → store cleared → RootRouterScreen re-routes to SignInScreen).
                    // Any other unenumerated status maps to the DEFINED retryable NetworkError state
                    // rather than a generic "load failed" fallthrough (mirrors NearbyRepository).
                    else -> GlobalTimelineOutcome.NetworkError
                }
        }
    }
}
