package id.nearyou.app.location

import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges the Android runtime-permission Activity-result callback back into a `suspend` call.
 *
 * `ActivityResultContracts.RequestPermission()` must be registered before the host Activity reaches
 * STARTED, so `MainActivity` registers the launcher (a field initializer) and hands it to this Koin
 * singleton. [AndroidLocationPermissionController.request] then calls [request], which parks a
 * `CompletableDeferred` and launches the OS dialog; `MainActivity`'s registered callback completes it
 * via [onResult]. This is the Activity-result seam the `mobile-location` spec names — a sibling of
 * the `CurrentActivityHolder` seam Credential Manager uses.
 */
class LocationPermissionRequestBridge {
    @Volatile
    var launcher: ActivityResultLauncher<String>? = null

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    /** Launch the OS permission dialog for [permission] and suspend until the user responds. */
    suspend fun request(permission: String): Boolean {
        val activeLauncher = launcher ?: return false
        // Resolve any abandoned prior request (e.g. a config change mid-prompt) to false first, so a
        // stale deferred never leaks or double-completes.
        pending?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        activeLauncher.launch(permission)
        return deferred.await()
    }

    /** Invoked from the Activity's registered `RequestPermission` callback with the grant result. */
    fun onResult(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }
}
