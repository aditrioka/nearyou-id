package id.nearyou.app.admin.reportqueue

import id.nearyou.app.admin.actionslog.ActionLogCursor
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Read-only repository for the admin Report Queue (`admin-report-queue`
 * capability, Admin #6). Backs `GET /admin/reports`.
 *
 * Design references: `openspec/changes/admin-report-queue-viewer/design.md`
 * D2 (keyset pagination over `(created_at, id)` DESC — reusing the
 * `admin-actions-log-viewer` [ActionLogCursor] codec, NOT a second one),
 * D4 (`moderation_queue` context via a single-row `LEFT JOIN LATERAL`),
 * D5 (per-`target_type` author/sender resolution for the deep-link, LEFT so a
 * hard-deleted target degrades to no-link), and the parameterized composable
 * filter set.
 *
 *  - All queries via JDBC `PreparedStatement`; every filter value is bound
 *    positionally, NEVER string-interpolated — so a filter value carrying SQL
 *    metacharacters is treated as a literal, never executed.
 *  - Raw reads of `reports` / `moderation_queue` / `users` / the content base
 *    tables are permitted here: this is the admin module, exempt from the
 *    `RawFromPostsRule` / `BlockExclusionJoinRule` Detekt rules (per
 *    `openspec/project.md` § Coding Conventions — admin-module exemption by
 *    package). No `visible_*` view or block-exclusion join applies to admin
 *    moderation reads.
 *  - This repository exposes NO write path — the Report Queue is strictly
 *    read-only (the resolution write-back ships as `admin-report-queue-
 *    resolution-actions`).
 */
class ReportQueueRepository(
    private val dataSource: DataSource,
) {
    /**
     * Run the filtered + keyset-paginated query and return one page of rows
     * plus the cursor for the next-older page (null when there is none).
     *
     * Fetches `pageSize + 1` rows: the extra row (if present) signals "there
     * is an older page" and its predecessor becomes the next cursor — no
     * `OFFSET`, no total-count query (design.md D2).
     */
    fun query(q: ReportQueueQuery): ReportQueuePage {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()

        q.status?.let {
            conditions += "r.status = ?"
            params += it
        }
        q.targetType?.let {
            conditions += "r.target_type = ?"
            params += it
        }
        q.reasonCategory?.let {
            conditions += "r.reason_category = ?"
            params += it
        }
        q.trigger?.let {
            // The trigger filter is an EXISTS over `moderation_queue` keyed on
            // the report's `(target_type, target_id)` — DISTINCT from the
            // display LATERAL join. A report whose target has no queue row of
            // that trigger is excluded (design Open Question 3).
            conditions +=
                "EXISTS (SELECT 1 FROM moderation_queue mqf " +
                "WHERE mqf.target_type = r.target_type AND mqf.target_id = r.target_id AND mqf.trigger = ?)"
            params += it
        }
        q.fromInclusive?.let {
            conditions += "r.created_at >= ?"
            params += java.sql.Timestamp.from(it)
        }
        q.toExclusive?.let {
            conditions += "r.created_at < ?"
            params += java.sql.Timestamp.from(it)
        }
        q.cursor?.let {
            // Keyset "older" predicate: row-value comparison aligned with the
            // `created_at DESC, id DESC` ordering. `id` is the deterministic
            // tiebreaker for rows sharing an identical `created_at`.
            conditions += "(r.created_at, r.id) < (?, ?)"
            params += java.sql.Timestamp.from(it.createdAt)
            params += it.id
        }

        val sql =
            buildString {
                append(
                    """
                    SELECT r.id, r.created_at, r.target_type, r.target_id,
                           r.reason_category, r.reason_note, r.status,
                           r.reporter_id, reporter.username AS reporter_username,
                           mq.id AS queue_id,
                           mq.trigger AS queue_trigger, mq.priority AS queue_priority,
                           mq.status AS queue_status,
                           CASE r.target_type
                               WHEN 'user' THEN tu.id
                               WHEN 'post' THEN p.author_id
                               WHEN 'reply' THEN pr.author_id
                               WHEN 'chat_message' THEN cm.sender_id
                           END AS action_user_id
                      FROM reports r
                      LEFT JOIN users reporter ON reporter.id = r.reporter_id
                      LEFT JOIN LATERAL (
                          SELECT mqj.id, mqj.trigger, mqj.priority, mqj.status
                            FROM moderation_queue mqj
                           WHERE mqj.target_type = r.target_type
                             AND mqj.target_id = r.target_id
                           ORDER BY mqj.priority ASC, mqj.created_at DESC
                           LIMIT 1
                      ) mq ON TRUE
                      LEFT JOIN users tu        ON r.target_type = 'user'         AND tu.id = r.target_id
                      LEFT JOIN posts p         ON r.target_type = 'post'         AND p.id = r.target_id
                      LEFT JOIN post_replies pr ON r.target_type = 'reply'        AND pr.id = r.target_id
                      LEFT JOIN chat_messages cm ON r.target_type = 'chat_message' AND cm.id = r.target_id
                    """.trimIndent(),
                )
                if (conditions.isNotEmpty()) {
                    append("\n WHERE ")
                    append(conditions.joinToString(" AND "))
                }
                append("\n ORDER BY r.created_at DESC, r.id DESC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<ReportQueueRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        fetched +=
                            ReportQueueRow(
                                id = rs.getObject("id", UUID::class.java),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                targetType = rs.getString("target_type"),
                                targetId = rs.getObject("target_id", UUID::class.java),
                                reasonCategory = rs.getString("reason_category"),
                                reasonNote = rs.getString("reason_note"),
                                status = rs.getString("status"),
                                reporterId = rs.getObject("reporter_id", UUID::class.java),
                                reporterUsername = rs.getString("reporter_username"),
                                queueId = rs.getObject("queue_id", UUID::class.java),
                                queueTrigger = rs.getString("queue_trigger"),
                                queuePriority = rs.getObject("queue_priority") as? Number,
                                queueStatus = rs.getString("queue_status"),
                                actionUserId = rs.getObject("action_user_id", UUID::class.java),
                            )
                    }
                }
            }
        }

        val hasOlder = fetched.size > q.pageSize
        val display = if (hasOlder) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasOlder && display.isNotEmpty()) {
                display.last().let { ActionLogCursor(it.createdAt, it.id) }
            } else {
                null
            }
        return ReportQueuePage(rows = display, nextCursor = nextCursor)
    }

    companion object {
        /**
         * Fixed page size (design Open Question 2 — reuse the
         * `admin-actions-log-viewer` value for cross-page consistency).
         */
        const val PAGE_SIZE: Int = 50
    }
}

/**
 * A single rendered `reports` row joined to its reporter, an optional
 * representative `moderation_queue` row, and the resolved offending-user id
 * for the deep-link (null when the target was hard-deleted).
 */
data class ReportQueueRow(
    val id: UUID,
    val createdAt: Instant,
    val targetType: String,
    val targetId: UUID,
    val reasonCategory: String,
    val reasonNote: String?,
    val status: String,
    val reporterId: UUID,
    val reporterUsername: String?,
    val queueId: UUID?,
    val queueTrigger: String?,
    val queuePriority: Number?,
    val queueStatus: String?,
    val actionUserId: UUID?,
)

/** The active filter set + page size + optional keyset cursor for one query. */
data class ReportQueueQuery(
    val status: String? = null,
    val targetType: String? = null,
    val reasonCategory: String? = null,
    val trigger: String? = null,
    val fromInclusive: Instant? = null,
    val toExclusive: Instant? = null,
    val cursor: ActionLogCursor? = null,
    val pageSize: Int = ReportQueueRepository.PAGE_SIZE,
)

/** One page of results plus the cursor for the next-older page (null = last page). */
data class ReportQueuePage(
    val rows: List<ReportQueueRow>,
    val nextCursor: ActionLogCursor?,
)
