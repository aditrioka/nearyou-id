package id.nearyou.app.infra.repo

import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.ReportTargetType
import java.sql.Connection
import java.util.UUID

/**
 * JDBC implementation of [ModerationQueueRepository]. Single write path for V9:
 * `auto_hide_3_reports` rows keyed by `(target_type, target_id, trigger)` with
 * ON CONFLICT DO NOTHING so concurrent racers and 4th/5th reporters are no-ops.
 */
class JdbcModerationQueueRepository : ModerationQueueRepository {
    override fun upsertAutoHideRow(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Boolean {
        conn.prepareStatement(
            """
            INSERT INTO moderation_queue (target_type, target_id, trigger)
            VALUES (?, ?, 'auto_hide_3_reports')
            ON CONFLICT (target_type, target_id, trigger) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, targetType.wire)
            ps.setObject(2, targetId)
            return ps.executeUpdate() == 1
        }
    }
}
