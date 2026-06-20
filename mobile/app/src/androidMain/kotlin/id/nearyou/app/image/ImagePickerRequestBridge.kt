package id.nearyou.app.image

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges the Android Photo Picker (`ActivityResultContracts.PickVisualMedia`) Activity-result
 * callback back into a `suspend` call — the exact sibling of
 * [id.nearyou.app.location.LocationPermissionRequestBridge].
 *
 * `ActivityResultContracts.PickVisualMedia()` must be registered before the host Activity reaches
 * STARTED, so `MainActivity` registers the launcher (a field initializer) and hands it to this Koin
 * singleton. [AndroidImagePicker.pick] then calls [launch], which parks a `CompletableDeferred` and
 * opens the Photo Picker; `MainActivity`'s registered callback completes it via [onResult] with the
 * chosen `Uri` (or `null` on cancel). The Photo Picker needs NO storage permission, so unlike the
 * location bridge there is no permission string parameter.
 */
class ImagePickerRequestBridge {
    @Volatile
    var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null

    @Volatile
    private var pending: CompletableDeferred<Uri?>? = null

    /**
     * Open the Photo Picker for [request] and suspend until the user picks an image or cancels,
     * returning the chosen content `Uri` or `null`. Returns `null` immediately when no launcher is
     * registered (no foreground Activity to host the picker).
     */
    suspend fun launch(request: PickVisualMediaRequest): Uri? {
        val activeLauncher = launcher ?: return null
        // Resolve any abandoned prior request (e.g. a config change mid-pick) to null first, so a
        // stale deferred never leaks or double-completes (mirrors the location bridge).
        pending?.complete(null)
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        activeLauncher.launch(request)
        return deferred.await()
    }

    /** Invoked from the Activity's registered `PickVisualMedia` callback with the chosen `Uri`
     *  (or `null` when the user dismissed the picker). */
    fun onResult(uri: Uri?) {
        pending?.complete(uri)
        pending = null
    }
}
