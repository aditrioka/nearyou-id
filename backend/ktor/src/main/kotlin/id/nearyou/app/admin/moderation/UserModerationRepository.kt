package id.nearyou.app.admin.moderation

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.ratelimit.DestructiveActionRateLimiter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository for the admin user-moderation actions (`admin-user-moderation`
 * capability — Admin #5, the panel's first state-changing action): the
 * target-user lookup + the atomic suspend / unban transactions.
 *
 * Design references: `openspec/changes/admin-suspend-unban-user-action/design.md`
 * D4 (single-transaction UPDATE + audit INSERT + suspend notification),
 * D6 (`before_state` / `after_state` built in Kotlin), D7 (suspend guards),
 * D8 (unban semantics), D9 (sanitized notification), D10 (UUID-first lookup).
 *
 *  - Every query is a parameterized JDBC `PreparedStatement` — never string-
 *    interpolated, so a `q` carrying SQL metacharacters is treated as a literal
 *    value, never executed.
 *  - Raw `FROM users` reads + `UPDATE users` writes are permitted here: this is
 *    the admin module (package `id.nearyou.app.admin.*`), exempt from the
 *    `RawFromPostsRule` / `BlockExclusionJoinRule` / `CoordinateJitterRule`
 *    Detekt rules (admins see + moderate everything; per `CLAUDE.md`
 *    § Critical invariants + `openspec/project.md` § Coding Conventions).
 *  - The suspend / unban transactions own a single JDBC connection with
 *    `autoCommit = false`; the audit INSERT (and, for suspend, the notification
 *    INSERT) join that connection via [AdminAuditLogger.logUserSuspended] /
 *    [AdminAuditLogger.logUserUnbanned] so the state change and its audit row
 *    commit or roll back together (design D4) — never a state change without
 *    its audit row, never a partial suspend.
 */
class UserModerationRepository(
    private val dataSource: DataSource,
    private val auditLogger: AdminAuditLogger,
    private val rateLimiter: DestructiveActionRateLimiter,
) {
    /**
     * Resolve [q] to a user: parse it as a UUID first (primary-key lookup);
     * if it is not a valid UUID, attempt an EXACT, case-sensitive `username`
     * match (design D10). Returns null when nothing matches — the route
     * renders that as an inline empty state (200), never a 404. Both branches
     * bind [q] as a parameter; the SQL text is chosen from two fixed literals,
     * never interpolated.
     */
    fun lookup(q: String): UserModerationState? {
        val asUuid = runCatching { UUID.fromString(q) }.getOrNull()
        return dataSource.connection.use { conn ->
            val sql =
                if (asUuid != null) {
                    "SELECT id, username, is_banned, suspended_until, deleted_at FROM users WHERE id = ?"
                } else {
                    "SELECT id, username, is_banned, suspended_until, deleted_at FROM users WHERE username = ?"
                }
            conn.prepareStatement(sql).use { ps ->
                if (asUuid != null) ps.setObject(1, asUuid) else ps.setString(1, q)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toState() else null }
            }
        }
    }

    /**
     * Apply a server-fixed 7-day suspension to an ELIGIBLE [targetId]
     * (`is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '7 days'`) in one
     * transaction, writing the `user_suspended` audit row + the sanitized
     * `account_action_applied` notification atomically.
     *
     * Guards (design D7), evaluated against a `SELECT … FOR UPDATE` snapshot:
     *  - soft-deleted (`deleted_at IS NOT NULL`) → [SuspendOutcome.RejectedSoftDeleted];
     *  - already permanently banned (`is_banned = TRUE AND suspended_until IS NULL`)
     *    → [SuspendOutcome.RejectedPermanentBan] (no silent downgrade to a 7-day window).
     *
     * The "already suspended" branch keys on `suspended_until IS NULL` (permanent)
     * vs non-null — NOT on `suspended_until > NOW()` — so an elapsed-but-unswept
     * time-bound suspension is still re-suspendable (clock resets; the prior
     * expiry is captured in `before_state`).
     *
     * A rejection rolls back and writes nothing. An injected audit / notification
     * failure rolls back the `users` UPDATE and rethrows (atomicity).
     */
    fun suspend(
        targetId: UUID,
        actingAdminId: UUID,
        reason: String?,
        ip: String,
        userAgent: String?,
    ): SuspendOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                // Destructive-action cap (admin-destructive-action-rate-limit):
                // at/over the cap, reject with no mutation + no audit row. Read on
                // this transaction's connection for a consistent ledger snapshot.
                if (rateLimiter.isAtOrOverCap(conn, actingAdminId)) {
                    conn.rollback()
                    return SuspendOutcome.RateLimited
                }
                val outcome =
                    when (val enforcement = applySuspendUserUpdate(conn, targetId)) {
                        SuspendEnforcement.NotFound -> SuspendOutcome.NotFound
                        SuspendEnforcement.RejectedSoftDeleted -> SuspendOutcome.RejectedSoftDeleted
                        SuspendEnforcement.RejectedPermanentBan -> SuspendOutcome.RejectedPermanentBan
                        is SuspendEnforcement.Applied -> {
                            auditLogger.logUserSuspended(
                                conn = conn,
                                adminId = actingAdminId,
                                targetUserId = targetId,
                                reason = reason,
                                beforeState = stateJson(enforcement.beforeIsBanned, enforcement.beforeSuspendedUntil),
                                afterState = stateJson(isBanned = true, suspendedUntil = enforcement.newSuspendedUntil),
                                ip = ip,
                                userAgent = userAgent,
                            )
                            insertSuspendNotification(conn, targetId, enforcement.newSuspendedUntil)
                            SuspendOutcome.Applied(enforcement.newSuspendedUntil)
                        }
                    }
                if (outcome is SuspendOutcome.Applied) conn.commit() else conn.rollback()
                outcome
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                // Restore the pool default before checkin — matches every other
                // transactional path in the codebase (CreatePostService,
                // ChatRepository, FollowService, SignupService, ReplyService,
                // LikeService). Defense-in-depth: the tx is already committed or
                // rolled back here, so there is no pending work to flush.
                runCatching { conn.autoCommit = true }
            }
        }

    /**
     * Issue a WARNING to [targetId] (`admin-user-moderation` — the warning
     * action): in ONE transaction write the `user_warned` audit row + the
     * sanitized `account_action_applied` notification (`body_data.action_type =
     * 'warning'`), mutating NO `users` moderation column. The free-text [reason]
     * is recorded ONLY in the audit row, never echoed to the warned user (design
     * D5, mirroring the suspend reason discipline).
     *
     * Outcomes (each writes nothing on rejection):
     *  - no user resolves to [targetId] → [WarnOutcome.NotFound];
     *  - target is soft-deleted (`deleted_at IS NOT NULL`) → [WarnOutcome.TargetDeleted];
     *  - acting admin at/over the destructive-action cap → [WarnOutcome.RateLimited].
     *
     * An injected audit / notification failure rolls back and rethrows (atomicity).
     */
    fun warn(
        targetId: UUID,
        actingAdminId: UUID,
        reason: String?,
        ip: String,
        userAgent: String?,
    ): WarnOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val current =
                    lockUser(conn, targetId) ?: run {
                        conn.rollback()
                        return WarnOutcome.NotFound
                    }
                if (current.deletedAt != null) {
                    conn.rollback()
                    return WarnOutcome.TargetDeleted
                }
                if (rateLimiter.isAtOrOverCap(conn, actingAdminId)) {
                    conn.rollback()
                    return WarnOutcome.RateLimited
                }

                auditLogger.logUserWarned(
                    conn = conn,
                    adminId = actingAdminId,
                    targetUserId = targetId,
                    reason = reason,
                    beforeState = buildJsonObject {},
                    afterState = buildJsonObject { put("warning_issued", JsonPrimitive(true)) },
                    ip = ip,
                    userAgent = userAgent,
                )
                insertWarnNotification(conn, targetId)

                conn.commit()
                WarnOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    /**
     * Insert the warning-side `account_action_applied` notification on [conn]
     * (joins the warn transaction). `body_data.action_type = 'warning'`; the
     * admin's free-text reason is DELIBERATELY absent (audit-only, design D5).
     * `actor_user_id` is left NULL (the actor is an admin, not a `users` row).
     */
    private fun insertWarnNotification(
        conn: Connection,
        targetId: UUID,
    ) {
        val bodyData = buildJsonObject { put("action_type", JsonPrimitive("warning")) }
        conn.prepareStatement(
            "INSERT INTO notifications (user_id, type, body_data) VALUES (?, 'account_action_applied', ?::jsonb)",
        ).use { ps ->
            ps.setObject(1, targetId)
            ps.setString(2, json.encodeToString(bodyData))
            ps.executeUpdate()
        }
    }

    /**
     * The shared 7-day-suspend USER-STATE enforcement on a caller-owned [conn]
     * (no commit/rollback, NO audit row, NO notification): `SELECT … FOR UPDATE`
     * the target, evaluate the suspend guards (soft-deleted →
     * [SuspendEnforcement.RejectedSoftDeleted]; already permanently banned →
     * [SuspendEnforcement.RejectedPermanentBan]; absent → [SuspendEnforcement.NotFound]),
     * and on a pass apply `is_banned = TRUE, suspended_until = NOW() + INTERVAL
     * '7 days'`, returning the prior state + the server-computed new expiry.
     *
     * Extracted so BOTH [suspend] (which then writes the `user_suspended` audit
     * row + the sanitized notification and commits) AND the report-queue
     * `suspend_author_7d` resolution (which instead writes ONE
     * `moderation_queue_resolved` audit row + the same notification, joining the
     * queue-status UPDATE in the SAME transaction — design D4) reuse one guard +
     * UPDATE path. There is no divergent second suspend implementation; the
     * guard semantics (`IS NULL` permanent-ban check, re-suspendable elapsed
     * window) are defined once, here. `token_version` is NOT modified (mirrors
     * the shipped suspend/unban).
     */
    internal fun applySuspendUserUpdate(
        conn: Connection,
        targetId: UUID,
    ): SuspendEnforcement {
        val current = lockUser(conn, targetId) ?: return SuspendEnforcement.NotFound
        if (current.deletedAt != null) return SuspendEnforcement.RejectedSoftDeleted
        if (current.isBanned && current.suspendedUntil == null) return SuspendEnforcement.RejectedPermanentBan

        val newSuspendedUntil =
            conn.prepareStatement(
                """
                UPDATE users
                   SET is_banned = TRUE, suspended_until = NOW() + INTERVAL '7 days'
                 WHERE id = ?
                RETURNING suspended_until
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, targetId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "suspend RETURNING yielded no row for target $targetId (impossible under FOR UPDATE)" }
                    rs.getTimestamp("suspended_until").toInstant()
                }
            }
        return SuspendEnforcement.Applied(
            beforeIsBanned = current.isBanned,
            beforeSuspendedUntil = current.suspendedUntil,
            newSuspendedUntil = newSuspendedUntil,
        )
    }

    /**
     * Lift a ban on [targetId] (`is_banned = FALSE`, `suspended_until = NULL`) in
     * one transaction, writing the `user_unbanned` audit row atomically. This is
     * the same `(is_banned, suspended_until) → (FALSE, NULL)` transition the
     * automated `suspension-unban-worker` performs on elapse, triggered early by
     * a human. No notification is written on unban (design D9).
     *
     * Guards (design D8 + D3), against a `SELECT … FOR UPDATE` snapshot:
     *  - not currently banned (`is_banned = FALSE`) → [UnbanOutcome.NoOpNotBanned]
     *    with NO audit row (the log records only actual transitions);
     *  - permanently banned (`suspended_until IS NULL`) AND [actingAdminRole] is
     *    `moderator` → [UnbanOutcome.ForbiddenPermanentBan] with NO writes (the D3
     *    higher-trust tier; lifting a permanent ban is owner/admin only, checked
     *    inside the tx so the rejection writes nothing).
     *
     * A soft-deleted-but-banned target MAY be unbanned (`deleted_at` unchanged).
     */
    fun unban(
        targetId: UUID,
        actingAdminRole: String,
        actingAdminId: UUID,
        reason: String?,
        ip: String,
        userAgent: String?,
    ): UnbanOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val current =
                    lockUser(conn, targetId) ?: run {
                        conn.rollback()
                        return UnbanOutcome.NotFound
                    }
                if (!current.isBanned) {
                    conn.rollback()
                    return UnbanOutcome.NoOpNotBanned
                }
                val isPermanentBan = current.suspendedUntil == null
                if (isPermanentBan && actingAdminRole == MODERATOR_ROLE) {
                    conn.rollback()
                    return UnbanOutcome.ForbiddenPermanentBan
                }

                conn.prepareStatement(
                    "UPDATE users SET is_banned = FALSE, suspended_until = NULL WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, targetId)
                    ps.executeUpdate()
                }

                auditLogger.logUserUnbanned(
                    conn = conn,
                    adminId = actingAdminId,
                    targetUserId = targetId,
                    reason = reason,
                    beforeState = stateJson(isBanned = true, suspendedUntil = current.suspendedUntil),
                    afterState = stateJson(isBanned = false, suspendedUntil = null),
                    ip = ip,
                    userAgent = userAgent,
                )

                conn.commit()
                UnbanOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                // Restore the pool default before checkin — matches every other
                // transactional path in the codebase (CreatePostService,
                // ChatRepository, FollowService, SignupService, ReplyService,
                // LikeService). Defense-in-depth: the tx is already committed or
                // rolled back here, so there is no pending work to flush.
                runCatching { conn.autoCommit = true }
            }
        }

    /** `SELECT … FOR UPDATE` the target's moderation columns, locking the row
     *  for the duration of the enclosing transaction. */
    private fun lockUser(
        conn: Connection,
        id: UUID,
    ): UserModerationState? =
        conn.prepareStatement(
            "SELECT id, username, is_banned, suspended_until, deleted_at FROM users WHERE id = ? FOR UPDATE",
        ).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toState() else null }
        }

    /**
     * Insert the suspend-side `account_action_applied` notification on [conn]
     * (joins the suspend transaction, design D4 + D9). `body_data.reason` is the
     * SANITIZED fixed code [SANITIZED_REASON_CODE] — NOT the admin's free-text
     * reason (which is moderator-internal and lives only in `admin_actions_log`).
     * `target_id` is NOT duplicated inside `body_data`; `actor_user_id` is left
     * NULL (the actor is an admin, not a `public.users` row).
     *
     * `internal` (not `private`) so the report-queue `suspend_author_7d`
     * resolution reuses the EXACT same sanitized notification on its own
     * transaction connection (design D4) — the suspend notification shape is
     * defined once, here.
     */
    internal fun insertSuspendNotification(
        conn: Connection,
        targetId: UUID,
        suspendedUntil: Instant,
    ) {
        val bodyData =
            buildJsonObject {
                put("action_type", JsonPrimitive("user_suspended"))
                put("reason", JsonPrimitive(SANITIZED_REASON_CODE))
                put("suspended_until", JsonPrimitive(suspendedUntil.toString()))
            }
        conn.prepareStatement(
            "INSERT INTO notifications (user_id, type, body_data) VALUES (?, 'account_action_applied', ?::jsonb)",
        ).use { ps ->
            ps.setObject(1, targetId)
            ps.setString(2, json.encodeToString(bodyData))
            ps.executeUpdate()
        }
    }

    /** `{"is_banned": <bool>, "suspended_until": <ISO-8601 Instant | null>}`
     *  (design D6). Built in Kotlin and passed as `?::jsonb`. */
    private fun stateJson(
        isBanned: Boolean,
        suspendedUntil: Instant?,
    ): JsonObject =
        buildJsonObject {
            put("is_banned", JsonPrimitive(isBanned))
            put("suspended_until", suspendedUntil?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        }

    private fun ResultSet.toState(): UserModerationState =
        UserModerationState(
            id = getObject("id", UUID::class.java),
            username = getString("username"),
            isBanned = getBoolean("is_banned"),
            suspendedUntil = getTimestamp("suspended_until")?.toInstant(),
            deletedAt = getTimestamp("deleted_at")?.toInstant(),
        )

    companion object {
        /** The role disallowed from lifting a PERMANENT ban (design D3). */
        const val MODERATOR_ROLE = "moderator"

        /**
         * Sanitized, non-free-text code written to the user-facing notification's
         * `body_data.reason` (design D9). Deliberately NOT the admin's free-text
         * reason — echoing a moderator note like "suspected minor, see report #42"
         * would leak moderator-internal rationale / third-party PII to the
         * offender. The free-text reason is recorded ONLY in `admin_actions_log`.
         */
        const val SANITIZED_REASON_CODE = "suspension"

        private val json = Json { encodeDefaults = false }
    }
}

/** A target user's moderation-relevant columns (lookup + state display). */
data class UserModerationState(
    val id: UUID,
    val username: String,
    val isBanned: Boolean,
    val suspendedUntil: Instant?,
    val deletedAt: Instant?,
)

/** Typed result of [UserModerationRepository.suspend]. */
sealed interface SuspendOutcome {
    /** Suspension applied; carries the server-computed new expiry. */
    data class Applied(val suspendedUntil: Instant) : SuspendOutcome

    /** Target is soft-deleted (`deleted_at IS NOT NULL`) — not moderated. */
    data object RejectedSoftDeleted : SuspendOutcome

    /** Target is already permanently banned — suspend would downgrade it. */
    data object RejectedPermanentBan : SuspendOutcome

    /** Acting admin is at/over the destructive-action cap — no mutation, no audit. */
    data object RateLimited : SuspendOutcome

    /** No user resolves to the target id. */
    data object NotFound : SuspendOutcome
}

/** Typed result of [UserModerationRepository.warn]. */
sealed interface WarnOutcome {
    /** Warning issued: one `user_warned` audit row + one sanitized notification. */
    data object Applied : WarnOutcome

    /** Target is soft-deleted (`deleted_at IS NOT NULL`) — not warned. */
    data object TargetDeleted : WarnOutcome

    /** Acting admin is at/over the destructive-action cap — no audit, no notification. */
    data object RateLimited : WarnOutcome

    /** No user resolves to the target id. */
    data object NotFound : WarnOutcome
}

/**
 * Internal result of [UserModerationRepository.applySuspendUserUpdate] — the
 * suspend guards + `users` UPDATE outcome WITHOUT the audit row or notification
 * (the caller writes those, choosing `user_suspended` for the standalone route
 * or `moderation_queue_resolved` for the report-queue resolution). Distinct
 * from the public [SuspendOutcome] so the report-queue resolution maps a guard
 * rejection onto its own outcome vocabulary (design D4).
 */
internal sealed interface SuspendEnforcement {
    /** Suspend applied; carries the prior state (for the audit `before_state`)
     *  + the server-computed new expiry. */
    data class Applied(
        val beforeIsBanned: Boolean,
        val beforeSuspendedUntil: Instant?,
        val newSuspendedUntil: Instant,
    ) : SuspendEnforcement

    /** Target is soft-deleted (`deleted_at IS NOT NULL`). */
    data object RejectedSoftDeleted : SuspendEnforcement

    /** Target is already permanently banned — suspend would downgrade it. */
    data object RejectedPermanentBan : SuspendEnforcement

    /** No user resolves to the target id. */
    data object NotFound : SuspendEnforcement
}

/** Typed result of [UserModerationRepository.unban]. */
sealed interface UnbanOutcome {
    /** Ban lifted (`is_banned = FALSE`, `suspended_until = NULL`). */
    data object Applied : UnbanOutcome

    /** Target was not banned — no-op, no audit row. */
    data object NoOpNotBanned : UnbanOutcome

    /** Target is permanently banned and the actor is a `moderator` (D3 tier). */
    data object ForbiddenPermanentBan : UnbanOutcome

    /** No user resolves to the target id. */
    data object NotFound : UnbanOutcome
}
