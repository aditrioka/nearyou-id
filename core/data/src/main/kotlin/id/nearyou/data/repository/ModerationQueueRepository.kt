package id.nearyou.data.repository

import java.sql.Connection
import java.util.UUID

/**
 * Moderation-queue writer — V9 produces exactly one trigger value
 * (`auto_hide_3_reports`) into this table. Read path (admin dashboard) is deferred
 * to the Phase 3.5 admin-panel change.
 */
interface ModerationQueueRepository {
    /**
     * `INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES
     * (?, ?, 'auto_hide_3_reports') ON CONFLICT (target_type, target_id, trigger)
     * DO NOTHING`.
     *
     * Returns `true` when a new row was inserted (threshold freshly crossed),
     * `false` when the ON CONFLICT branch fired (queue row already existed —
     * 4th/5th reporter, or concurrent race that lost).
     *
     * Priority deliberately not customized — the schema default (5) is
     * authoritative for V9 per the `moderation-queue` spec.
     */
    fun upsertAutoHideRow(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Boolean
}
