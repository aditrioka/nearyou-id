package id.nearyou.app.admin.reportqueue

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.moderation.BanPrimitives
import id.nearyou.app.admin.moderation.SuspendEnforcement
import id.nearyou.app.admin.moderation.UserModerationRepository
import id.nearyou.app.admin.ratelimit.DestructiveActionRateLimiter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Write repository for the in-panel report-queue resolution actions
 * (`admin-report-queue-resolution-actions` — the write-back fast-follow to the
 * read-only `admin-report-queue` viewer). Backs `POST /admin/reports/{id}/resolve`
 * (report bookkeeping) + `POST /admin/moderation-queue/{id}/resolve` (queue
 * resolution that PERFORMS the named enforcement).
 *
 * Design references: `openspec/changes/admin-report-queue-resolution-actions/design.md`
 * D2 (resolution performs enforcement), D4 (one transaction: enforcement +
 * status write + audit (+ notification) commit-or-rollback together), D5
 * (idempotency via the `WHERE status = 'pending'` precondition, which also
 * serializes the two-admins race), D9 (the `moderation_queue_resolved`
 * `after_state` records the resolution + the enforcement effect).
 *
 *  - All queries are parameterized JDBC `PreparedStatement`s — never string-
 *    interpolated.
 *  - Raw `FROM`/`UPDATE` on `reports` / `moderation_queue` / `users` / `posts` /
 *    `post_replies` / `chat_messages` is permitted here: this is the admin
 *    module (package `id.nearyou.app.admin.*`), exempt from the
 *    `RawFromPostsRule` / `BlockExclusionJoinRule` / `CoordinateJitterRule`
 *    Detekt rules (admins moderate everything; per `CLAUDE.md` § Critical
 *    invariants + `openspec/project.md` § Coding Conventions). No `visible_*`
 *    view or block-exclusion join applies to admin moderation writes.
 *  - The `suspend_author_7d` resolution REUSES the shipped suspend path
 *    ([UserModerationRepository.applySuspendUserUpdate] + the sanitized
 *    [UserModerationRepository.insertSuspendNotification]) joined to THIS
 *    repository's transaction — there is no divergent second suspend
 *    implementation (design D4). `ban_author` mirrors the suspend transaction
 *    shape (single `users` UPDATE + notification) but is permanent + owner/admin-
 *    tier-gated; `shadow_ban_author` is a single `users` UPDATE with NO
 *    notification (stealth invariant). `token_version` is never modified (mirrors
 *    the shipped suspend/unban).
 */
