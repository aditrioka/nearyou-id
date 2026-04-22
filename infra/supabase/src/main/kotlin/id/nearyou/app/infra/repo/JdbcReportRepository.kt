package id.nearyou.app.infra.repo

import id.nearyou.data.repository.ReportReasonCategory
import id.nearyou.data.repository.ReportRepository
import id.nearyou.data.repository.ReportTargetType
import java.sql.Connection
import java.util.UUID

/**
 * JDBC implementation of [ReportRepository]. Intentionally lives in the infra
 * module so the SQL stays DB-agnostic at the `:core:data` interface.
 *
 * Point-lookup existence literals (`SELECT 1 FROM <table> WHERE id = ? AND
 * deleted_at IS NULL`) are deliberately raw reads against `posts`, `post_replies`,
 * `users`, `chat_messages` — NOT through `visible_posts`/`visible_users`. The
 * `RawFromPostsRule` allowlist exempts `Report*` files under `.../app/moderation/`;
 * this file lives outside `.../app/moderation/` (it's in `:infra:supabase`), so
 * it falls outside the rule's scan scope entirely (detekt is configured against
 * `:backend:ktor` sources only). See V9 proposal + design Decision 3.
 */
class JdbcReportRepository : ReportRepository {
    override fun targetExists(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Boolean {
        val sql =
            when (targetType) {
                ReportTargetType.POST -> "SELECT 1 FROM posts WHERE id = ? AND deleted_at IS NULL"
                ReportTargetType.REPLY -> "SELECT 1 FROM post_replies WHERE id = ? AND deleted_at IS NULL"
                ReportTargetType.USER -> "SELECT 1 FROM users WHERE id = ? AND deleted_at IS NULL"
                // chat_messages has no deleted_at column (MVP chat is ephemeral by design).
                ReportTargetType.CHAT_MESSAGE -> "SELECT 1 FROM chat_messages WHERE id = ?"
            }
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, targetId)
            ps.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    override fun insertReport(
        conn: Connection,
        reporterId: UUID,
        targetType: ReportTargetType,
        targetId: UUID,
        reasonCategory: ReportReasonCategory,
        reasonNote: String?,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO reports (reporter_id, target_type, target_id, reason_category, reason_note)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, reporterId)
            ps.setString(2, targetType.wire)
            ps.setObject(3, targetId)
            ps.setString(4, reasonCategory.wire)
            if (reasonNote != null) ps.setString(5, reasonNote) else ps.setNull(5, java.sql.Types.VARCHAR)
            ps.executeUpdate()
        }
    }

    override fun countDistinctAgedReporters(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Int {
        // Canonical query from docs/05-Implementation.md §777 — 7-day account-age
        // filter + deleted_at NULL filter, both applied at COUNT time (reports
        // spec Decision 4).
        conn.prepareStatement(
            """
            SELECT COUNT(DISTINCT r.reporter_id)
              FROM reports r
              JOIN users u ON u.id = r.reporter_id
             WHERE r.target_type = ?
               AND r.target_id = ?
               AND u.created_at < NOW() - INTERVAL '7 days'
               AND u.deleted_at IS NULL
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, targetType.wire)
            ps.setObject(2, targetId)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }
}
