package id.nearyou.app.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Android [LocationPermissionController] actual.
 *
 * - [status]: checks `ACCESS_COARSE_LOCATION` via `Context.checkSelfPermission` (API 23+; minSdk 24).
 *   Android cannot distinguish "never asked" from "permanently denied" at the OS level, so a persisted
 *   flag disambiguates: not-granted + never-asked ⇒ `NOT_DETERMINED` (show the rationale, then the
 *   prompt); not-granted + asked-before ⇒ `DENIED` (denial fallback + "Buka Pengaturan"). The flag
 *   persists across cold starts so a prior denial does NOT re-nag the rationale on every launch
 *   (spec § "A prior denial does not re-show the rationale on every Nearby visit").
 * - [request]: routes through the [LocationPermissionRequestBridge] (the Activity-result seam) and
 *   requests `ACCESS_COARSE_LOCATION` only.
 * - [openAppSettings]: an app-details settings intent scoped to THIS package via
 *   `Uri.fromParts("package", packageName, null)` — never an externally-derived URI.
 */
class AndroidLocationPermissionController(
    private val context: Context,
    private val bridge: LocationPermissionRequestBridge,
) : LocationPermissionController {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun status(): LocationPermissionStatus =
        when {
            isCoarseGranted() -> LocationPermissionStatus.GRANTED
            prefs.getBoolean(KEY_ASKED, false) -> LocationPermissionStatus.DENIED
            else -> LocationPermissionStatus.NOT_DETERMINED
        }

    override suspend fun request(): LocationPermissionStatus {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        val granted = bridge.request(Manifest.permission.ACCESS_COARSE_LOCATION)
        return if (granted) LocationPermissionStatus.GRANTED else LocationPermissionStatus.DENIED
    }

    override fun openAppSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    private fun isCoarseGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PREFS_NAME = "nearyou_location_permission"
        const val KEY_ASKED = "coarse_location_requested"
    }
}
