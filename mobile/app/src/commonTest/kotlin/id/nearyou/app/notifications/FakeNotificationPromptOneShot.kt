package id.nearyou.app.notifications

/**
 * A [NotificationPromptOneShot] test double. [claimed] stands in for the persisted per-install flag: start
 * it `true` to simulate a relaunch after the rationale was already shown on this install.
 */
class FakeNotificationPromptOneShot(
    var claimed: Boolean = false,
) : NotificationPromptOneShot {
    override suspend fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}
