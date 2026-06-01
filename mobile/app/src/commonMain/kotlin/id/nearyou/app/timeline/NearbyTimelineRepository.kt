package id.nearyou.app.timeline

/**
 * The Free-tier fixed Nearby radius — 20 km per `docs/02-Product.md` § Nearby Timeline ("Free: stuck
 * at 20km"). A single named constant (NOT a magic literal at the call site) so the deferred
 * `mobile-nearby-radius-slider` follow-up has one site to generalize.
 */
const val NEARBY_RADIUS_M: Int = 20_000

/**
 * Orchestrates a Nearby first-page fetch: acquire the coordinate from [locationProvider] → call
 * `GET /api/v1/timeline/nearby` (fixed [NEARBY_RADIUS_M], per-process [SessionIdProvider] header) →
 * map the HTTP **status** to exactly one [NearbyTimelineOutcome] (design D6). There is no generic
 * "load failed" fallthrough; `401` is delegated to the shipped `Auth` plugin (this repository MUST
 * NOT reimplement token refresh / re-route).
 */
class NearbyTimelineRepository(
    private val apiClient: NearbyTimelineApiClient,
    private val locationProvider: LocationProvider,
    private val sessionIdProvider: SessionIdProvider,
    // Diagnostic sink for non-user-facing error detail. Wired to Sentry / OTel when that lands;
    // no-op for now. MUST NOT carry tokens or coordinates (none are passed here).
    private val diagnosticLog: (String) -> Unit = {},
) : NearbyTimelineFlow {
    override suspend fun loadFirstPage(): NearbyTimelineOutcome {
        val location = locationProvider.current()
        val result =
            apiClient.fetchNearby(
                lat = location.lat,
                lng = location.lng,
                radiusM = NEARBY_RADIUS_M,
                sessionId = sessionIdProvider.sessionId,
                cursor = null,
            )
        return when (result) {
            is NearbyApiResult.Success ->
                NearbyTimelineOutcome.Loaded(
                    posts = result.body.posts,
                    nextCursor = result.body.nextCursor,
                    upsell = result.body.upsell,
                )
            is NearbyApiResult.NetworkError -> {
                diagnosticLog("nearby_network_error: ${result.cause.message}")
                NearbyTimelineOutcome.NetworkError
            }
            is NearbyApiResult.HttpError ->
                when {
                    // 400 invalid_request / location_out_of_bounds / radius_out_of_bounds /
                    // invalid_cursor — not expected from the stub's always-valid params; surface as
                    // retryable WITH a logged diagnostic (NOT a silent no-op, NOT a crash).
                    result.status == 400 -> {
                        diagnosticLog("nearby_invalid_request: status=400")
                        NearbyTimelineOutcome.Error
                    }
                    result.status in 500..599 -> NearbyTimelineOutcome.NetworkError
                    // 401 is handled upstream by the shipped Auth plugin + SessionInvalidator
                    // (terminal 401 → store cleared → RootRouterScreen re-routes to SignInScreen).
                    // Any other unenumerated status maps to the DEFINED retryable NetworkError state
                    // rather than a generic "load failed" fallthrough (mirrors AuthRepository).
                    else -> NearbyTimelineOutcome.NetworkError
                }
        }
    }
}
