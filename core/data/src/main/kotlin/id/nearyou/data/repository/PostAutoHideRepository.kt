package id.nearyou.data.repository

import java.sql.Connection
import java.util.UUID

/**
 * Writes `is_auto_hidden = TRUE` on the target when the V9 auto-hide threshold is
 * crossed. Only the `post` and `reply` target types have the column; the
 * `user` and `chat_message` variants return 0 rows affected (the queue row is
 * the sole admin-triage signal for those).
 *
 * The UPDATE is idempotent — the WHERE `deleted_at IS NULL` clause makes the
 * 4th/5th reporter a no-op if already TRUE (and a no-op if the target was soft-
 * deleted between INSERT and flip).
 */
interface PostAutoHideRepository {
    /**
     * Returns rows affected by the UPDATE — always 0 for `user` / `chat_message`
     * target types, 0 or 1 for `post` / `reply`. Caller uses the rowcount only
     * for observability; the side-effect (visible_posts filtering + V8 reply
     * author-bypass) is carried by the flag flip itself.
     */
    fun flipIsAutoHidden(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Int
}
