package id.nearyou.data.repository

import java.sql.Connection
import java.util.UUID

/**
 * Reports repository — target-existence lookup + INSERT + aged-reporter count, all
 * wired to run inside the same DB transaction as the auto-hide path.
 *
 * All methods take an explicit [Connection] so `ReportService` can run the full
 * flow (existence check → INSERT → COUNT → UPDATE → queue INSERT) inside a single
 * transaction and roll back cleanly on error.
 *
 * See the `reports` capability spec for semantics of each method.
 */
interface ReportRepository {
    /**
     * Point-lookup existence check on the target table per [targetType]. Runs
     * `SELECT 1 FROM <table> WHERE id = ? AND deleted_at IS NULL` (chat_messages
     * has no deleted_at column, so that guard is omitted there).
     *
     * Deliberately does NOT go through `visible_posts` and does NOT apply
     * bidirectional `user_blocks` exclusion — reporting a blocker / blocked /
     * shadow-banned / auto-hidden target is all legitimate. See
     * `visible-posts-view` spec delta shipped with V9.
     */
    fun targetExists(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Boolean

    /**
     * `INSERT INTO reports (...) VALUES (...)`. Throws `java.sql.SQLException`
     * with SQLSTATE `23505` on a UNIQUE `(reporter_id, target_type, target_id)`
     * violation — the HTTP layer maps that to 409 `duplicate_report`.
     */
    fun insertReport(
        conn: Connection,
        reporterId: UUID,
        targetType: ReportTargetType,
        targetId: UUID,
        reasonCategory: ReportReasonCategory,
        reasonNote: String?,
    )

    /**
     * `SELECT COUNT(DISTINCT r.reporter_id) FROM reports r JOIN users u ON
     * u.id = r.reporter_id WHERE r.target_type = ? AND r.target_id = ? AND
     * u.created_at < NOW() - INTERVAL '7 days' AND u.deleted_at IS NULL`.
     *
     * The 7-day filter + deleted_at filter are applied at COUNT time, not at
     * INSERT time (per `reports` spec Decision 4: the report row is always
     * written; a <7-day account or soft-deleted account simply doesn't count
     * toward the auto-hide threshold).
     */
    fun countDistinctAgedReporters(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Int
}

/** Closed enum mirroring the V9 `reports.target_type` CHECK constraint. */
enum class ReportTargetType(val wire: String) {
    POST("post"),
    REPLY("reply"),
    USER("user"),
    CHAT_MESSAGE("chat_message"),
    ;

    companion object {
        fun fromWire(raw: String): ReportTargetType? = entries.firstOrNull { it.wire == raw }
    }
}

/** Closed enum mirroring the V9 `reports.reason_category` CHECK constraint. */
enum class ReportReasonCategory(val wire: String) {
    SPAM("spam"),
    HATE_SPEECH_SARA("hate_speech_sara"),
    HARASSMENT("harassment"),
    ADULT_CONTENT("adult_content"),
    MISINFORMATION("misinformation"),
    SELF_HARM("self_harm"),
    CSAM_SUSPECTED("csam_suspected"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(raw: String): ReportReasonCategory? = entries.firstOrNull { it.wire == raw }
    }
}
