package id.nearyou.app.location

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS [LocationPermissionController] actual — **when-in-use** authorization via `CLLocationManager`
 * (no "Always"/background authorization; spec § "Coarse-only, no fine, no background").
 *
 * - [status]: maps `CLLocationManager.authorizationStatus`. `restricted` / `denied` collapse into
 *   [LocationPermissionStatus.DENIED]; the when-in-use / always grants map to
 *   [LocationPermissionStatus.GRANTED].
 * - [request]: `requestWhenInUseAuthorization()`, resolving on the first terminal authorization
 *   callback (the transient `notDetermined` pre-decision callback is ignored).
 * - [openAppSettings]: opens `UIApplicationOpenSettingsURLString` (this app's settings page).
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationPermissionController : LocationPermissionController {
    // Main-confined (2026-06-10 audit, 07-#7): CLLocationManager delivers delegate callbacks
    // on the run loop of the creating thread — created off-main (any future dispatcher hop
    // around a caller) the terminal callback never arrives and request() suspends forever.
    // Confinement removes that hang class structurally, so request() deliberately has NO
    // decision timeout: the wait is on the USER's prompt choice, which is unbounded by design
    // (a ceiling would misreport a slow human decision as DENIED). This also keeps the
    // retained-delegate sets main-thread-confined (no synchronization needed).
    override suspend fun status(): LocationPermissionStatus =
        withContext(Dispatchers.Main) {
            mapAuthorization(CLLocationManager().authorizationStatus)
        }

    override suspend fun request(): LocationPermissionStatus =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val manager = CLLocationManager()
                lateinit var delegate: AuthorizationDelegate
                delegate =
                    AuthorizationDelegate { status ->
                        // notDetermined is the transient pre-decision callback; wait for a terminal status.
                        if (status == kCLAuthorizationStatusNotDetermined) return@AuthorizationDelegate
                        manager.delegate = null
                        retainedAuthorizationDelegates.remove(delegate)
                        if (cont.isActive) cont.resume(mapAuthorization(status))
                    }
                retainedAuthorizationDelegates.add(delegate)
                manager.delegate = delegate
                cont.invokeOnCancellation {
                    manager.delegate = null
                    retainedAuthorizationDelegates.remove(delegate)
                }
                manager.requestWhenInUseAuthorization()
            }
        }

    override fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}

/** Delegate that forwards `didChangeAuthorizationStatus` to [onChange]. */
@OptIn(ExperimentalForeignApi::class)
private class AuthorizationDelegate(
    private val onChange: (CLAuthorizationStatus) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(
        manager: CLLocationManager,
        didChangeAuthorizationStatus: CLAuthorizationStatus,
    ) {
        onChange(didChangeAuthorizationStatus)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun mapAuthorization(status: CLAuthorizationStatus): LocationPermissionStatus =
    when (status) {
        kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> LocationPermissionStatus.GRANTED
        kCLAuthorizationStatusNotDetermined -> LocationPermissionStatus.NOT_DETERMINED
        else -> LocationPermissionStatus.DENIED
    }

/** Strong references to in-flight authorization delegates (CLLocationManager keeps its delegate weak). */
@OptIn(ExperimentalForeignApi::class)
private val retainedAuthorizationDelegates = mutableSetOf<AuthorizationDelegate>()
