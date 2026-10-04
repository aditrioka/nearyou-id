package id.nearyou.app.admin.appealreview

import id.nearyou.app.admin.actionslog.ActionLogCursor
import id.nearyou.app.admin.auth.AdminAuditLogger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.sql.Timestamp
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

/** One page of the pending queue plus the cursor for the next-newer page (null = last page). */
data class AppealQueuePage(
    val rows: List<AppealQueueRow>,
    val nextCursor: ActionLogCursor?,
)

/** Typed result of an appeal decision (approve / reject). */
sealed interface AppealDecisionOutcome {
    /**
     * The appeal transitioned `pending → approved | rejected` (+ the unban on approve) + one audit row
     * + one `appeal_decided` notification.
     */
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
 * no `users` write, no second audit row, and no second notification.
 *
 * Each applied decision also writes one `appeal_decided` notification for the
 * appellant (`appeal-decision-notification`) via the shipped admin notification
 * pattern — a RAW in-tx `INSERT INTO notifications` (like `account_action_applied`
 * / `chat_message_redacted`), NOT the social `NotificationEmitter`. In-app feed
 * only: nothing is handed to the push dispatcher, so no FCM push.
 */
class AppealReviewRepository(
    private val dataSource: DataSource,
    private val auditLogger: AdminAuditLogger,
) {
    /**
     * Oldest-first page of pending appeals (the `appeals_pending_created_idx` scan
     * path), keyset-paginated over `(created_at, id)` ASC — reusing the
     * [ActionLogCursor] codec, NOT a second one (the `admin-report-queue`
     * precedent). Fetches `pageSize + 1` rows: the extra row signals "there is a
     * newer page" and its predecessor becomes the next cursor — no `OFFSET`, no
     * total-count query (docs/11 §3).
     */
    fun listPending(
        pageSize: Int,
        cursor: ActionLogCursor?,
    ): AppealQueuePage {
        require(pageSize > 0) { "pageSize must be positive" }
        val sql =
            buildString {
                append("SELECT id, user_id, action_type, appeal_text, created_at\n  FROM appeals\n WHERE status = 'pending'")
                // Keyset "newer" predicate aligned with the ASC ordering; `id` breaks created_at ties.
                if (cursor != null) append("\n   AND (created_at, id) > (?, ?)")
                append("\n ORDER BY created_at ASC, id ASC\n LIMIT ?")
            }
        val fetched =
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    var i = 1
                    if (cursor != null) {
                        ps.setTimestamp(i++, Timestamp.from(cursor.createdAt))
                        ps.setObject(i++, cursor.id)
                    }
                    ps.setInt(i, pageSize + 1)
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
        val rows = fetched.take(pageSize)
        val nextCursor = if (fetched.size > pageSize) rows.last().let { ActionLogCursor(it.createdAt, it.id) } else null
        return AppealQueuePage(rows = rows, nextCursor = nextCursor)
    }

    /**
     * Approve a pending appeal: transition `pending → approved` (+ `reviewed_by` /
     * `reviewed_at`), LIFT the moderation action on the appellant (`is_banned =
     * FALSE`, `suspended_until = NULL` — the unban shape the suspension worker
     * applies), write one `appeal_approved` audit row, and insert one `appeal_decided`
     * notification for the appellant — all in ONE transaction.
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
                insertDecisionNotification(conn, locked.userId, appealId, decision = "approved")

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
     * `decision_reason`, `reviewed_by` / `reviewed_at`), write one
     * `appeal_rejected` audit row, and insert one `appeal_decided` notification in
     * ONE transaction. The moderation action is LEFT INTACT (no `users` write) —
     * the appellant stays banned/suspended (they see the row once access returns).
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
                insertDecisionNotification(conn, locked.userId, appealId, decision = "rejected")

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
     * Insert the appellant's `appeal_decided` notification on [conn] (joins the
     * decision transaction). `(target_type, target_id) = ('appeal', appealId)` is
     * the deep-link address; `body_data` is exactly `{"decision": …}` — never the
     * admin's free-text `decision_reason` (the appellant reads it through the
     * own-status read), never the appeal id (it is `target_id`). `actor_user_id`
     * stays NULL (the actor is an admin, not a `public.users` row).
     */
    private fun insertDecisionNotification(
        conn: Connection,
        userId: UUID,
        appealId: UUID,
        decision: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO notifications (user_id, type, target_type, target_id, body_data) " +
                "VALUES (?, 'appeal_decided', 'appeal', ?, ?::jsonb)",
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setObject(2, appealId)
            ps.setString(3, buildJsonObject { put("decision", JsonPrimitive(decision)) }.toString())
            ps.executeUpdate()
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
