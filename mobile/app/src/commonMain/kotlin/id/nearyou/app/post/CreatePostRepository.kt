package id.nearyou.app.post

import id.nearyou.app.infra.amplitude.AnalyticsTracker
import id.nearyou.app.infra.amplitude.NoOpAnalyticsTracker
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.location.LocationUnavailableException
import id.nearyou.app.timeline.LocationProvider
import kotlin.coroutines.cancellation.CancellationException

/**
 * Orchestrates a post submission (design D1/D3): permission-gate → acquire the device coordinate →
 * `POST /api/v1/posts` → map the HTTP **status + `error.code`** to exactly one [PostCreationOutcome]
 * with no generic fallthrough.
 *
 * **Permission gate is mandatory and runs FIRST** (design D1 reconciliation note): the shipped
 * `AndroidLocationProvider.current()` calls `FusedLocationProviderClient.getCurrentLocation(...)` with
 * NO permission guard and can throw a synchronous `SecurityException` if invoked under a denied
 * permission. So `submit()` consults [permissionController] before ever touching [locationProvider] —
 * a denied permission short-circuits to [PostCreationOutcome.LocationUnavailable] with no `current()`
 * call and no POST.
 *
 * PII discipline (design D7): the [diagnosticLog] sink is **coordinate-free by construction** — its
 * signature carries only the HTTP `status` and `errorCode` primitives, never the `content` or the
 * `LatLng` — so the no-coordinate-logging discipline is structural, not merely review-enforced. The
 * repository never `println`s/logs the coordinate or the request body.
 */
class CreatePostRepository(
    private val apiClient: PostCreationApiClient,
    private val locationProvider: LocationProvider,
    private val permissionController: LocationPermissionController,
    // Diagnostic sink for non-user-facing error detail (status + error code only — NEVER the
    // coordinate or content). Wired to Sentry / OTel when that lands; no-op for now.
    private val diagnosticLog: (status: Int, errorCode: String?) -> Unit = { _, _ -> },
    // mobile-amplitude-analytics — consent-gated tracker + the authenticated user-id source. Both default
    // to no-op so existing callers/tests are unaffected; MobileModule passes the real ConsentGated tracker
    // + a SelfUserIdProvider-backed lambda.
    private val analytics: AnalyticsTracker = NoOpAnalyticsTracker,
    private val selfUserId: suspend () -> String? = { null },
) : CreatePostFlow {
    override suspend fun submit(
        content: String,
        imageId: String?,
    ): PostCreationOutcome {
        // 1. Permission gate FIRST — never reach the un-guarded platform provider under a denial.
        //    status()/request() suspend; a CancellationException propagates naturally (not caught).
        val granted =
            when (permissionController.status()) {
                LocationPermissionStatus.GRANTED -> true
                // Terminal denial: the OS shows nothing → no prompt, no current(), no POST.
                LocationPermissionStatus.DENIED -> return PostCreationOutcome.LocationUnavailable
                // Contextual OS prompt; proceed only if the user grants.
                LocationPermissionStatus.NOT_DETERMINED ->
                    permissionController.request() == LocationPermissionStatus.GRANTED
            }
        if (!granted) return PostCreationOutcome.LocationUnavailable

        // 2. Granted path: acquire the device fix (now safe to call the platform provider). A
        //    granted-but-no-fix throws LocationUnavailableException; defend against a late
        //    permission-revocation race surfacing another platform exception too — either way it
        //    means "no usable location", so no POST is issued. Cancellation is rethrown.
        val location =
            try {
                locationProvider.current()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: LocationUnavailableException) {
                return PostCreationOutcome.LocationUnavailable
            } catch (_: Throwable) {
                return PostCreationOutcome.LocationUnavailable
            }

        // 3. POST + exhaustive status/error.code mapping. The optional imageId is threaded straight to the
        //    request body (null ⇒ a text-only post; the body omits image_id, byte-identical to pre-change).
        return when (
            val result =
                apiClient.createPost(content = content, lat = location.lat, lng = location.lng, imageId = imageId)
        ) {
            is PostCreationApiResult.Success -> {
                // mobile-amplitude-analytics — emit post_created (consent-gated downstream). Privacy-safe:
                // user id only, no coordinates, no content.
                selfUserId()?.let { analytics.track("post_created", it) }
                PostCreationOutcome.Success(result.id)
            }
            is PostCreationApiResult.NetworkError -> PostCreationOutcome.NetworkError
            is PostCreationApiResult.HttpError -> mapHttpError(result)
        }
    }

    /**
     * Maps a non-2xx to exactly one outcome (no wildcard "submit failed"). 400s key on the parsed
     * `error.code`; an unknown/absent 400 code is the DEFINED retryable [PostCreationOutcome.Error]
     * with a logged diagnostic. 5xx (and any other unenumerated status) → [NetworkError]. 401 never
     * reaches here — the shipped `Auth` plugin handles it (terminal 401 → re-route to sign-in).
     */
    private fun mapHttpError(error: PostCreationApiResult.HttpError): PostCreationOutcome =
        when {
            error.status == 400 ->
                when (error.errorCode) {
                    "content_empty" -> PostCreationOutcome.ContentEmpty
                    "content_too_long" -> PostCreationOutcome.ContentTooLong
                    "location_out_of_bounds" -> PostCreationOutcome.LocationOutOfBounds
                    "content_moderated_profanity" -> PostCreationOutcome.ContentRejected
                    else -> {
                        diagnosticLog(error.status, error.errorCode)
                        PostCreationOutcome.Error
                    }
                }
            // 429 — the daily post cap (docs/05 Layer 2; backend returns Retry-After + the
            // rate_limited envelope). A distinct outcome, NOT the retryable fallthrough: retrying
            // cannot succeed before the daily reset, and the network-error copy misleads.
            error.status == 429 -> PostCreationOutcome.RateLimited
            // 5xx (server-side) → retryable. Kept as an explicit arm rather than folded into `else`
            // to mirror NearbyTimelineRepository and leave a self-documenting slot for a future
            // distinct 5xx outcome.
            error.status in 500..599 -> PostCreationOutcome.NetworkError
            // Any other unenumerated status (e.g. an unexpected non-401 4xx) → the DEFINED retryable
            // NetworkError, NOT a generic "submit failed" fallthrough. 401 never reaches here: the
            // shipped Auth plugin consumes it upstream (terminal 401 → re-route to sign-in).
            else -> PostCreationOutcome.NetworkError
        }
}
