package id.nearyou.app.admin.appealreview

import id.nearyou.app.admin.auth.AdminAuditLogger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** A pending appeal projected for the admin review queue. */
data class AppealQueueRow(
    val id: UUID,
    val userId: UUID,
    val actionType: String,
    val appealText: String,
    val createdAt: Instant,
)

/** Typed result of an appeal decision (approve / reject). */
sealed interface AppealDecisionOutcome {
    /** The appeal transitioned `pending → approved | rejected` (+ the unban on approve) + one audit row. */
    data object Applied : AppealDecisionOutcome

    /** The appeal was already decided — benign no-op, no enforcement, no second audit row. */
    data object NoOpAlreadyResolved : AppealDecisionOutcome

    /** No appeal resolves to the id. */
    data object NotFound : AppealDecisionOutcome
}

/**
 * Write repository for the admin appeals-review surface (`admin-appeal-review`).
 * Backs `GET /admin/appeals` (pending queue), `POST /admin/appeals/{id}/approve`
 * (lifts the moderation action → unban), and `POST /admin/appeals/{id}/reject`.
 *
 * Admin module (`id.nearyou.app.admin.*`) — exempt from the `RawFromPostsRule` /
 * `BlockExclusionJoinRule` Detekt rules; raw `UPDATE users` is the sanctioned
 * admin moderation write (mirrors `ReportResolutionRepository`). NO destructive-
 * action rate limiter: approve is RESTORATIVE and reject alters no user state, so
 * neither is counted by `admin-destructive-action-rate-limit` (design D6).
 *
 * Idempotency + the two-admins race are serialized by the `SELECT … FOR UPDATE`
 * lock + the `WHERE status = 'pending'` precondition (the report-resolution
 * precedent): a re-decision of an already-decided appeal is a benign no-op with
 * no `users` write and no second audit row.
 */
class AppealReviewRepository(
    private val dataSource: DataSource,
    private val auditLogger: AdminAuditLogger,
) {
    /** Oldest-first page of pending appeals (the `appeals_pending_created_idx` scan path). */
    fun listPending(
        limit: Int,
        offset: Int,
    ): List<AppealQueueRow> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, user_id, action_type, appeal_text, created_at
                  FROM appeals
                 WHERE status = 'pending'
                 ORDER BY created_at ASC
                 LIMIT ? OFFSET ?
                """.trimIndent(),
            ).use { ps ->
                ps.setInt(1, limit)
                ps.setInt(2, offset)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AppealQueueRow(
                                    id = rs.getObject("id", UUID::class.java),
                                    userId = rs.getObject("user_id", UUID::class.java),
                                    actionType = rs.getString("action_type"),
                                    appealText = rs.getString("appeal_text"),
                                    createdAt = rs.getTimestamp("created_at").toInstant(),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /**
     * Approve a pending appeal: transition `pending → approved` (+ `reviewed_by` /
     * `reviewed_at`), LIFT the moderation action on the appellant (`is_banned =
     * FALSE`, `suspended_until = NULL` — the unban shape the suspension worker
     * applies), and write one `appeal_approved` audit row — all in ONE transaction.
     *
     * The unban UPDATE is unconditional on the user, so an appeal approved AFTER
     * the daily unban worker already cleared `is_banned` still transitions cleanly
     * (the re-set to FALSE is a harmless no-op). `token_version` is never modified
     * (mirrors the shipped suspend/unban).
     */
    fun approve(
        appealId: UUID,
        actingAdminId: UUID,
        ip: String,
        userAgent: String?,
    ): AppealDecisionOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val locked =
                    lockAppeal(conn, appealId) ?: run {
                        conn.rollback()
                        return AppealDecisionOutcome.NotFound
                    }
                if (locked.status != STATUS_PENDING) {
                    conn.rollback()
                    return AppealDecisionOutcome.NoOpAlreadyResolved
                }

                conn.prepareStatement(
                    "UPDATE appeals SET status = 'approved', reviewed_by = ?, reviewed_at = NOW() " +
                        "WHERE id = ? AND status = 'pending'",
                ).use { ps ->
                    ps.setObject(1, actingAdminId)
                    ps.setObject(2, appealId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("UPDATE users SET is_banned = FALSE, suspended_until = NULL WHERE id = ?").use { ps ->
                    ps.setObject(1, locked.userId)
                    ps.executeUpdate()
                }
                auditLogger.logAppealApproved(
                    conn = conn,
                    adminId = actingAdminId,
                    appealId = appealId,
                    beforeState = buildJsonObject { put("status", JsonPrimitive(STATUS_PENDING)) },
                    afterState =
                        buildJsonObject {
                            put("status", JsonPrimitive("approved"))
                            put("lifted_ban", JsonPrimitive(true))
                            put("user_id", JsonPrimitive(locked.userId.toString()))
                        },
                    ip = ip,
                    userAgent = userAgent,
                )

                conn.commit()
                AppealDecisionOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    /**
     * Reject a pending appeal: transition `pending → rejected` (+ optional
     * `decision_reason`, `reviewed_by` / `reviewed_at`) and write one
     * `appeal_rejected` audit row in ONE transaction. The moderation action is
     * LEFT INTACT (no `users` write) — the appellant stays banned/suspended.
     */
    fun reject(
        appealId: UUID,
        actingAdminId: UUID,
        decisionReason: String?,
        ip: String,
        userAgent: String?,
    ): AppealDecisionOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val locked =
                    lockAppeal(conn, appealId) ?: run {
                        conn.rollback()
                        return AppealDecisionOutcome.NotFound
                    }
                if (locked.status != STATUS_PENDING) {
                    conn.rollback()
                    return AppealDecisionOutcome.NoOpAlreadyResolved
                }

                conn.prepareStatement(
                    "UPDATE appeals SET status = 'rejected', decision_reason = ?, reviewed_by = ?, reviewed_at = NOW() " +
                        "WHERE id = ? AND status = 'pending'",
                ).use { ps ->
                    ps.setString(1, decisionReason)
                    ps.setObject(2, actingAdminId)
                    ps.setObject(3, appealId)
                    ps.executeUpdate()
                }
                auditLogger.logAppealRejected(
                    conn = conn,
                    adminId = actingAdminId,
                    appealId = appealId,
                    reason = decisionReason,
                    beforeState = buildJsonObject { put("status", JsonPrimitive(STATUS_PENDING)) },
                    afterState = buildJsonObject { put("status", JsonPrimitive("rejected")) },
                    ip = ip,
                    userAgent = userAgent,
                )

                conn.commit()
                AppealDecisionOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    /** `SELECT … FOR UPDATE` the appeal's user_id + status, locking the row for the tx. */
    private fun lockAppeal(
        conn: Connection,
        appealId: UUID,
    ): LockedAppeal? =
        conn.prepareStatement("SELECT user_id, status FROM appeals WHERE id = ? FOR UPDATE").use { ps ->
            ps.setObject(1, appealId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    LockedAppeal(
                        userId = rs.getObject("user_id", UUID::class.java),
                        status = rs.getString("status"),
                    )
                } else {
                    null
                }
            }
        }

    private data class LockedAppeal(
        val userId: UUID,
        val status: String,
    )

    private companion object {
        const val STATUS_PENDING = "pending"
    }
}
