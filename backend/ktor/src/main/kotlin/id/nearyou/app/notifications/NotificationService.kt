package id.nearyou.app.notifications

import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.NotificationRow
import java.time.Instant
import java.util.UUID

/**
 * Read-path orchestration for `GET/PATCH /api/v1/notifications`. Pure thin
 * wrapper around [NotificationRepository] — services adds the probe-row
 * pagination trick (page_size + 1 fetch → truncate + next cursor) and the
 * idempotent already-read branch on `markRead`.
 */
class NotificationService(
    private val notifications: NotificationRepository,
) {
    fun list(
        userId: UUID,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        unreadOnly: Boolean,
        limit: Int,
    ): NotificationPage {
        val rows =
            notifications.listByUser(
                userId = userId,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
                unreadOnly = unreadOnly,
                limit = limit + 1,
            )
        return if (rows.size > limit) {
            val page = rows.take(limit)
            val last = page.last()
            NotificationPage(
                rows = page,
                nextCursor = NotificationCursor(createdAt = last.createdAt, id = last.id),
            )
        } else {
            NotificationPage(rows = rows, nextCursor = null)
        }
    }

    fun unreadCount(userId: UUID): Long = notifications.countUnread(userId)

    /**
     * Returns [MarkReadOutcome.Acknowledged] for both "now flipped unread → read"
     * and "already read"; callers return 204 in both cases (idempotent).
     * Returns [MarkReadOutcome.NotFound] when the id does not belong to the
     * caller or does not exist.
     */
    fun markRead(
        userId: UUID,
        notificationId: UUID,
    ): MarkReadOutcome {
        val affected = notifications.markRead(userId, notificationId)
        if (affected > 0) return MarkReadOutcome.Acknowledged
        // 0 rows: either already-read (exists for user) or not found (does not exist / other user).
        return if (notifications.existsForUser(userId, notificationId)) {
            MarkReadOutcome.Acknowledged
        } else {
            MarkReadOutcome.NotFound
        }
    }

    fun markAllRead(userId: UUID): Int = notifications.markAllRead(userId)

    sealed interface MarkReadOutcome {
        data object Acknowledged : MarkReadOutcome

        data object NotFound : MarkReadOutcome
    }
}

data class NotificationPage(
    val rows: List<NotificationRow>,
    val nextCursor: NotificationCursor?,
)

data class NotificationCursor(
    val createdAt: Instant,
    val id: UUID,
)
