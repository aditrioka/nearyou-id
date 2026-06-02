package id.nearyou.app.location

import id.nearyou.app.timeline.LocationProvider
import id.nearyou.distance.LatLng
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyReduced
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * iOS real [LocationProvider] actual — a one-shot `CLLocationManager.requestLocation()` with
 * **reduced** accuracy (coarse; design D2 / Open Q3 sets `kCLLocationAccuracyReduced` explicitly).
 * Resolves with the delivered coordinate, or throws [LocationUnavailableException] on failure (the
 * granted-but-no-fix path the Nearby gate maps to the existing retryable error state).
 *
 * The coordinate is returned to the caller ONLY — never logged (`openspec/specs/mobile-location`
 * § "Acquired coordinate is never logged"). No background/"always" authorization is requested.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationProvider : LocationProvider {
    override suspend fun current(): LatLng =
        suspendCancellableCoroutine { cont ->
            val manager = CLLocationManager()
            manager.desiredAccuracy = kCLLocationAccuracyReduced
            lateinit var delegate: OneShotLocationDelegate
            delegate =
                OneShotLocationDelegate { result ->
                    retainedLocationDelegates.remove(delegate)
                    if (cont.isActive) cont.resumeWith(result)
                }
            // CLLocationManager holds its delegate weakly; retain the delegate (and, transitively via
            // the cancellation handler, the manager) through the suspension so the one-shot callback
            // is not lost to deallocation.
            retainedLocationDelegates.add(delegate)
            manager.delegate = delegate
            cont.invokeOnCancellation {
                manager.delegate = null
                retainedLocationDelegates.remove(delegate)
            }
            manager.requestLocation()
        }
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
