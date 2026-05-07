package id.nearyou.app.infra.repo

import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.ReportTargetType
import java.sql.Connection
import java.util.UUID

/**
 * JDBC implementation of [ModerationQueueRepository]. Two writers wired:
 *  - V9 `auto_hide_3_reports` (3-reports-cross-threshold).
 *  - `uu_ite_keyword_match` (Layer 2 soft-flag from `content-moderation-keyword-lists`).
 *
 * Both share the `(target_type, target_id, trigger)` UNIQUE constraint with
 * ON CONFLICT DO NOTHING so concurrent racers and retries collapse to a single row.
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

    override fun upsertUuIteKeywordMatchRow(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Boolean {
        conn.prepareStatement(
            """
            INSERT INTO moderation_queue (target_type, target_id, trigger)
            VALUES (?, ?, 'uu_ite_keyword_match')
            ON CONFLICT (target_type, target_id, trigger) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, targetType.wire)
            ps.setObject(2, targetId)
            return ps.executeUpdate() == 1
        }
    }
}
