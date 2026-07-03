package id.nearyou.app.push

import org.koin.mp.KoinPlatformTools
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * The iOS notification-tap handler (`mobile-push-message-handling` task 6.1, symmetric with the
 * Android `MainActivity` extras path): reads the routing fields the `fcm-push-dispatch` MODIFY
 * added to the APNs custom payload (`type` / `target_type` / `target_id` / `actor_user_id`, plus
 * `body_full` = the JSON-stringified `body_data` whose `conversation_id` addresses chat) from the
 * tapped notification's `userInfo` and offers them to the consumed-once [PushTapNavSignal] — the
 * SAME shared resolver + nav signal the Android path feeds (no second nav pattern). The ids are
 * opaque route payload — never rendered or logged.
 *
 * Inert without operator config (design D9): without Firebase/APNs setup no notification ever
 * arrives, and a missing Koin context (or a `type`-less `userInfo`) makes [offerPushTapRouting] a
 * silent no-op.
 */
class PushNotificationTapDelegate :
    NSObject(),
    UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        offerPushTapRouting(didReceiveNotificationResponse.notification.request.content.userInfo)
        withCompletionHandler()
    }
}

/** Pure userInfo → nav-signal projection (exercised directly by tests; the delegate is a thin shim). */
internal fun offerPushTapRouting(userInfo: Map<Any?, *>) {
    val type = userInfo["type"] as? String ?: return
    if (type.isBlank()) return
    // Lazily + defensively resolved (the getOrNull idiom): a not-started Koin (e.g. an NSE-like
    // headless context) makes the tap a no-op instead of a crash.
    val signal =
        KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<PushTapNavSignal>() ?: return
    signal.offer(
        PushTapRouting.fromWire(
            type = type,
            targetType = userInfo["target_type"] as? String,
            targetId = userInfo["target_id"] as? String,
            actorUserId = userInfo["actor_user_id"] as? String,
            // body_full IS the JSON-stringified body_data — the same shape Android's body_data
            // extra carries, so the shared resolver parses conversation_id identically.
            bodyDataJson = userInfo["body_full"] as? String,
        ),
    )
}

// The center holds its delegate WEAKLY — keep the strong reference here for the app's lifetime.
private var installedDelegate: PushNotificationTapDelegate? = null

/**
 * Installs [PushNotificationTapDelegate] as the notification-center delegate (idempotent; called
 * from `MainViewController` at UI bring-up). Swallows platform failures so a host-less context
 * (unit tests / missing entitlement) stays inert.
 */
fun installPushTapDelegate() {
    if (installedDelegate != null) return
    try {
        val delegate = PushNotificationTapDelegate()
        UNUserNotificationCenter.currentNotificationCenter().delegate = delegate
        installedDelegate = delegate
    } catch (_: Throwable) {
        // No usable notification center (test host / missing config) — the tap path is inert.
    }
}