class ReportResolutionRepository(
    private val dataSource: DataSource,
    private val auditLogger: AdminAuditLogger,
    private val userModerationRepository: UserModerationRepository,
    private val rateLimiter: DestructiveActionRateLimiter,
) {
    /**
     * Transition a `reports` row `pending → actioned | dismissed` with
     * `reviewed_by` (the acting session admin) + `reviewed_at` and write exactly
     * one `report_resolved` audit row, all in ONE transaction. Bookkeeping only —
     * performs NO user/content enforcement (that is [resolveQueueItem]'s role).
     *
     * Idempotent (design D5): the conditional `WHERE status = 'pending'` makes a
     * re-resolution of a non-`pending` report a benign no-op
     * ([ReportDecisionOutcome.NoOpAlreadyResolved]) with no audit row; this also
     * serializes the two-admins-resolve-the-same-report race (the loser is a
     * no-op — the first decision wins).
     */
    fun resolveReport(
        reportId: UUID,
        decision: ReportDecision,
        actingAdminId: UUID,
        ip: String,
        userAgent: String?,
    ): ReportDecisionOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val currentStatus =
                    lockReportStatus(conn, reportId) ?: run {
                        conn.rollback()
                        return ReportDecisionOutcome.NotFound
                    }
                if (currentStatus != STATUS_PENDING) {
                    conn.rollback()
                    return ReportDecisionOutcome.NoOpAlreadyResolved
                }

                conn.prepareStatement(
                    "UPDATE reports SET status = ?, reviewed_by = ?, reviewed_at = NOW() " +
                        "WHERE id = ? AND status = 'pending'",
                ).use { ps ->
                    ps.setString(1, decision.wire)
                    ps.setObject(2, actingAdminId)
                    ps.setObject(3, reportId)
                    ps.executeUpdate()
                }
                auditLogger.logReportResolved(
                    conn = conn,
                    adminId = actingAdminId,
                    reportId = reportId,
                    beforeState = statusJson(STATUS_PENDING),
                    afterState = statusJson(decision.wire),
                    ip = ip,
                    userAgent = userAgent,
                )

                conn.commit()
                ReportDecisionOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    /**
     * Resolve a `moderation_queue` item `pending → resolved` (+ `resolution` +
     * `resolved_by`/`resolved_at`), PERFORM the enforcement the [resolution]
     * names, and write exactly one `moderation_queue_resolved` audit row
     * (whose `after_state` records the resolution + the enforcement effect,
     * design D9) — all in ONE transaction (design D4). Any failure rolls back
     * everything: no enforcement without its audit row, no queue marked
     * `resolved` without the enforcement.
     *
     * Guard / rejection outcomes write NOTHING (the queue stays `pending`):
     *  - content `keep`/`hide` on a non-`{post,reply}` target →
     *    [QueueResolutionOutcome.RejectedContentNotApplicable];
     *  - an author action whose author can't be resolved (hard-deleted target) →
     *    [QueueResolutionOutcome.RejectedAuthorUnresolvable];
     *  - `suspend_author_7d` hitting a suspend guard (soft-deleted / already
     *    permanently banned) → [QueueResolutionOutcome.RejectedSuspendGuard];
     *  - `ban_author` by a `moderator` → [QueueResolutionOutcome.ForbiddenBanTier].
     *
     * Idempotent + race-serialized via the `WHERE status = 'pending'`
     * precondition under the `FOR UPDATE` lock (design D5): a non-`pending` row
     * is [QueueResolutionOutcome.NoOpAlreadyResolved] — no enforcement re-
     * performed, no second audit row.
     */
    fun resolveQueueItem(
        queueId: UUID,
        resolution: QueueResolution,
        actingAdminId: UUID,
        actingAdminRole: String,
        ip: String,
        userAgent: String?,
    ): QueueResolutionOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val queue =
                    lockQueueRow(conn, queueId) ?: run {
                        conn.rollback()
                        return QueueResolutionOutcome.NotFound
                    }
                if (queue.status != STATUS_PENDING) {
                    conn.rollback()
                    return QueueResolutionOutcome.NoOpAlreadyResolved
                }

                // Destructive-action cap (admin-destructive-action-rate-limit):
                // the punitive author resolutions enforce the ±20/hr per-admin
                // cap; content keep/hide are NOT gated. At/over the cap, reject
                // with the queue left `pending`, no enforcement, no audit row.
                if (resolution in DESTRUCTIVE_RESOLUTIONS && rateLimiter.isAtOrOverCap(conn, actingAdminId)) {
                    conn.rollback()
                    return QueueResolutionOutcome.RateLimited
                }

                // Perform the enforcement; `effect` carries the after_state fields
                // describing what was enforced. A guard rejection rolls back and
                // returns its outcome (no queue mutation, no audit row).
                val effect: JsonObject =
                    when (resolution) {
                        QueueResolution.KEEP, QueueResolution.HIDE -> {
                            if (queue.targetType !in CONTENT_TARGET_TYPES) {
                                conn.rollback()
                                return QueueResolutionOutcome.RejectedContentNotApplicable
                            }
                            val hidden = resolution == QueueResolution.HIDE
                            applyContentVisibility(conn, queue.targetType, queue.targetId, hidden)
                            buildJsonObject { put("is_auto_hidden", JsonPrimitive(hidden)) }
                        }

                        QueueResolution.SUSPEND_AUTHOR_7D,
                        QueueResolution.BAN_AUTHOR,
                        QueueResolution.SHADOW_BAN_AUTHOR,
                        -> {
                            val authorId =
                                resolveAuthor(conn, queue.targetType, queue.targetId) ?: run {
                                    conn.rollback()
                                    return QueueResolutionOutcome.RejectedAuthorUnresolvable
                                }
                            when (resolution) {
                                QueueResolution.SUSPEND_AUTHOR_7D ->
                                    when (val e = userModerationRepository.applySuspendUserUpdate(conn, authorId)) {
                                        SuspendEnforcement.NotFound -> {
                                            conn.rollback()
                                            return QueueResolutionOutcome.RejectedAuthorUnresolvable
                                        }
                                        SuspendEnforcement.RejectedSoftDeleted,
                                        SuspendEnforcement.RejectedPermanentBan,
                                        -> {
                                            conn.rollback()
                                            return QueueResolutionOutcome.RejectedSuspendGuard
                                        }
                                        is SuspendEnforcement.Applied -> {
                                            userModerationRepository.insertSuspendNotification(
                                                conn,
                                                authorId,
                                                e.newSuspendedUntil,
                                            )
                                            buildJsonObject {
                                                put("is_banned", JsonPrimitive(true))
                                                put("suspended_until", JsonPrimitive(e.newSuspendedUntil.toString()))
                                            }
                                        }
                                    }

                                QueueResolution.BAN_AUTHOR -> {
                                    // Permanent ban is owner/admin-only (design D2),
                                    // mirroring the permanent-ban-unban tier — checked
                                    // INSIDE the transaction so a moderator rejection
                                    // writes nothing.
                                    if (actingAdminRole == UserModerationRepository.MODERATOR_ROLE) {
                                        conn.rollback()
                                        return QueueResolutionOutcome.ForbiddenBanTier
                                    }
                                    BanPrimitives.applyPermanentBan(conn, authorId)
                                    BanPrimitives.insertBanNotification(conn, authorId)
                                    buildJsonObject {
                                        put("is_banned", JsonPrimitive(true))
                                        put("suspended_until", JsonNull)
                                    }
                                }

                                QueueResolution.SHADOW_BAN_AUTHOR -> {
                                    // Stealth invariant (design D2 matrix + D9): NO
                                    // notification — a shadow ban is invisible to the
                                    // affected user.
                                    BanPrimitives.applyShadowBan(conn, authorId)
                                    buildJsonObject { put("is_shadow_banned", JsonPrimitive(true)) }
                                }

                                // Unreachable: outer `when` already narrowed to the
                                // three author resolutions.
                                QueueResolution.KEEP, QueueResolution.HIDE ->
                                    error("content resolution in author branch")
                            }
                        }
                    }

                conn.prepareStatement(
                    "UPDATE moderation_queue SET status = 'resolved', resolution = ?, " +
                        "resolved_by = ?, resolved_at = NOW() WHERE id = ? AND status = 'pending'",
                ).use { ps ->
                    ps.setString(1, resolution.wire)
                    ps.setObject(2, actingAdminId)
                    ps.setObject(3, queueId)
                    ps.executeUpdate()
                }

                val afterState =
                    buildJsonObject {
                        put("resolution", JsonPrimitive(resolution.wire))
                        effect.forEach { (k, v) -> put(k, v) }
                    }
                auditLogger.logModerationQueueResolved(
                    conn = conn,
                    adminId = actingAdminId,
                    queueId = queueId,
                    beforeState =
                        buildJsonObject {
                            put("status", JsonPrimitive(STATUS_PENDING))
                            put("resolution", JsonNull)
                        },
                    afterState = afterState,
                    ip = ip,
                    userAgent = userAgent,
                )

                conn.commit()
                QueueResolutionOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    /** `SELECT … FOR UPDATE` the report's status, locking the row for the tx. */
    private fun lockReportStatus(
        conn: Connection,
        reportId: UUID,
    ): String? =
        conn.prepareStatement("SELECT status FROM reports WHERE id = ? FOR UPDATE").use { ps ->
            ps.setObject(1, reportId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("status") else null }
        }

    /** `SELECT … FOR UPDATE` the queue row, locking it so concurrent resolvers
     *  serialize (the loser sees `status = 'resolved'` → no-op). */
    private fun lockQueueRow(
        conn: Connection,
        queueId: UUID,
    ): QueueRow? =
        conn.prepareStatement(
            "SELECT target_type, target_id, status FROM moderation_queue WHERE id = ? FOR UPDATE",
        ).use { ps ->
            ps.setObject(1, queueId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    QueueRow(
                        targetType = rs.getString("target_type"),
                        targetId = rs.getObject("target_id", UUID::class.java),
                        status = rs.getString("status"),
                    )
                } else {
                    null
                }
            }
        }

    /**
     * Resolve the offending author from the queue row's target — the SAME
     * resolution the read viewer uses for the deep-link: `post`→author,
     * `reply`→author, `user`→self, `chat_message`→sender. Returns null when the
     * target row has been hard-deleted (no resolvable author) → the caller
     * rejects with [QueueResolutionOutcome.RejectedAuthorUnresolvable].
     */
    private fun resolveAuthor(
        conn: Connection,
        targetType: String,
        targetId: UUID,
    ): UUID? {
        val sql =
            when (targetType) {
                TARGET_POST -> "SELECT author_id AS uid FROM posts WHERE id = ?"
                TARGET_REPLY -> "SELECT author_id AS uid FROM post_replies WHERE id = ?"
                TARGET_USER -> "SELECT id AS uid FROM users WHERE id = ?"
                TARGET_CHAT_MESSAGE -> "SELECT sender_id AS uid FROM chat_messages WHERE id = ?"
                else -> return null
            }
        return conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, targetId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("uid", UUID::class.java) else null }
        }
    }

    /** Toggle `is_auto_hidden` on the `post`/`reply` target (the inverse of the
     *  V9-era application-level auto-hide writer in `ReportService`). */
    private fun applyContentVisibility(
        conn: Connection,
        targetType: String,
        targetId: UUID,
        hidden: Boolean,
    ) {
        val table = if (targetType == TARGET_REPLY) "post_replies" else "posts"
        conn.prepareStatement("UPDATE $table SET is_auto_hidden = ? WHERE id = ?").use { ps ->
            ps.setBoolean(1, hidden)
            ps.setObject(2, targetId)
            ps.executeUpdate()
        }
    }

    /** `{"status": "<value>"}` for the report-resolution audit before/after. */
    private fun statusJson(status: String): JsonObject = buildJsonObject { put("status", JsonPrimitive(status)) }

    /** A locked queue row's enforcement-relevant columns. */
    private data class QueueRow(
        val targetType: String,
        val targetId: UUID,
        val status: String,
    )

    companion object {
        private const val STATUS_PENDING = "pending"

        private const val TARGET_POST = "post"
        private const val TARGET_REPLY = "reply"
        private const val TARGET_USER = "user"
        private const val TARGET_CHAT_MESSAGE = "chat_message"

        /** Target types that carry an `is_auto_hidden` column (content actions
         *  apply only to these — `user`/`chat_message` are not applicable). */
        private val CONTENT_TARGET_TYPES = setOf(TARGET_POST, TARGET_REPLY)

        /** The punitive author resolutions gated by the destructive-action cap
         *  (`admin-destructive-action-rate-limit`); content keep/hide are NOT. */
        private val DESTRUCTIVE_RESOLUTIONS =
            setOf(
                QueueResolution.SUSPEND_AUTHOR_7D,
                QueueResolution.BAN_AUTHOR,
                QueueResolution.SHADOW_BAN_AUTHOR,
            )
    }
}

