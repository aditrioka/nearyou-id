package id.nearyou.app.infra.repo

import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.ReportTargetType
import java.sql.Connection
import java.util.UUID

/**
 * JDBC implementation of [ModerationQueueRepository]. Three writers wired:
 *  - V9 `auto_hide_3_reports` (3-reports-cross-threshold).
 *  - `uu_ite_keyword_match` (Layer 2 soft-flag from `content-moderation-keyword-lists`).
 *  - `username_flagged` (Premium username-change moderation hit).
 *
 * The first two share the `(target_type, target_id, trigger)` UNIQUE constraint with
 * ON CONFLICT DO NOTHING so concurrent racers and retries collapse to a single row.
 * `username_flagged` uses ON CONFLICT DO UPDATE (re-open + persist latest candidate;
 * `admin-premium-username-oversight` Decision 6) — its returned `true`/`false` is
 * derived from `xmax = 0` (a freshly-inserted tuple has `xmax = 0`; the DO UPDATE
 * path sees a non-zero `xmax`), since a DO UPDATE counts as an affected row and so
 * `executeUpdate() == 1` cannot distinguish insert from update.
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

    override fun upsertUsernameFlaggedRow(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
        candidate: String,
    ): Boolean {
        // ON CONFLICT DO UPDATE re-opens the standing flag and refreshes the candidate
        // (Decision 6). `RETURNING (xmax = 0)` distinguishes a fresh INSERT (xmax = 0)
        // from the DO UPDATE branch (xmax != 0) — executeUpdate() returns 1 for both.
        conn.prepareStatement(
            """
            INSERT INTO moderation_queue (target_type, target_id, trigger, notes)
            VALUES (?, ?, 'username_flagged', ?)
            ON CONFLICT (target_type, target_id, trigger)
            DO UPDATE SET status = 'pending', resolution = NULL, resolved_by = NULL,
                          resolved_at = NULL, notes = EXCLUDED.notes, created_at = NOW()
            RETURNING (xmax = 0) AS inserted
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, targetType.wire)
            ps.setObject(2, targetId)
            ps.setString(3, candidate)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "INSERT … RETURNING returned no row" }
                return rs.getBoolean("inserted")
            }
        }
    }

    override fun upsertCsamDetectedRow(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Boolean {
        conn.prepareStatement(
            """
            INSERT INTO moderation_queue (target_type, target_id, trigger)
            VALUES (?, ?, 'csam_detected')
            ON CONFLICT (target_type, target_id, trigger) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, targetType.wire)
            ps.setObject(2, targetId)
            return ps.executeUpdate() == 1
        }
    }

    override fun upsertAreaSpamRow(
        conn: Connection,
        targetType: ReportTargetType,
        targetId: UUID,
    ): Boolean {
        conn.prepareStatement(
            """
            INSERT INTO moderation_queue (target_type, target_id, trigger)
            VALUES (?, ?, 'area_spam')
            ON CONFLICT (target_type, target_id, trigger) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, targetType.wire)
            ps.setObject(2, targetId)
            return ps.executeUpdate() == 1
        }
    }
}
