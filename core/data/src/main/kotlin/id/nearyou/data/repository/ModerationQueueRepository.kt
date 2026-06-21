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

    /**
     * `INSERT INTO moderation_queue (target_type, target_id, trigger, notes) VALUES
     * (?, ?, 'username_flagged', ?) ON CONFLICT (target_type, target_id, trigger)
     * DO UPDATE SET status='pending', resolution=NULL, resolved_by=NULL,
     * resolved_at=NULL, notes=EXCLUDED.notes, created_at=NOW()`. Written by
     * `premium-username-customization` when a Premium username-change candidate trips
     * the profanity / UU-ITE moderation pipeline (and no admin override pre-approves
     * the candidate): the change is rejected upfront and a standing admin-awareness
     * row recording the flagged `candidate` in `notes` is left.
     *
     * One standing username-flag per user (V9's `(target_type, target_id, trigger)`
     * UNIQUE still holds — NOT one row per attempt), but unlike the prior
     * `DO NOTHING`, a 2nd+ flagged candidate **re-opens** the standing row: it resets
     * `status` to `'pending'`, clears any `resolution`/`resolved_by`/`resolved_at`,
     * refreshes `notes` to the latest flagged candidate, and re-stamps `created_at`.
     * This is intentional (`admin-premium-username-oversight` Decision 6) — a
     * previously-resolved flag must not silently swallow a new attempt, and the admin
     * always sees the latest flagged candidate. A still-unused per-candidate approval
     * is unaffected: it lives in the separate `username_flag_overrides` table, not on
     * this row.
     *
     * Callers pass `targetType = USER`, `targetId = <user id>`, and `candidate = the
     * flagged candidate string` (rendered HTML-escaped in the admin UI). Returns
     * `true` when a NEW row was inserted, `false` when the ON CONFLICT DO UPDATE
     * branch fired (the standing row already existed and was re-opened).
     */
    fun upsertUsernameFlaggedRow(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
        candidate: String,
    ): Boolean

    /**
     * `INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES
     * (?, ?, 'csam_detected') ON CONFLICT (target_type, target_id, trigger)
     * DO NOTHING`. Written by the `csam-detection` takedown for admin awareness of a
     * CSAM match (`targetType = POST`, the tombstoned post). Idempotent — a re-trigger
     * for the same post is a silent no-op, so the queue does not flood on repeated
     * webhook invocations. Returns `true` when a new row was inserted, `false` when
     * the ON CONFLICT branch fired.
     *
     * Caller MUST execute this in the takedown transaction so a rollback also rolls
     * back the queue row.
     */
    fun upsertCsamDetectedRow(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Boolean
}
