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
 *  - Block: if [actorUserId] is non-null and either direction of `user_blocks`
 *    has a row between actor and recipient, the emit is skipped (write-time
 *    suppression per design Decision 2). System-originated emits with
 *    `actor == null` (auto-hide) skip the block check entirely.
 *
 * Returns the inserted notification's id on success, or `null` if the emit
 * was suppressed. Dispatch is intentionally NOT called here — the caller
 * (service layer) owns the commit boundary and dispatches post-commit so a
 * future non-noop dispatcher (FCM) never observes a notification that ended
 * up rolled back.
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
    ): UUID?
}

/**
 * Default [NotificationEmitter] impl: suppress → INSERT. The INSERT happens
 * on the passed [Connection] inside the caller's transaction. Dispatch is the
 * caller's responsibility (post-commit) — see the interface KDoc.
 */
class DbNotificationEmitter(
    private val notifications: NotificationRepository,
) : NotificationEmitter {
    override fun emit(
        conn: Connection,
        recipientId: UUID,
        actorUserId: UUID?,
        type: NotificationType,
        targetType: String?,
        targetId: UUID?,
        bodyData: JsonObject,
    ): UUID? {
        if (actorUserId != null && actorUserId == recipientId) {
            log.debug(
                "event=notification_suppressed type={} recipient={} reason=self_action",
                type.wire,
                recipientId,
            )
            return null
        }
        if (actorUserId != null && notifications.isBlockedBetween(conn, actorUserId, recipientId)) {
            log.debug(
                "event=notification_suppressed type={} recipient={} actor={} reason=blocked",
                type.wire,
                recipientId,
                actorUserId,
            )
            return null
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
        return id
    }

    companion object {
        private val log = LoggerFactory.getLogger(DbNotificationEmitter::class.java)
    }
}
