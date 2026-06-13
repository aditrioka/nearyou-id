package id.nearyou.app.notifications

/**
 * Compose-free, platform-free notification-permission status. Mirrors `LocationPermissionStatus`
 * (`mobile-location-permission-flow`): Android terminal-denial collapses into [DENIED]; a not-yet-asked
 * state is [NOT_DETERMINED] (the gate to show the `notif_permission_rationale` rationale once).
 */
enum class NotificationPermissionStatus {
    GRANTED,
    DENIED,
    NOT_DETERMINED,
}

/**
 * The commonMain seam for the OS notification-permission ceremony (`mobile-chat-screen` task 9.4), with
 * Android / iOS actuals bound in each `platformModule` — mirroring the `LocationPermissionController`
 * testability idiom (a `FakeNotificationPermissionController` drives tests). Platform-free by contract:
 * this interface references NO platform notification API.
 *
 * It is independent of FCM token registration + actual push delivery (deferred — Non-Goals): this only
 * surfaces the permission prompt so a user who later wires push has already granted it. On Android < 33
 * (no runtime notification permission) the actual reports [NotificationPermissionStatus.GRANTED].
 */
interface NotificationPermissionController {
    /** The current OS notification-permission status. Cheap and side-effect-free (shows no prompt). */
    suspend fun status(): NotificationPermissionStatus

    /** Fires the OS permission prompt and suspends until the user responds, returning the result. */
    suspend fun request(): NotificationPermissionStatus
}

/**
 * Per-install one-shot guard for the first-send notification-permission rationale (task 9.4). A Koin
 * `single` so the rationale + prompt fire at most once per process; combined with the OS de-duping the
 * actual system dialog after the user has answered, the effective behavior is "ask once". (Persisting
 * the flag across process restarts is folded into the FCM-registration follow-up — the OS dialog itself
 * never re-shows once answered, so the only imperfection is re-showing our rationale once per process.)
 */
class NotificationPromptOneShot {
    private var consumed = false

    /** Returns true exactly once (the first call), then false — the caller shows the rationale on true. */
    fun claim(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}
