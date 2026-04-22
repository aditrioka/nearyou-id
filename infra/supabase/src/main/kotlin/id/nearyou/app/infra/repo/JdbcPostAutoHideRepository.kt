package id.nearyou.app.infra.repo

import id.nearyou.data.repository.PostAutoHideRepository
import id.nearyou.data.repository.ReportTargetType
import java.sql.Connection
import java.util.UUID

/**
 * JDBC implementation of [PostAutoHideRepository]. Issues per-target-type UPDATEs
 * on `posts` / `post_replies` only. `user` / `chat_message` targets skip the
 * UPDATE entirely — neither table carries an `is_auto_hidden` column.
 *
 * The `deleted_at IS NULL` guard makes the UPDATE idempotent and safely skips
 * soft-deleted targets (the queue row is still inserted by the caller; admin
 * can still triage the tombstoned content).
 */
class JdbcPostAutoHideRepository : PostAutoHideRepository {
    override fun flipIsAutoHidden(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Int {
        val sql =
            when (targetType) {
                ReportTargetType.POST ->
                    "UPDATE posts SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL"
                ReportTargetType.REPLY ->
                    "UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL"
                ReportTargetType.USER, ReportTargetType.CHAT_MESSAGE -> return 0
            }
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, targetId)
            return ps.executeUpdate()
        }
    }
}
