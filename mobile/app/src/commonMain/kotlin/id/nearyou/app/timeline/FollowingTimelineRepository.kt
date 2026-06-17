package id.nearyou.app.timeline

/**
 * Orchestrates a Following first-page fetch: call `GET /api/v1/timeline/following` (per-process
 * [SessionIdProvider] header, **NO spatial params** — Following has no spatial filter) → map the HTTP
 * **status** to exactly one [FollowingTimelineOutcome] (design D1, mirroring `GlobalTimelineRepository`).
 * There is no generic "load failed" fallthrough; `401` is delegated to the shipped `Auth` plugin (this
 * repository MUST NOT reimplement token refresh / re-route). Like Global, Following needs **no**
 * `LocationProvider` (there is no coordinate to acquire).
 */
class FollowingTimelineRepository(
    private val apiClient: FollowingTimelineApiClient,
    private val sessionIdProvider: SessionIdProvider,
    // Diagnostic sink for non-user-facing error detail. Wired to the coordinate-free DiagnosticSink in
    // MobileModule. MUST log status / exception-TYPE only — NEVER cause.message, a response-body field,
    // or a coordinate. This is load-bearing on THIS surface specifically: Following's latitude/longitude
    // arrive in the response BODY (not the URL), which LogLevel.HEADERS excludes — so the diagnostic sink
    // is the one remaining place a body value could leak. Mirror the coord-safe Global diagnostic.
    private val diagnosticLog: (String) -> Unit = {},
) : FollowingTimelineFlow {
    override suspend fun loadFirstPage(): FollowingTimelineOutcome {
        val result =
            apiClient.fetchFollowing(
                sessionId = sessionIdProvider.sessionId,
                cursor = null,
            )
        return when (result) {
            is FollowingApiResult.Success ->
                FollowingTimelineOutcome.Loaded(
                    posts = result.body.posts,
                    nextCursor = result.body.nextCursor,
                    upsell = result.body.upsell,
                )
            is FollowingApiResult.NetworkError -> {
                // Log the exception TYPE, not cause.message — Following carries coordinates in the body,
                // so cause.message could echo a parse fragment; the class name is the right diagnostic.
                diagnosticLog("following_network_error: ${result.cause::class.simpleName}")
                FollowingTimelineOutcome.NetworkError
            }
            is FollowingApiResult.HttpError ->
                when {
                    // Terminal 401 (survived the shipped Auth refresh) → SessionExpired, NOT NetworkError.
                    // Branched explicitly AHEAD of the fallback; the Auth plugin + SessionInvalidator
                    // still own the re-route to SignInScreen.
                    result.status == 401 -> FollowingTimelineOutcome.SessionExpired
                    // 400 invalid_cursor — not expected from the always-valid first page; surface as
                    // retryable WITH a status-only diagnostic (NO body field, NO coordinate, NO
                    // cause.message — coord-safe per the security review).
                    result.status == 400 -> {
                        diagnosticLog("following_invalid_request: status=400")
                        FollowingTimelineOutcome.Error
                    }
                    result.status in 500..599 -> FollowingTimelineOutcome.NetworkError
                    // Any other unenumerated non-2xx status maps to the DEFINED retryable NetworkError
                    // fallback rather than a generic "load failed" fallthrough (the match is over an Int,
                    // so a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic
                    // copy, not a `when` else). Mirrors GlobalRepository.
                    else -> FollowingTimelineOutcome.NetworkError
                }
        }
    }

    override suspend fun loadMore(cursor: String): FollowingTimelineOutcome {
        // Cursor-only: Following has NO spatial anchor to reuse (unlike Nearby) — only the chronological
        // cursor is passed. Same status→outcome mapping as loadFirstPage.
        val result =
            apiClient.fetchFollowing(
                sessionId = sessionIdProvider.sessionId,
                cursor = cursor,
            )
        return when (result) {
            is FollowingApiResult.Success ->
                FollowingTimelineOutcome.Loaded(
                    posts = result.body.posts,
                    nextCursor = result.body.nextCursor,
                    upsell = result.body.upsell,
                )
            is FollowingApiResult.NetworkError -> {
                // Type-only diagnostic (Following carries coordinates in the body) — log the exception
                // class, NEVER cause.message or a body field. Mirror the coord-safe loadFirstPage tag.
                diagnosticLog("following_loadmore_error: ${result.cause::class.simpleName}")
                FollowingTimelineOutcome.NetworkError
            }
            is FollowingApiResult.HttpError ->
                when {
                    result.status == 401 -> FollowingTimelineOutcome.SessionExpired
                    result.status == 400 -> {
                        diagnosticLog("following_loadmore_invalid_request: status=400")
                        FollowingTimelineOutcome.Error
                    }
                    result.status in 500..599 -> FollowingTimelineOutcome.NetworkError
                    else -> FollowingTimelineOutcome.NetworkError
                }
        }
    }
}
