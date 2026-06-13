package id.nearyou.app.notifications

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * iOS [NotificationPermissionController] actual — alert+badge+sound authorization via
 * `UNUserNotificationCenter`.
 *
 * - [status]: maps `getNotificationSettingsWithCompletionHandler`'s `authorizationStatus`
 *   (`authorized`/`provisional`/`ephemeral` → GRANTED; `notDetermined` → NOT_DETERMINED; `denied` → DENIED).
 * - [request]: `requestAuthorizationWithOptions`, resolving on the completion handler.
 */
@OptIn(ExperimentalForeignApi::class)
class IosNotificationPermissionController : NotificationPermissionController {
    override suspend fun status(): NotificationPermissionStatus =
        suspendCancellableCoroutine { cont ->
            UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
                if (cont.isActive) cont.resume(mapStatus(settings?.authorizationStatus))
            }
        }

    override suspend fun request(): NotificationPermissionStatus =
        suspendCancellableCoroutine { cont ->
            val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(options) { granted, _ ->
                if (cont.isActive) {
                    cont.resume(
                        if (granted) NotificationPermissionStatus.GRANTED else NotificationPermissionStatus.DENIED,
                    )
                }
            }
        }

    private fun mapStatus(status: UNAuthorizationStatus?): NotificationPermissionStatus =
        when (status) {
            UNAuthorizationStatusAuthorized,
            UNAuthorizationStatusProvisional,
            UNAuthorizationStatusEphemeral,
            -> NotificationPermissionStatus.GRANTED
            UNAuthorizationStatusNotDetermined -> NotificationPermissionStatus.NOT_DETERMINED
            else -> NotificationPermissionStatus.DENIED
        }
}
