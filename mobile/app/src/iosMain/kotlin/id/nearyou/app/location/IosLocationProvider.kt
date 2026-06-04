package id.nearyou.app.location

import id.nearyou.app.timeline.LocationProvider
import id.nearyou.distance.LatLng
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyReduced
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject

/**
 * iOS real [LocationProvider] actual — **reduced** accuracy (coarse; `kCLLocationAccuracyReduced`),
 * when-in-use only (no background/"Always" authorization — that lives in
 * `IosLocationPermissionController`).
 *
 * Acquisition is **bounded and stale-tolerant** (`mobile-location-acquisition-tuning`):
 * - A recent `CLLocationManager.location` (its `timestamp` younger than
 *   [LocationTuning.maxCachedFixAge]) is reused first — a recent cached fix costs no cold
 *   `requestLocation()`.
 * - Otherwise a one-shot `requestLocation()` wrapped in [withTimeoutOrNull] at
 *   [LocationTuning.acquisitionTimeout] — a defensive ceiling just above `requestLocation()`'s internal
 *   ~10s self-timeout (the native `didFailWithError` path normally fires first). On expiry it resolves
 *   to the existing [LocationUnavailableException]; a delivered `didFailWithError` likewise surfaces
 *   [LocationUnavailableException].
 *
 * The coordinate (and the cached fix's `timestamp`) are used to satisfy the request ONLY — neither is
 * ever logged (`openspec/specs/mobile-location` § "Acquired coordinate is never logged"); this class
 * makes no logging/diagnostic call.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationProvider : LocationProvider {
    override suspend fun current(): LatLng {
        val manager = CLLocationManager()
        manager.desiredAccuracy = kCLLocationAccuracyReduced

        manager.recentCachedFix()?.let { return it }

        return withTimeoutOrNull(LocationTuning.acquisitionTimeout) {
            suspendCancellableCoroutine { cont ->
                lateinit var delegate: OneShotLocationDelegate
                delegate =
                    OneShotLocationDelegate { result ->
                        retainedLocationDelegates.remove(delegate)
                        if (cont.isActive) cont.resumeWith(result)
                    }
                // CLLocationManager holds its delegate weakly; retain the delegate (and, transitively
                // via the cancellation handler, the manager) through the suspension so the one-shot
                // callback is not lost to deallocation.
                retainedLocationDelegates.add(delegate)
                manager.delegate = delegate
                cont.invokeOnCancellation {
                    manager.delegate = null
                    retainedLocationDelegates.remove(delegate)
                }
                manager.requestLocation()
            }
        } ?: throw LocationUnavailableException()
    }
}

/**
 * Reuses `CLLocationManager.location` when its `timestamp` is younger than
 * [LocationTuning.maxCachedFixAge], so a recent fix costs no cold `requestLocation()`. Returns `null`
 * (fall through to a fresh acquisition) when there is no cached fix, or it is too old, or its timestamp
 * is in the future (clock skew). The coordinate and the `timestamp` drive the reuse decision only —
 * neither is logged.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CLLocationManager.recentCachedFix(): LatLng? {
    val cached = location ?: return null
    val ageSeconds = -cached.timestamp.timeIntervalSinceNow
    if (ageSeconds < 0.0 || ageSeconds >= LocationTuning.maxCachedFixAge.inWholeSeconds.toDouble()) return null
    val coordinate = cached.coordinate.useContents { latitude to longitude }
    return LatLng(lat = coordinate.first, lng = coordinate.second)
}

/**
 * One-shot `CLLocationManagerDelegate`: forwards the first `didUpdateLocations` / `didFailWithError`
 * to [onComplete] as a [Result], detaching itself from the manager so the manager fires once.
 */
@OptIn(ExperimentalForeignApi::class)
private class OneShotLocationDelegate(
    private val onComplete: (Result<LatLng>) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(
        manager: CLLocationManager,
        didUpdateLocations: List<*>,
    ) {
        manager.delegate = null
        val location = didUpdateLocations.lastOrNull() as? CLLocation
        if (location == null) {
            onComplete(Result.failure(LocationUnavailableException()))
        } else {
            val coordinate = location.coordinate.useContents { latitude to longitude }
            onComplete(Result.success(LatLng(lat = coordinate.first, lng = coordinate.second)))
        }
    }

    override fun locationManager(
        manager: CLLocationManager,
        didFailWithError: NSError,
    ) {
        manager.delegate = null
        onComplete(Result.failure(LocationUnavailableException(didFailWithError.localizedDescription)))
    }
}

/** Strong references to in-flight one-shot delegates (CLLocationManager keeps its delegate weak). */
@OptIn(ExperimentalForeignApi::class)
private val retainedLocationDelegates = mutableSetOf<OneShotLocationDelegate>()
