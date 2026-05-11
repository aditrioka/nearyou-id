package id.nearyou.data.repository

import java.util.UUID

/**
 * Transactional Layer 3 DB writer for the `text-moderation-perspective-api-layer`
 * capability. Each helper opens its own SQL transaction and rolls back atomically
 * on failure.
 *
 * The `targetType` argument is constrained to [ReportTargetType.POST] /
 * [ReportTargetType.REPLY] — Layer 3 does NOT apply to user / chat-message targets
 * in this capability (chat is explicitly deferred per design Open Question 1).
 * Implementations SHALL throw [IllegalArgumentException] for any other target type.
 *
 * The `score` argument is the per-call max-aggregated moderation score
 * (in `[0.0, 1.0]`). It is currently NOT persisted — the `moderation_queue`
 * schema has no `score` column; admin disambiguation between AutoHide and
 * FlagOnly outcomes uses the related row's `is_auto_hidden` state per design
 * Decision 10. The argument is preserved for forward compatibility and
 * structured-log emissions.
 *
 * **Vendor history**: the original spec planned Google Perspective API. The
 * SQL trigger string `'perspective_api_high_score'` is fixed in the V9 schema
 * `moderation_queue.trigger` CHECK constraint enum — it persists as a historical
 * artifact even though the vendor pivoted to OpenAI Moderation mid-implementation
 * (renaming would require a Flyway migration; deferred to a future change).
 */
interface Layer3ModerationWriter {
    /**
     * AutoHide path (`Outcome.AutoHide`). Inside one SQL transaction with a single COMMIT:
     *  1. `UPDATE posts|post_replies SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL`
     *  2. `INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (?, ?, 'perspective_api_high_score') ON CONFLICT (target_type, target_id, trigger) DO NOTHING`
     *
     * If the UPDATE returns zero affected rows (soft-deleted target race per design
     * Decision 8), the INSERT is SKIPPED and the transaction is rolled back. The
     * caller observes [Layer3WriteResult.SoftDeletedTarget].
     */
    fun applyAutoHideAndQueue(
        targetType: ReportTargetType,
        targetId: UUID,
        score: Double,
    ): Layer3WriteResult

    /**
     * FlagOnly path (`Outcome.FlagOnly`). Inside one SQL transaction:
     *  1. `INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (?, ?, 'perspective_api_high_score') ON CONFLICT (target_type, target_id, trigger) DO NOTHING`
     *
     * No `is_auto_hidden` flip. Idempotent retry collapses via the ON CONFLICT clause.
     */
    fun applyFlagQueue(
        targetType: ReportTargetType,
        targetId: UUID,
        score: Double,
    ): Layer3WriteResult
}

/**
 * Outcome of a [Layer3ModerationWriter] invocation. Distinguishes the apply path
 * from the soft-delete-race no-op path so the orchestrator can emit the right
 * structured INFO log.
 */
sealed interface Layer3WriteResult {
    /** UPDATE+INSERT (or INSERT-only) completed; queue row written as expected. */
    data object Applied : Layer3WriteResult

    /** ON CONFLICT DO NOTHING fired; queue row pre-existed (idempotent retry). */
    data object QueueConflictNoOp : Layer3WriteResult

    /**
     * AutoHide path only: the UPDATE matched zero rows because the target was
     * soft-deleted between the user's INSERT and the async Layer 3 dispatch. The
     * INSERT was skipped to avoid orphan queue rows for tombstoned content.
     */
    data object SoftDeletedTarget : Layer3WriteResult
}
