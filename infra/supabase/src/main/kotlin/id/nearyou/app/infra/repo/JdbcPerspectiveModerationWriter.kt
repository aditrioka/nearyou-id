package id.nearyou.app.infra.repo

import id.nearyou.data.repository.PerspectiveModerationWriter
import id.nearyou.data.repository.PerspectiveWriteResult
import id.nearyou.data.repository.ReportTargetType
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC-backed [PerspectiveModerationWriter]. Each helper opens its own connection
 * + transaction (the orchestrator does NOT supply a `Connection` — the writer
 * owns the transaction lifecycle per `text-moderation-perspective-api-layer/spec.md`
 * `### Requirement: AutoHide outcome flips is_auto_hidden and writes queue row in
 * one SQL transaction (with soft-delete guard)`).
 *
 * The `AND deleted_at IS NULL` predicate on the UPDATE matches the canonical V9
 * writer pattern at [`V9__reports_moderation.sql:36`](backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql).
 * If the UPDATE returns zero rows (soft-deleted target race per design Decision 8),
 * the INSERT is skipped AND the transaction is rolled back.
 */
class JdbcPerspectiveModerationWriter(
    private val dataSource: DataSource,
) : PerspectiveModerationWriter {
    override fun applyAutoHideAndQueue(
        targetType: ReportTargetType,
        targetId: UUID,
        score: Double,
    ): PerspectiveWriteResult {
        require(targetType == ReportTargetType.POST || targetType == ReportTargetType.REPLY) {
            "Perspective Layer 3 only applies to POST / REPLY targets; got $targetType"
        }
        val updateSql =
            when (targetType) {
                ReportTargetType.POST ->
                    "UPDATE posts SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL"
                ReportTargetType.REPLY ->
                    "UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL"
                ReportTargetType.USER, ReportTargetType.CHAT_MESSAGE ->
                    error("unreachable — guarded by require() above")
            }
        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val updated =
                    conn.prepareStatement(updateSql).use { ps ->
                        ps.setObject(1, targetId)
                        ps.executeUpdate()
                    }
                if (updated == 0) {
                    // Soft-deleted-target race: the user voluntarily deleted their content
                    // between the INSERT and the async Layer 3 dispatch. Skip the queue INSERT
                    // to avoid orphan rows for tombstoned content; rollback the (otherwise
                    // empty) transaction. The orchestrator emits a structured INFO log.
                    conn.rollback()
                    return PerspectiveWriteResult.SoftDeletedTarget
                }
                val inserted =
                    conn.prepareStatement(QUEUE_INSERT_SQL).use { ps ->
                        ps.setString(1, targetType.wire)
                        ps.setObject(2, targetId)
                        ps.executeUpdate()
                    }
                conn.commit()
                return if (inserted == 1) PerspectiveWriteResult.Applied else PerspectiveWriteResult.QueueConflictNoOp
            } catch (t: Throwable) {
                try {
                    conn.rollback()
                } catch (_: SQLException) {
                    // Defense in depth — if rollback also fails, surface the original cause.
                }
                throw t
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun applyFlagQueue(
        targetType: ReportTargetType,
        targetId: UUID,
        score: Double,
    ): PerspectiveWriteResult {
        require(targetType == ReportTargetType.POST || targetType == ReportTargetType.REPLY) {
            "Perspective Layer 3 only applies to POST / REPLY targets; got $targetType"
        }
        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val inserted =
                    conn.prepareStatement(QUEUE_INSERT_SQL).use { ps ->
                        ps.setString(1, targetType.wire)
                        ps.setObject(2, targetId)
                        ps.executeUpdate()
                    }
                conn.commit()
                return if (inserted == 1) PerspectiveWriteResult.Applied else PerspectiveWriteResult.QueueConflictNoOp
            } catch (t: Throwable) {
                try {
                    conn.rollback()
                } catch (_: SQLException) {
                    // Defense in depth.
                }
                throw t
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    companion object {
        internal const val QUEUE_INSERT_SQL: String =
            "INSERT INTO moderation_queue (target_type, target_id, trigger) " +
                "VALUES (?, ?, 'perspective_api_high_score') " +
                "ON CONFLICT (target_type, target_id, trigger) DO NOTHING"
    }
}
