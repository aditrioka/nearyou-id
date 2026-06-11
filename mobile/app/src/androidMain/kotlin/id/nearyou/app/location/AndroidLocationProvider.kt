package id.nearyou.app.location

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
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
 * accuracy (balanced-power priority; `ACCESS_COARSE_LOCATION` only, never fine; `GRANULARITY_COARSE`).
 *
 * Acquisition is **bounded and stale-tolerant** (`mobile-location-acquisition-tuning`):
 * - An age-acceptable `lastLocation` (younger than [LocationTuning.maxCachedFixAge], measured on the
 *   monotonic `elapsedRealtimeNanos` clock) is returned first — a recent cached fix costs no cold
 *   acquisition.
 * - Otherwise a `getCurrentLocation` carrying a [CurrentLocationRequest] with `setDurationMillis` (the
 *   request **expires** instead of hanging unboundedly — the headline fix over the old 2-arg
 *   `getCurrentLocation(priority, token)` overload that had no bound), `setMaxUpdateAgeMillis` (a recent
 *   system-cached fix is accepted), and `setGranularity(GRANULARITY_COARSE)`.
 * - If neither yields a coordinate it throws [LocationUnavailableException] — the granted-but-no-fix
 *   path the Nearby gate / composer map to the existing retryable state.
 *
 * The acquired coordinate is returned to the caller ONLY — it is NEVER logged
 * (`openspec/specs/mobile-location` § "Acquired coordinate is never logged"); this class makes no
 * logging/diagnostic call. Invoked only after permission is confirmed granted (the screen gates the
 * fetch), so a `@SuppressLint("MissingPermission")` is unnecessary at the call site granted by the
 * gate; the Fused calls themselves are permission-checked by the OS.
 */
class AndroidLocationProvider(
    context: Context,
) : LocationProvider {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    override suspend fun current(): LatLng {
        val request =
            CurrentLocationRequest
                .Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(LocationTuning.acquisitionTimeout.inWholeMilliseconds)
                .setMaxUpdateAgeMillis(LocationTuning.maxCachedFixAge.inWholeMilliseconds)
                .setGranularity(Granularity.GRANULARITY_COARSE)
                .build()
        // The CTS is wired into awaitOrNull's cancellation handler so navigating away
        // mid-acquisition cancels the fused request instead of letting it run its full
        // duration in the background (2026-06-10 audit, 07-#11).
        val cts = CancellationTokenSource()
        val location =
            client.lastLocation.awaitOrNull()?.takeIf { it.isWithinMaxAge() }
                ?: client.getCurrentLocation(request, cts.token).awaitOrNull(onCancel = { cts.cancel() })
                ?: throw LocationUnavailableException()
        return LatLng(lat = location.latitude, lng = location.longitude)
    }
}

/**
 * True when [this] fix is younger than [LocationTuning.maxCachedFixAge] on the monotonic
 * `elapsedRealtimeNanos` clock (immune to wall-clock changes; an unset/zeroed timestamp reads as too
 * old and falls through to a fresh acquisition). The age is computed for the reuse decision only — it
 * is never logged.
 */
private fun Location.isWithinMaxAge(): Boolean {
    val ageMillis = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
    return ageMillis in 0..LocationTuning.maxCachedFixAge.inWholeMilliseconds
}

/**
 * Awaits a Play Services [Task], resuming with the result on success or `null` on failure/cancel — so
 * "no fix" surfaces as a uniform `null` the caller maps to [LocationUnavailableException]. Avoids a
 * `kotlinx-coroutines-play-services` dependency by wrapping the listener API directly. [onCancel]
 * propagates the coroutine's cancellation to the underlying request (the Task API has no direct hook).
 */
private suspend fun <T> Task<T>.awaitOrNull(onCancel: (() -> Unit)? = null): T? =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
        addOnCanceledListener { cont.resume(null) }
        if (onCancel != null) cont.invokeOnCancellation { onCancel() }
    }
