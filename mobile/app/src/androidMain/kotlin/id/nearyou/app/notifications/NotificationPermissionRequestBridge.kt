package id.nearyou.app.notifications

import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges the Android `POST_NOTIFICATIONS` runtime-permission Activity-result callback back into a
 * `suspend` call — a sibling of `LocationPermissionRequestBridge`. `MainActivity` registers the launcher
 * (a field initializer, before STARTED as `ActivityResultContracts` requires) and hands it to this Koin
 * singleton; [AndroidNotificationPermissionController.request] parks a `CompletableDeferred` and launches
 * the OS dialog; the registered callback completes it via [onResult].
 */
class NotificationPermissionRequestBridge {
    @Volatile
    var launcher: ActivityResultLauncher<String>? = null

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    /** Launch the OS permission dialog for [permission] and suspend until the user responds. */
    suspend fun request(permission: String): Boolean {
        val activeLauncher = launcher ?: return false
        // Resolve any abandoned prior request (config change mid-prompt) to false so no stale deferred leaks.
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