/**
 * The report-status decisions accepted by `POST /admin/reports/{id}/resolve`.
 * The route maps the `decision` form field onto this enum via [fromWire] BEFORE
 * the repository call (server-side allowlist, design D7) — anything else is
 * rejected without a DB write, so an out-of-enum value never reaches the
 * `reports.status` CHECK as a 5xx.
 */
enum class ReportDecision(val wire: String) {
    ACTIONED("actioned"),
    DISMISSED("dismissed"),
    ;

    companion object {
        fun fromWire(value: String?): ReportDecision? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The IN-SCOPE moderation-queue resolutions accepted by `POST
 * /admin/moderation-queue/{id}/resolve` (the `docs/07-Operations.md` § Core
 * Features in-row set: Dismiss/Hide/Suspend/Ban/Shadow-ban). The route maps the
 * `resolution` form field onto this enum via [fromWire] BEFORE the repository
 * call (server-side allowlist, design D7); the OUT-OF-SCOPE in-enum values
 * `delete` / `accept_flagged_username` / `reject_flagged_username` (and any
 * garbage) map to null → rejected with no DB write, so they never reach the
 * `moderation_queue.resolution` CHECK as a 5xx.
 */
enum class QueueResolution(val wire: String) {
    KEEP("keep"),
    HIDE("hide"),
    SUSPEND_AUTHOR_7D("suspend_author_7d"),
    BAN_AUTHOR("ban_author"),
    SHADOW_BAN_AUTHOR("shadow_ban_author"),
    ;

    companion object {
        fun fromWire(value: String?): QueueResolution? = entries.firstOrNull { it.wire == value }
    }
}

/** Typed result of [ReportResolutionRepository.resolveReport]. */
sealed interface ReportDecisionOutcome {
    /** The report transitioned `pending → actioned | dismissed` + one audit row. */
    data object Applied : ReportDecisionOutcome

    /** The report was already non-`pending` — benign no-op, no audit row. */
    data object NoOpAlreadyResolved : ReportDecisionOutcome

    /** No report resolves to the id. */
    data object NotFound : ReportDecisionOutcome
}

/** Typed result of [ReportResolutionRepository.resolveQueueItem]. */
sealed interface QueueResolutionOutcome {
    /** The queue item resolved + the enforcement applied + one audit row. */
    data object Applied : QueueResolutionOutcome

    /** The queue row was already `resolved` — benign no-op, no enforcement, no
     *  second audit row. */
    data object NoOpAlreadyResolved : QueueResolutionOutcome

    /** No queue row resolves to the id. */
    data object NotFound : QueueResolutionOutcome

    /** A content action (`keep`/`hide`) on a `user`/`chat_message` target (no
     *  `is_auto_hidden` column) — not applicable; no write. */
    data object RejectedContentNotApplicable : QueueResolutionOutcome

    /** An author action whose author can't be resolved (hard-deleted target);
     *  no write. */
    data object RejectedAuthorUnresolvable : QueueResolutionOutcome

    /** `suspend_author_7d` hit a suspend guard (soft-deleted / already
     *  permanently banned); queue stays `pending`, no audit. */
    data object RejectedSuspendGuard : QueueResolutionOutcome

    /** `ban_author` attempted by a `moderator` (owner/admin tier only); no
     *  `users` write, queue stays `pending`, no audit. */
    data object ForbiddenBanTier : QueueResolutionOutcome

    /** Acting admin is at/over the destructive-action cap; queue stays `pending`,
     *  no enforcement, no audit row (`admin-destructive-action-rate-limit`). */
    data object RateLimited : QueueResolutionOutcome
}
