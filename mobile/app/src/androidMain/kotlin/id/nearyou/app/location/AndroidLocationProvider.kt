package id.nearyou.app.location

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import id.nearyou.app.timeline.LocationProvider
import id.nearyou.distance.LatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android real [LocationProvider] actual — `FusedLocationProviderClient` with **coarse/approximate**
 * accuracy (balanced-power priority; `ACCESS_COARSE_LOCATION` only, never fine). Best-effort
 * acquisition: a fresh `getCurrentLocation` fix, falling back to the cached `getLastLocation`; if
 * neither yields a coordinate it throws [LocationUnavailableException] (the granted-but-no-fix path
 * the Nearby gate maps to the existing retryable error state).
 *
 * The acquired coordinate is returned to the caller ONLY — it is NEVER logged
 * (`openspec/specs/mobile-location` § "Acquired coordinate is never logged"); this class makes no
 * logging/diagnostic call.
 *
 * Invoked only after permission is confirmed granted (the screen gates the fetch), so a
 * `@SuppressLint("MissingPermission")` is unnecessary at the call site granted by the gate; the
 * Fused calls themselves are permission-checked by the OS.
 */
class AndroidLocationProvider(
    context: Context,
) : LocationProvider {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    override suspend fun current(): LatLng {
        val location =
            client
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
                .awaitOrNull()
                ?: client.lastLocation.awaitOrNull()
                ?: throw LocationUnavailableException()
        return LatLng(lat = location.latitude, lng = location.longitude)
    }
}

/**
 * Awaits a Play Services [Task], resuming with the result on success or `null` on failure/cancel — so
 * "no fix" surfaces as a uniform `null` the caller maps to [LocationUnavailableException]. Avoids a
 * `kotlinx-coroutines-play-services` dependency by wrapping the listener API directly.
 */
private suspend fun <T> Task<T>.awaitOrNull(): T? =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
        addOnCanceledListener { cont.resume(null) }
    }
