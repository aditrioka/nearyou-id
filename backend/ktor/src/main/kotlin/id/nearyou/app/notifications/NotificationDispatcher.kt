package id.nearyou.app.notifications

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Delivery-channel seam for notifications. V10 ships the in-app only — the DB
 * row is the source of truth per docs/05-Implementation.md:849. The FCM
 * implementation is a separate change (Phase 2 item 7) and will swap in its
 * own binding without touching [NotificationEmitter] or any of the four
 * emit-site services.
 */
fun interface NotificationDispatcher {
    fun dispatch(notificationId: UUID)
}

/**
 * Default (in-app-only) dispatcher. Logs at INFO and returns — no push. The
 * `NoopNotificationDispatcher` name is retained to mirror the
 * in-app-notifications task spec reference.
 */
class NoopNotificationDispatcher : NotificationDispatcher {
    override fun dispatch(notificationId: UUID) {
        log.info("event=notification_dispatched channel=noop id={}", notificationId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(NoopNotificationDispatcher::class.java)
    }
}
