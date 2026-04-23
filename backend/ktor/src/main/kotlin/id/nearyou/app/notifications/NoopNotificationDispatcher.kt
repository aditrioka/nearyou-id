package id.nearyou.app.notifications

import id.nearyou.data.repository.NotificationDispatcher
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Default (in-app-only) [NotificationDispatcher]. Logs at INFO and returns —
 * no external push. The FCM change will swap in its own implementation; this
 * file is the in-app no-op reference.
 *
 * The interface itself lives in `:core:data`
 * ([NotificationDispatcher][id.nearyou.data.repository.NotificationDispatcher])
 * so an `:infra:firebase` module can implement it without a reverse dep on
 * `:backend:ktor`.
 */
class NoopNotificationDispatcher : NotificationDispatcher {
    override fun dispatch(notificationId: UUID) {
        log.info("event=notification_dispatched channel=noop id={}", notificationId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(NoopNotificationDispatcher::class.java)
    }
}
