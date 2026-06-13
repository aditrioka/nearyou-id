package id.nearyou.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [NotificationPermissionController] actual.
 *
 * - On API < 33 (`TIRAMISU`) there is NO runtime notification permission → [status]/[request] both report
 *   [NotificationPermissionStatus.GRANTED] (notifications are allowed by default).
 * - On API 33+: [status] checks `POST_NOTIFICATIONS` via `Context.checkSelfPermission`; a persisted
 *   "asked" flag disambiguates not-granted-never-asked ([NOT_DETERMINED]) from a prior denial ([DENIED]),
 *   mirroring `AndroidLocationPermissionController`. [request] routes through the
 *   [NotificationPermissionRequestBridge] Activity-result seam.
 */
class AndroidNotificationPermissionController(
    private val context: Context,
    private val bridge: NotificationPermissionRequestBridge,
) : NotificationPermissionController {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun status(): NotificationPermissionStatus =
        withContext(Dispatchers.IO) {
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> NotificationPermissionStatus.GRANTED
                isGranted() -> NotificationPermissionStatus.GRANTED
                prefs.getBoolean(KEY_ASKED, false) -> NotificationPermissionStatus.DENIED
                else -> NotificationPermissionStatus.NOT_DETERMINED
            }
        }

    override suspend fun request(): NotificationPermissionStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationPermissionStatus.GRANTED
        withContext(Dispatchers.IO) { prefs.edit().putBoolean(KEY_ASKED, true).apply() }
        val granted = bridge.request(Manifest.permission.POST_NOTIFICATIONS)
        return if (granted) NotificationPermissionStatus.GRANTED else NotificationPermissionStatus.DENIED
    }

    private fun isGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PREFS_NAME = "nearyou_notification_permission"
        const val KEY_ASKED = "post_notifications_requested"
    }
}
