package id.nearyou.data.repository

import java.sql.Connection
import java.util.UUID

/**
 * Moderation-queue writer — produces queue rows for the seven trigger types
 * declared in V9's `moderation_queue.trigger` CHECK constraint. The two writers
 * currently bound:
 *  - V9 `auto_hide_3_reports` (3-reports-cross-threshold path).
 *  - `content-moderation-keyword-lists` `uu_ite_keyword_match` (Layer 2 soft-flag).
 *
 * Read path (admin dashboard) is deferred to the Phase 3.5 admin-panel change.
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

    /**
     * `INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES
     * (?, ?, 'uu_ite_keyword_match') ON CONFLICT (target_type, target_id, trigger)
     * DO NOTHING`. Idempotent — a retry of the same `(target_type, target_id)` from
     * a Layer 2 Flag verdict is a silent no-op.
     *
     * Returns `true` when a new row was inserted, `false` when the ON CONFLICT
     * branch fired (concurrent insert race or retry).
     *
     * Caller MUST execute this in the same transaction as the underlying content
     * INSERT (post / reply / chat-message) so a content-INSERT rollback also rolls
     * back the queue row — per the `### Requirement: TextModerator integration is
     * invoked AFTER existing length and rate-limit gates, BEFORE INSERT` Scenario
     * "Flag verdict writes moderation_queue row in the same transaction as INSERT".
     */
    fun upsertUuIteKeywordMatchRow(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Boolean
}
