package id.nearyou.app.notifications

/** A [NotificationPermissionController] test double — returns a scripted [status] and records [requestCount]. */
class FakeNotificationPermissionController(
    var status: NotificationPermissionStatus = NotificationPermissionStatus.GRANTED,
) : NotificationPermissionController {
    var requestCount: Int = 0
        private set

    override suspend fun status(): NotificationPermissionStatus = status

    override suspend fun request(): NotificationPermissionStatus {
        requestCount++
        return NotificationPermissionStatus.GRANTED
    }
}
