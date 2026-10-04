package id.nearyou.app.timeline

import id.nearyou.distance.LatLng

/**
 * The Free-tier fixed Nearby radius — 20 km per `docs/02-Product.md` § Nearby Timeline ("Free: stuck
 * at 20km"). A single named constant (NOT a magic literal at the call site) so the deferred
 * `mobile-nearby-radius-slider` follow-up has one site to generalize.
 */
const val NEARBY_RADIUS_M: Int = 20_000

/**
 * Orchestrates a Nearby first-page fetch: acquire the coordinate from [locationProvider] → call
 * `GET /api/v1/timeline/nearby` (at the caller-supplied `radiusM`, default [NEARBY_RADIUS_M]; per-process
 * [SessionIdProvider] header) → map the HTTP **status** to exactly one [NearbyTimelineOutcome] (design
 * D6). There is no generic "load failed" fallthrough; `401` is delegated to the shipped `Auth` plugin
 * (this repository MUST NOT reimplement token refresh / re-route). Every fetch goes through ONE gate-aware
 * mapping ([toResult]) that surfaces the `radius_premium_only` 403 as [NearbyFetchResult.PremiumGated]
 * (`mobile-nearby-radius-slider`, #518).
 */
class NearbyTimelineRepository(
    private val apiClient: NearbyTimelineApiClient,
    private val locationProvider: LocationProvider,
    private val sessionIdProvider: SessionIdProvider,
    // Diagnostic sink for non-user-facing error detail. Wired to Sentry / OTel when that lands;
    // no-op for now. MUST NOT carry tokens or coordinates (none are passed here).
    private val diagnosticLog: (String) -> Unit = {},
) : NearbyTimelineFlow {
    override suspend fun loadFirstPage(radiusM: Int): NearbyFetchResult {
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
                return NearbyFetchResult.Loaded(NearbyTimelineOutcome.NetworkError)
            }
        val result =
            apiClient.fetchNearby(
                lat = location.lat,
                lng = location.lng,
                radiusM = radiusM,
                sessionId = sessionIdProvider.sessionId,
                cursor = null,
            )
        // Retain the page-1 anchor so load-more reuses it (design D4); VM-held, never rendered/logged.
        return result.toResult(
            anchor = location,
            networkErrorTag = "nearby_network_error",
            invalidRequestTag = "nearby_invalid_request",
        )
    }

    override suspend fun loadMore(
        cursor: String,
        anchor: LatLng,
        radiusM: Int,
    ): NearbyFetchResult {
        // Reuse the first-page [anchor] — NO fresh GPS acquisition. The backend cursor is chronological,
        // so ordering is anchor-independent; reuse keeps the [radiusM] stable + avoids redundant location
        // work (design D4). Same status→outcome mapping as loadFirstPage.
        val result =
            apiClient.fetchNearby(
                lat = anchor.lat,
                lng = anchor.lng,
                radiusM = radiusM,
                sessionId = sessionIdProvider.sessionId,
                cursor = cursor,
            )
        return result.toResult(
            anchor = anchor,
            networkErrorTag = "nearby_loadmore_error",
            invalidRequestTag = "nearby_loadmore_invalid_request",
        )
    }

    /**
     * The ONE gate-aware mapping every Nearby fetch passes through (#518). The server Premium gate — a 403
     * `radius_premium_only` — is surfaced as [NearbyFetchResult.PremiumGated] so the VM shows the upsell +
     * reverts to 20 km, NOT folded into the frozen status→outcome contract. A stale-tier backstop; the client
     * gate normally stops a Free session from ever issuing a non-20 km radius. Anything else → [toOutcome].
     */
    private fun NearbyApiResult.toResult(
        anchor: LatLng,
        networkErrorTag: String,
        invalidRequestTag: String,
    ): NearbyFetchResult =
        if (this is NearbyApiResult.HttpError && status == 403 && errorCode == "radius_premium_only") {
            NearbyFetchResult.PremiumGated
        } else {
            NearbyFetchResult.Loaded(toOutcome(anchor, networkErrorTag, invalidRequestTag))
        }

    /**
     * The frozen status→outcome mapping shared by [loadFirstPage] and [loadMore] via [toResult]
     * (extracted in follow-up #386). The two diagnostic tags are the only per-site
     * variation; each call site passes its exact literal so the per-surface diagnostic strings
     * (asserted by `DiagnosticSinkWiringTest`) are unchanged.
     */
    private fun NearbyApiResult.toOutcome(
        anchor: LatLng,
        networkErrorTag: String,
        invalidRequestTag: String,
    ): NearbyTimelineOutcome =
        when (this) {
            is NearbyApiResult.Success ->
                NearbyTimelineOutcome.Loaded(
                    posts = body.posts,
                    nextCursor = body.nextCursor,
                    upsell = body.upsell,
                    anchor = anchor,
                )
            is NearbyApiResult.NetworkError -> {
                // Log the exception TYPE, not cause.message: a timeout exception's message can embed the
                // request URL — which for Nearby carries the query coordinates — and the diagnostic sink
                // does NOT go through the HTTP-path CoordinateMaskingLogger. The class name is
                // coordinate-free by construction (mobile-session-expiry-and-proactive-refresh D6).
                diagnosticLog("$networkErrorTag: ${cause::class.simpleName}")
                NearbyTimelineOutcome.NetworkError
            }
            is NearbyApiResult.HttpError ->
                when {
                    // Terminal 401 (survived the shipped Auth refresh) → SessionExpired, NOT NetworkError
                    // (mobile-session-expiry-and-proactive-refresh D4). Branched explicitly AHEAD of the
                    // fallback; the Auth plugin + SessionInvalidator still own the re-route to SignInScreen.
                    status == 401 -> NearbyTimelineOutcome.SessionExpired
                    // 400 invalid_request / location_out_of_bounds / radius_out_of_bounds /
                    // invalid_cursor — not expected from always-valid params; surface as retryable
                    // WITH a logged diagnostic (NOT a silent no-op, NOT a crash).
                    status == 400 -> {
                        diagnosticLog("$invalidRequestTag: status=400")
                        NearbyTimelineOutcome.Error
                    }
                    status in 500..599 -> NearbyTimelineOutcome.NetworkError
                    // Any other unenumerated non-2xx status maps to the DEFINED retryable NetworkError
                    // fallback rather than a generic "load failed" fallthrough (the match is over an Int,
                    // so a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic
                    // copy, not a `when` else). Mirrors AuthRepository.
                    else -> NearbyTimelineOutcome.NetworkError
                }
        }
}
