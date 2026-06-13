package id.nearyou.app.search

/**
 * Orchestrates a search fetch: call `GET /api/v1/search?q=&offset=` → map the HTTP **status** to exactly
 * one [SearchOutcome] (design D4). There is no generic "load failed" fallthrough; `401` is delegated to
 * the shipped `Auth` plugin (this repository MUST NOT reimplement token refresh / re-route). Mirrors the
 * `GlobalTimelineRepository` shape, with the search-specific gate / rate-limit / kill-switch statuses
 * added (403 → PremiumGate, 429 → RateLimited, 503 → Disabled).
 */
class SearchRepository(
    private val apiClient: SearchApiClient,
    // Diagnostic sink for non-user-facing error detail. Wired to the real coordinate-free DiagnosticSink
    // in Koin (no-op default for tests). MUST NOT carry tokens or the raw query.
    private val diagnosticLog: (String) -> Unit = {},
) : SearchFlow {
    override suspend fun search(
        query: String,
        offset: Int,
    ): SearchOutcome =
        when (val result = apiClient.search(query = query, offset = offset)) {
            is SearchApiResult.Success ->
                SearchOutcome.Results(
                    hits = result.body.results,
                    nextOffset = result.body.nextOffset,
                )
            is SearchApiResult.NetworkError -> {
                // Log the exception TYPE, not cause.message (coordinate-/query-safe — the class name is
                // the right diagnostic). The raw query is never logged here.
                diagnosticLog("search_network_error: ${result.cause::class.simpleName}")
                SearchOutcome.NetworkError
            }
            is SearchApiResult.HttpError ->
                when {
                    // Terminal 401 (survived the shipped Auth refresh) → SessionExpired, NOT NetworkError.
                    // Branched explicitly AHEAD of the fallback; the Auth plugin + SessionInvalidator own
                    // the re-route to SignInScreen.
                    result.status == 401 -> SearchOutcome.SessionExpired
                    // 403 premium_required — the Free-tier gate (the server's authoritative
                    // subscription_status drives this; the screen renders the upsell panel).
                    result.status == 403 -> SearchOutcome.PremiumGate
                    // 429 rate_limited — carries the Retry-After seconds (absent/unparseable → 0, the
                    // screen floors that to one minute).
                    result.status == 429 -> SearchOutcome.RateLimited(result.retryAfterSeconds ?: 0L)
                    // 503 search_disabled — the search_enabled Remote Config kill switch.
                    result.status == 503 -> SearchOutcome.Disabled
                    // 400 invalid_query_length / invalid_offset — not expected given the client-side
                    // guard; surface as retryable WITH a logged diagnostic (NOT a silent no-op, NOT a crash).
                    result.status == 400 -> {
                        diagnosticLog("search_invalid_request: status=400")
                        SearchOutcome.Error
                    }
                    result.status in 500..599 -> SearchOutcome.NetworkError
                    // Any other unenumerated non-2xx status maps to the DEFINED retryable NetworkError
                    // fallback rather than a generic "load failed" fallthrough (the match is over an Int,
                    // so a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic
                    // copy, not a `when` else). Mirrors GlobalTimelineRepository.
                    else -> SearchOutcome.NetworkError
                }
        }
}
