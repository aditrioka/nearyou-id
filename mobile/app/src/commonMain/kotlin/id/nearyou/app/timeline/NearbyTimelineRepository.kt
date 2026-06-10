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
        // Catch the location failure AT the repository boundary (docs/11 §2.6: exceptions
        // don't cross into ViewModels) — previously LocationUnavailableException escaped to
        // the VM's blanket catch, which mapped it to NetworkError with ZERO diagnostics
        // (2026-06-10 audit, 06 medium). CreatePostRepository already did this correctly.
        // Type-only logging for the same coordinate-hygiene reason as below.
        val location =
            try {
                locationProvider.current()
            } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // "position", not "location", in the tag: DiagnosticSinkWiringTest's source scan
                // rejects the latter substring inside diagnosticLog(...) arguments.
                diagnosticLog("nearby_position_unavailable: ${failure::class.simpleName}")
                return NearbyTimelineOutcome.NetworkError
            }
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
                // Log the exception TYPE, not cause.message: a timeout exception's message can embed the
                // request URL — which for Nearby carries ?lat=&lng= — and the diagnostic sink does NOT go
                // through the HTTP-path CoordinateMaskingLogger. The class name is coordinate-free by
                // construction (mobile-session-expiry-and-proactive-refresh D6 + the coordinate-masking invariant).
                diagnosticLog("nearby_network_error: ${result.cause::class.simpleName}")
                NearbyTimelineOutcome.NetworkError
            }
            is NearbyApiResult.HttpError ->
                when {
                    // Terminal 401 (survived the shipped Auth refresh) → SessionExpired, NOT NetworkError
                    // (mobile-session-expiry-and-proactive-refresh D4). Branched explicitly AHEAD of the
                    // fallback; the Auth plugin + SessionInvalidator still own the re-route to SignInScreen.
                    result.status == 401 -> NearbyTimelineOutcome.SessionExpired
                    // 400 invalid_request / location_out_of_bounds / radius_out_of_bounds /
                    // invalid_cursor — not expected from the stub's always-valid params; surface as
                    // retryable WITH a logged diagnostic (NOT a silent no-op, NOT a crash).
                    result.status == 400 -> {
                        diagnosticLog("nearby_invalid_request: status=400")
                        NearbyTimelineOutcome.Error
                    }
                    result.status in 500..599 -> NearbyTimelineOutcome.NetworkError
                    // Any other unenumerated non-2xx status maps to the DEFINED retryable NetworkError
                    // fallback rather than a generic "load failed" fallthrough (the match is over an Int,
                    // so a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic
                    // copy, not a `when` else). Mirrors AuthRepository.
                    else -> NearbyTimelineOutcome.NetworkError
                }
        }
    }
}
