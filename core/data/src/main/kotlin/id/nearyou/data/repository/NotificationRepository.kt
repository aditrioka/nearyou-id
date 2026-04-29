package id.nearyou.data.repository

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Notifications repository — transactional INSERT on the write side plus the
 * caller-scoped read / mark-read surface consumed by `GET/PATCH
 * /api/v1/notifications`.
 *
 * Two-sided shape intentional:
 *  - Write-side methods ([insert], [isBlockedBetween]) take an explicit
 *    [Connection] so the INSERT rides the primary action's open transaction
 *    (like / reply / follow / auto-hide) per the in-app-notifications design
 *    Decision 1 (same-transaction coupling).
 *  - Read-side methods ([listByUser], [countUnread], [markRead],
 *    [markAllRead]) open their own connections from an injected DataSource —
 *    they are not transactional with any primary write.
 */
interface NotificationRepository {
    /**
     * `INSERT INTO notifications (user_id, type, actor_user_id, target_type,
     * target_id, body_data) VALUES (...)` on the provided transaction.
     * [bodyData] is a JSON object literal (kotlinx.serialization renders a
     * `String`; we pass through PGobject `jsonb`). Returns the new row id.
     */
    fun insert(
        conn: Connection,
        recipientId: UUID,
        type: NotificationType,
        actorUserId: UUID?,
        targetType: String?,
        targetId: UUID?,
        bodyDataJson: String,
    ): UUID

    /**
     * Bidirectional block-existence check against `user_blocks`. Returns `true`
     * when either `(blocker_id = :a AND blocked_id = :b)` or
     * `(blocker_id = :b AND blocked_id = :a)` is present. Callers skip the
     * emit when this returns true (write-time suppression — see design
     * Decision 2).
     */
    fun isBlockedBetween(
        conn: Connection,
        userA: UUID,
        userB: UUID,
    ): Boolean

    /**
     * Paginated list for [userId] in `created_at DESC` order. When
     * [unreadOnly] is true, the query hits `notifications_user_unread_idx`
     * (partial). Composite cursor on `(created_at, id)`: rows returned are
     * strictly before the cursor.
     */
    fun listByUser(
        userId: UUID,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        unreadOnly: Boolean,
        limit: Int,
    ): List<NotificationRow>

    /**
     * Index-only COUNT against `notifications_user_unread_idx` for the
     * caller's unread rows.
     */
    fun countUnread(userId: UUID): Long

    /**
     * `UPDATE notifications SET read_at = NOW() WHERE id = ? AND user_id = ?
     * AND read_at IS NULL`. Returns affected-row count (0 when already read,
     * not owned, or missing — idempotent).
     */
    fun markRead(
        userId: UUID,
        notificationId: UUID,
    ): Int

    /**
     * `UPDATE notifications SET read_at = NOW() WHERE user_id = ? AND read_at
     * IS NULL`. Returns affected-row count.
     */
    fun markAllRead(userId: UUID): Int

    /**
     * Existence check for the caller's notification by id. Used to
     * distinguish the 404 `not_found` case from the already-read idempotent
     * 204 on `PATCH /:id/read`.
     */
    fun existsForUser(
        userId: UUID,
        notificationId: UUID,
    ): Boolean

    /**
     * Single-row lookup by id. Used by `FcmDispatcher` (per the
     * `fcm-push-dispatch` capability) to resolve the row that the
     * post-commit dispatch contract refers to by UUID. Returns `null` when
     * the row no longer exists (e.g., the recipient was hard-deleted between
     * `NotificationService.emit(...)` commit and dispatch — CASCADE removed
     * the row; the dispatcher tolerates this gracefully).
     */
    fun findById(notificationId: UUID): NotificationRow?
}

/**
 * The 13 notification types from docs/05-Implementation.md §826–832. Values
 * serialize to the snake_case strings that match the V10 CHECK constraint.
 * V10 writes only `post_liked`, `post_replied`, `followed`, `post_auto_hidden`;
 * the remaining 9 are reserved for future feature changes.
 */
enum class NotificationType(val wire: String) {
    POST_LIKED("post_liked"),
    POST_REPLIED("post_replied"),
    FOLLOWED("followed"),
    CHAT_MESSAGE("chat_message"),
    SUBSCRIPTION_BILLING_ISSUE("subscription_billing_issue"),
    SUBSCRIPTION_EXPIRED("subscription_expired"),
    POST_AUTO_HIDDEN("post_auto_hidden"),
    ACCOUNT_ACTION_APPLIED("account_action_applied"),
    DATA_EXPORT_READY("data_export_ready"),
    CHAT_MESSAGE_REDACTED("chat_message_redacted"),
    PRIVACY_FLIP_WARNING("privacy_flip_warning"),
    USERNAME_RELEASE_SCHEDULED("username_release_scheduled"),
    APPLE_RELAY_EMAIL_CHANGED("apple_relay_email_changed"),
    ;

    companion object {
        /**
         * Returns the matching enum, or `null` if [value] is not one of the 13
         * known wire strings. Callers at the read boundary (e.g.
         * `JdbcNotificationRepository.listByUser`) use this to skip — rather
         * than crash on — rows with an unexpected `type`. This shields the list
         * endpoint from a DB-level enum widening that ships ahead of the
         * service code (or an unlikely manual INSERT bypassing the CHECK).
         */
        fun fromWireOrNull(value: String): NotificationType? = entries.firstOrNull { it.wire == value }
    }
}

data class NotificationRow(
    val id: UUID,
    val userId: UUID,
    val type: NotificationType,
    val actorUserId: UUID?,
    val targetType: String?,
    val targetId: UUID?,
    val bodyDataJson: String,
    val createdAt: Instant,
    val readAt: Instant?,
)
