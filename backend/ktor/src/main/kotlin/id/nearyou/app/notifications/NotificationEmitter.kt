package id.nearyou.app.notifications

import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.NotificationType
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Transactional emit seam for the four V10 write-paths (like, reply, follow,
 * auto-hide). The emitter runs inside the primary action's open transaction
 * (see in-app-notifications design Decision 1): block-check + INSERT on the
 * same [Connection] means the notification row commits with the primary write
 * or rolls back with it.
 *
 * Suppression rules enforced here:
 *  - Self-action: `actor == recipient` returns without inserting (defense-in-
 *    depth; the V6 self-follow CHECK constraint, V7 post-likes PK, V8
 *    post_replies FK shape all fail self-actions upstream, but the notification
 *    layer also filters to be safe).
 *  - Block: if [actor] is non-null and either direction of `user_blocks` has a
 *    row between actor and recipient, the emit is skipped (write-time
 *    suppression per design Decision 2). System-originated emits with
 *    `actor == null` (auto-hide) skip the block check entirely.
 *
 * After a successful INSERT, the emitter calls [NotificationDispatcher.dispatch]
 * — V10 dispatcher is a no-op, but the seam keeps the FCM change a drop-in.
 */
interface NotificationEmitter {
    fun emit(
        conn: Connection,
        recipientId: UUID,
        actorUserId: UUID?,
        type: NotificationType,
        targetType: String?,
        targetId: UUID?,
        bodyData: JsonObject,
    )
}

/**
 * Default [NotificationEmitter] impl: suppress → INSERT → dispatch.
 *
 * Dispatch is called inline after the INSERT succeeds. The in-app-only
 * dispatcher is a no-op; a future FCM dispatcher may want to defer until
 * commit, which it can do via its own implementation — the interface
 * signature stays stable.
 */
class DbNotificationEmitter(
    private val notifications: NotificationRepository,
    private val dispatcher: NotificationDispatcher,
) : NotificationEmitter {
    override fun emit(
        conn: Connection,
        recipientId: UUID,
        actorUserId: UUID?,
        type: NotificationType,
        targetType: String?,
        targetId: UUID?,
        bodyData: JsonObject,
    ) {
        if (actorUserId != null && actorUserId == recipientId) {
            log.debug(
                "event=notification_suppressed type={} recipient={} reason=self_action",
                type.wire,
                recipientId,
            )
            return
        }
        if (actorUserId != null && notifications.isBlockedBetween(conn, actorUserId, recipientId)) {
            log.debug(
                "event=notification_suppressed type={} recipient={} actor={} reason=blocked",
                type.wire,
                recipientId,
                actorUserId,
            )
            return
        }
        val id =
            notifications.insert(
                conn = conn,
                recipientId = recipientId,
                type = type,
                actorUserId = actorUserId,
                targetType = targetType,
                targetId = targetId,
                bodyDataJson = bodyData.toString(),
            )
        log.info(
            "event=notification_emitted type={} recipient={} actor={} id={}",
            type.wire,
            recipientId,
            actorUserId,
            id,
        )
        dispatcher.dispatch(id)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DbNotificationEmitter::class.java)
    }
}
