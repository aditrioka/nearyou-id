package id.nearyou.app.admin.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Audit log writer for admin auth events.
 *
 * Per `admin-login-argon2-totp` design.md D14 + spec Req "Login POST writes
 * audit row when an admin actor is identified" + Req "CSRF mismatch writes
 * admin_csrf_violation audit row" + Req "Logout POST revokes session, clears
 * cookie, and writes audit row":
 *
 *  - Successful login          → INSERT admin_actions_log (action_type=admin_login_success)
 *  - Failed login (matched)    → INSERT admin_actions_log (action_type=admin_login_failure, reason=∈{password_mismatch, totp_mismatch, inactive_admin, totp_secret_missing})
 *  - Email-not-found attempt   → structured INFO log line at application logger (NOT admin_actions_log; V16 schema's admin_id NOT NULL forbids unowned rows — see D14)
 *  - Logout                    → INSERT admin_actions_log (action_type=admin_logout)
 *  - CSRF violation            → INSERT admin_actions_log (action_type=admin_csrf_violation, target_type='csrf', reason=∈{missing_token, header_mismatch, form_field_mismatch})
 *
 * Audit-row invariants enforced:
 *  - Plaintext password / TOTP code / CSRF token / session token NEVER reach
 *    a column. The method signatures don't accept them.
 *  - `after_state` JSON for success path contains session metadata only
 *    (session_id + expires_at + last_active_at); no token-derived value.
 *  - All INSERTs via `PreparedStatement` (parameterized; no string interpolation).
 *  - All rows carry IP + user_agent + created_at (via DEFAULT NOW()).
 */
class AdminAuditLogger(
    private val dataSource: DataSource,
) {
    fun logSuccess(
        adminId: UUID,
        sessionId: UUID,
        expiresAt: Instant,
        lastActiveAt: Instant,
        ip: String,
        userAgent: String?,
    ) {
        val afterState =
            buildJsonObject {
                put("session_id", JsonPrimitive(sessionId.toString()))
                put("expires_at", JsonPrimitive(expiresAt.toString()))
                put("last_active_at", JsonPrimitive(lastActiveAt.toString()))
            }
        insert(
            actionType = "admin_login_success",
            adminId = adminId,
            targetType = null,
            targetId = null,
            reason = null,
            beforeState = null,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    fun logFailure(
        adminId: UUID,
        reason: LoginFailureReason,
        ip: String,
        userAgent: String?,
    ) {
        insert(
            actionType = "admin_login_failure",
            adminId = adminId,
            targetType = null,
            targetId = null,
            reason = reason.code,
            beforeState = null,
            afterState = null,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Per design.md D14: the email-not-found path does NOT write to
     * `admin_actions_log` (the V16 schema's `admin_id NOT NULL` invariant
     * forbids it). Instead, emit a structured INFO log line at the
     * application logger so anomaly detection (per
     * `docs/08-Roadmap-Risk.md` Phase 1 §29) sees it at the same
     * observability surface as audit rows. The plaintext email NEVER
     * appears in the log line; only its SHA-256 hash (base64url-encoded).
     */
    fun logEmailNotFoundAttempt(
        submittedEmail: String,
        ip: String,
        userAgent: String?,
    ) {
        val emailHash = sha256Base64Url(submittedEmail)
        // Structured key=value pairs — matches the project's existing
        // logging convention (see AdminModule.kt for the WARN-line shape).
        log.info(
            "event=admin_login_attempt_email_not_found email_hash={} ip={} user_agent=\"{}\"",
            emailHash,
            ip,
            userAgent ?: "",
        )
    }

    fun logLogout(
        adminId: UUID,
        ip: String,
        userAgent: String?,
    ) {
        insert(
            actionType = "admin_logout",
            adminId = adminId,
            targetType = null,
            targetId = null,
            reason = null,
            beforeState = null,
            afterState = null,
            ip = ip,
            userAgent = userAgent,
        )
    }

    fun logCsrfViolation(
        adminId: UUID,
        reason: CsrfViolationReason,
        ip: String,
        userAgent: String?,
    ) {
        insert(
            actionType = "admin_csrf_violation",
            adminId = adminId,
            targetType = "csrf",
            targetId = null,
            reason = reason.code,
            beforeState = null,
            afterState = null,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a successful admin-initiated user SUSPEND
     * (`admin-user-moderation` capability). Unlike the auth-event writers
     * above, this joins a CALLER-SUPPLIED [conn] so the audit INSERT commits
     * atomically with the `users` UPDATE + the suspend notification in the
     * `UserModerationRepository` transaction (design D4) — the connection is
     * NOT closed or committed here; the caller owns the transaction.
     *
     * `adminId` is the acting human admin's `AdminPrincipal` UUID — NEVER the
     * `system` sentinel owned by the worker (`system-actor` capability).
     */
    fun logUserSuspended(
        conn: Connection,
        adminId: UUID,
        targetUserId: UUID,
        reason: String?,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "user_suspended",
            adminId = adminId,
            targetType = "user",
            targetId = targetUserId.toString(),
            reason = reason,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a successful admin-initiated user UNBAN
     * (`admin-user-moderation` capability). Joins the caller's [conn] for
     * atomicity with the `users` UPDATE (design D4); unban writes no
     * notification. `adminId` is the acting human admin, never the `system`
     * sentinel.
     */
    fun logUserUnbanned(
        conn: Connection,
        adminId: UUID,
        targetUserId: UUID,
        reason: String?,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "user_unbanned",
            adminId = adminId,
            targetType = "user",
            targetId = targetUserId.toString(),
            reason = reason,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a successful admin-initiated user WARNING
     * (`admin-user-moderation` capability — the warning action). Joins the
     * caller's [conn] so the audit INSERT commits atomically with the sanitized
     * `account_action_applied` notification in the `UserModerationRepository`
     * warn transaction (design D5); the warning mutates no `users` moderation
     * column. The free-text [reason] is stored here (audit-only) and is NEVER
     * echoed to the warned user's notification. `adminId` is the acting human
     * admin, never the `system` sentinel.
     */
    fun logUserWarned(
        conn: Connection,
        adminId: UUID,
        targetUserId: UUID,
        reason: String?,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "user_warned",
            adminId = adminId,
            targetType = "user",
            targetId = targetUserId.toString(),
            reason = reason,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a report-status resolution (`admin-report-queue-resolution-
     * actions` capability): `POST /admin/reports/{id}/resolve` transitioning a
     * `reports` row `pending → actioned | dismissed`. Joins the caller's [conn]
     * so the audit INSERT commits atomically with the `reports` UPDATE
     * ([id.nearyou.app.admin.reportqueue.ReportResolutionRepository], design D4).
     * `targetType = 'report'`, `targetId` = the report id; `beforeState` /
     * `afterState` record the status transition. `adminId` is the acting human
     * admin, never the `system` sentinel.
     */
    fun logReportResolved(
        conn: Connection,
        adminId: UUID,
        reportId: UUID,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "report_resolved",
            adminId = adminId,
            targetType = "report",
            targetId = reportId.toString(),
            reason = null,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a moderation-queue resolution that PERFORMED its enforcement
     * (`admin-report-queue-resolution-actions` capability): `POST
     * /admin/moderation-queue/{id}/resolve` transitioning a `moderation_queue`
     * row `pending → resolved` AND applying the named enforcement. Joins the
     * caller's [conn] so this audit INSERT commits atomically with the
     * enforcement write(s) + the queue UPDATE (+ any `account_action_applied`
     * notification) in ONE transaction (design D4). `targetType =
     * 'moderation_queue'`, `targetId` = the queue id; the [afterState] JSONB
     * records BOTH the `resolution` value AND the enforcement effect (e.g.
     * `{"resolution":"ban_author","is_banned":true,"suspended_until":null}`) so
     * the audit trail is self-describing (design D9). `adminId` is the acting
     * human admin, never the `system` sentinel.
     */
    fun logModerationQueueResolved(
        conn: Connection,
        adminId: UUID,
        queueId: UUID,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "moderation_queue_resolved",
            adminId = adminId,
            targetType = "moderation_queue",
            targetId = queueId.toString(),
            reason = null,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for an admin-initiated CHAT MESSAGE REDACTION
     * (`admin-chat-message-redaction` capability): `POST /admin/chat-messages/
     * {id}/redact` setting the redaction flags on a `chat_messages` row. Joins
     * the caller's [conn] so this audit INSERT commits atomically with the
     * `chat_messages` UPDATE + the participant `chat_message_redacted`
     * notifications in ONE transaction (mirrors [logModerationQueueResolved]).
     * `targetType = 'chat_message'`, `targetId` = the redacted message id; the
     * free-text [reason] is stored here (audit-only) and is NEVER serialized on
     * the chat data plane. `beforeState` captures the original row (content or
     * embedded-post snapshot); `afterState` records the redacted result.
     * `adminId` is the acting human admin, never the `system` sentinel.
     */
    fun logChatRedaction(
        conn: Connection,
        adminId: UUID,
        messageId: UUID,
        reason: String?,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "admin_chat_redaction",
            adminId = adminId,
            targetType = "chat_message",
            targetId = messageId.toString(),
            reason = reason,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a subscription-grace MANUAL EXPEDITE
     * (`admin-subscription-grace-monitor` capability): `POST
     * /admin/subscriptions/grace/{user_id}/expedite` recording a support-desk
     * bookkeeping resolution. Joins the caller's [conn] so the audit INSERT + the
     * rate-limit COUNT read the same ledger snapshot and append atomically inside
     * the expedite transaction. `target_type = 'user'`, `target_id` = the
     * billing-retry user's id. This action changes NO entitlement: it mutates no
     * `users` column and writes no `subscription_events` row — so [beforeState]
     * and [afterState] carry an IDENTICAL `subscription_status` (the snapshot
     * documents that expedite did not downgrade/grant); [afterState] additionally
     * records `{expedited: true, ticket_ref}`. The mandatory support-ticket
     * reference is carried in [reason]. `adminId` is the acting human admin,
     * never the `system` sentinel.
     */
    fun logSubscriptionGraceExpedite(
        conn: Connection,
        adminId: UUID,
        targetUserId: UUID,
        reason: String,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "subscription_grace_expedite",
            adminId = adminId,
            targetType = "user",
            targetId = targetUserId.toString(),
            reason = reason,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a hard-delete-queue MANUAL EXPEDITE (`admin-hard-delete-queue`
     * capability): `POST /admin/deletion-requests/{id}/expedite` advancing a
     * pending `deletion_requests` row's `scheduled_hard_delete_at` to `NOW()` so
     * the existing daily worker erases the account on its next run. Joins the
     * caller's [conn] so this audit INSERT + the rate-limit COUNT read the same
     * ledger snapshot and the deadline advance + audit row commit atomically inside
     * the expedite transaction (the atomicity requirement). `target_type =
     * 'deletion_request'`, `target_id` = the deletion-request id. Unlike the
     * subscription-grace expedite (a no-op), this action MUTATES — so [beforeState]
     * / [afterState] carry DIFFERING `scheduled_hard_delete_at` values (the prior
     * future deadline → `NOW()`) plus the affected `user_id`; the route itself never
     * tombstones or cascades (the worker does). The mandatory [reason] is the
     * operator justification. `adminId` is the acting human admin, never the
     * `system` sentinel.
     */
    fun logDeletionRequestExpedited(
        conn: Connection,
        adminId: UUID,
        deletionRequestId: UUID,
        reason: String,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "deletion_request_expedited",
            adminId = adminId,
            targetType = "deletion_request",
            targetId = deletionRequestId.toString(),
            reason = reason,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a feature-flag publish (`admin-feature-flags` capability):
     * exactly one immutable row per applied Server-template parameter write.
     * Joins the caller's [conn] so the rate-limit COUNT and this INSERT read +
     * append the same ledger snapshot on one connection (the count can't drift
     * from the ledger it gates — design D3). `action_type='feature_flag_toggled'`,
     * `target_type='feature_flag'`, `target_id`=the parameter name;
     * [beforeState]/[afterState] record the value transition (e.g.
     * `{"value":"false"}` → `{"value":"true"}`); [reason] is the mandatory
     * operator-supplied free text. `adminId` is the acting human admin, never the
     * `system` sentinel.
     */
    fun logFeatureFlagToggled(
        conn: Connection,
        adminId: UUID,
        parameterName: String,
        reason: String,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "feature_flag_toggled",
            adminId = adminId,
            targetType = "feature_flag",
            targetId = parameterName,
            reason = reason,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a Premium username-flag RESOLUTION (`admin-premium-username-
     * oversight` capability): `POST /admin/username-oversight/flags/{queue_id}/
     * resolve` transitioning a `username_flagged` `moderation_queue` row
     * `pending → resolved`. Joins the caller's [conn] so this audit INSERT commits
     * atomically with the queue UPDATE AND — on `accept_flagged_username` — the
     * `username_flag_overrides` upsert in ONE transaction (design Decision 2). The
     * [afterState] records the chosen `resolution` and, on accept, the approved
     * candidate (e.g. `{"resolution":"accept_flagged_username","candidate":"…"}`)
     * so the audit trail is self-describing — it is the only record of WHICH handle
     * the operator whitelisted. `targetType = 'user'`, `targetId` = the flag's
     * `target_id` (the flagged user). `adminId` is the acting human admin, never the
     * `system` sentinel.
     */
    fun logUsernameFlagResolved(
        conn: Connection,
        adminId: UUID,
        targetUserId: UUID,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "username_flag_resolved",
            adminId = adminId,
            targetType = "user",
            targetId = targetUserId.toString(),
            reason = null,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a Premium handle HOLD RELEASE (`admin-premium-username-
     * oversight` capability): `POST /admin/username-oversight/holds/{history_id}/
     * release` force-releasing a `username_history` 30-day hold (`released_at =
     * NOW()`). Joins the caller's [conn] so this audit INSERT commits atomically
     * with the `username_history` UPDATE in ONE transaction. The [beforeState]
     * records the prior `released_at` (e.g. `{"released_at":"…"}`) so the audit
     * trail captures the hold that was shortened. `targetType = 'username_history'`,
     * `targetId` = the history row id. `adminId` is the acting human admin, never
     * the `system` sentinel.
     */
    fun logUsernameHoldReleased(
        conn: Connection,
        adminId: UUID,
        historyId: UUID,
        beforeState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "username_hold_released",
            adminId = adminId,
            targetType = "username_history",
            targetId = historyId.toString(),
            reason = null,
            beforeState = beforeState,
            afterState = null,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a reserved-username editor ADD (`admin-reserved-usernames-
     * editor` capability): a single add or one inserted CSV bulk row. Joins the
     * caller's [conn] so the audit INSERT commits atomically with the
     * `reserved_usernames` INSERT in the repository transaction. `targetType =
     * 'reserved_username'`, `targetId` = the username (the `target_id` TEXT
     * column holds it directly); [afterState] records `{username, reason,
     * source}`. The action carries no separate free-text justification, so
     * `reason` is null (the reserved entry's own reason lives in [afterState]).
     * `adminId` is the acting human admin, never the `system` sentinel.
     */
    fun logReservedUsernameAdded(
        conn: Connection,
        adminId: UUID,
        username: String,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "reserved_username_added",
            adminId = adminId,
            targetType = "reserved_username",
            targetId = username,
            reason = null,
            beforeState = null,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a reserved-username editor REASON EDIT (`admin-reserved-
     * usernames-editor`). Joins the caller's [conn] so the audit INSERT commits
     * atomically with the `reserved_usernames` UPDATE. [beforeState]/[afterState]
     * record the old/new `{reason}`. `adminId` is the acting human admin.
     */
    fun logReservedUsernameEdited(
        conn: Connection,
        adminId: UUID,
        username: String,
        beforeState: JsonElement,
        afterState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "reserved_username_edited",
            adminId = adminId,
            targetType = "reserved_username",
            targetId = username,
            reason = null,
            beforeState = beforeState,
            afterState = afterState,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a reserved-username editor REMOVE (`admin-reserved-usernames-
     * editor`). Joins the caller's [conn] so the audit INSERT commits atomically
     * with the `reserved_usernames` DELETE. [beforeState] records the removed
     * `{username, reason, source}`. `adminId` is the acting human admin.
     */
    fun logReservedUsernameRemoved(
        conn: Connection,
        adminId: UUID,
        username: String,
        beforeState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "reserved_username_removed",
            adminId = adminId,
            targetType = "reserved_username",
            targetId = username,
            reason = null,
            beforeState = beforeState,
            afterState = null,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Audit row for a rejected-identifier manual CLEAR (`admin-rejected-
     * identifiers-clear-action`). Joins the caller's [conn] so the audit INSERT
     * commits atomically with the `rejected_identifiers` DELETE. [beforeState]
     * records the cleared `{identifier_hash, identifier_type, reason, rejected_at}`;
     * `after_state` is null (the row is gone — the audit trail is the retained
     * record). [reason] is the admin-supplied support justification. `adminId` is
     * the acting human admin; this same `action_type` row is also the rate-limit
     * ledger entry counted by [id.nearyou.app.admin.ratelimit.RejectedIdentifierClearRateLimiter].
     */
    fun logRejectedIdentifierCleared(
        conn: Connection,
        adminId: UUID,
        rejectedIdentifierId: UUID,
        reason: String,
        beforeState: JsonElement,
        ip: String,
        userAgent: String?,
    ) {
        insertWithConnection(
            conn = conn,
            actionType = "rejected_identifier_cleared",
            adminId = adminId,
            targetType = "rejected_identifier",
            targetId = rejectedIdentifierId.toString(),
            reason = reason,
            beforeState = beforeState,
            afterState = null,
            ip = ip,
            userAgent = userAgent,
        )
    }

    /**
     * Own-connection audit write for the standalone login / logout / CSRF
     * events — opens, writes, and (via `use`) closes its own connection.
     * There is nothing to be atomic against on those paths, so each is a
     * single self-contained INSERT (contrast [logUserSuspended] /
     * [logUserUnbanned], which join a repository transaction).
     */
    private fun insert(
        actionType: String,
        adminId: UUID,
        targetType: String?,
        targetId: String?,
        reason: String?,
        beforeState: JsonElement?,
        afterState: JsonElement?,
        ip: String,
        userAgent: String?,
    ) {
        dataSource.connection.use { conn ->
            insertWithConnection(
                conn = conn,
                actionType = actionType,
                adminId = adminId,
                targetType = targetType,
                targetId = targetId,
                reason = reason,
                beforeState = beforeState,
                afterState = afterState,
                ip = ip,
                userAgent = userAgent,
            )
        }
    }

    /**
     * Single canonical `admin_actions_log` INSERT, parameterized. Writes on
     * the supplied [conn] WITHOUT closing or committing it — so an own-
     * connection caller wraps it in `dataSource.connection.use { }` (see
     * [insert]) and a transactional caller passes its repository connection
     * (see [logUserSuspended] / [logUserUnbanned]). Sharing one statement
     * keeps the column list + the `?::jsonb` / `?::inet` casts + the IP
     * sanitization identical across both call shapes.
     */
    private fun insertWithConnection(
        conn: Connection,
        actionType: String,
        adminId: UUID,
        targetType: String?,
        targetId: String?,
        reason: String?,
        beforeState: JsonElement?,
        afterState: JsonElement?,
        ip: String,
        userAgent: String?,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO admin_actions_log (
                admin_id, action_type, target_type, target_id, reason,
                before_state, after_state, ip, user_agent
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, adminId)
            ps.setString(2, actionType)
            ps.setString(3, targetType)
            ps.setString(4, targetId)
            ps.setString(5, reason)
            ps.setString(6, beforeState?.let { json.encodeToString(it) })
            ps.setString(7, afterState?.let { json.encodeToString(it) })
            // admin_actions_log.ip is nullable INET — sanitize so a
            // non-literal clientIp doesn't throw at the ?::inet cast
            // (would 500 the audit write, breaking no-enumeration on a
            // failure path). NULL when not an IP literal.
            ps.setString(8, InetSanitizer.orFallback(ip, null))
            ps.setString(9, userAgent)
            ps.executeUpdate()
        }
    }

    /**
     * Login failure reasons recorded on `admin_actions_log.reason` when a
     * matched admin row exists. The `email_not_found` reason is NOT in this
     * enum because that path does not write to `admin_actions_log` — see
     * [logEmailNotFoundAttempt].
     */
    enum class LoginFailureReason(val code: String) {
        PASSWORD_MISMATCH("password_mismatch"),
        TOTP_MISMATCH("totp_mismatch"),
        INACTIVE_ADMIN("inactive_admin"),
        TOTP_SECRET_MISSING("totp_secret_missing"),
    }

    enum class CsrfViolationReason(val code: String) {
        MISSING_TOKEN("missing_token"),
        HEADER_MISMATCH("header_mismatch"),
        FORM_FIELD_MISMATCH("form_field_mismatch"),
    }

    companion object {
        private val log = LoggerFactory.getLogger("id.nearyou.app.admin.auth.AdminAuditLogger")
        private val json = Json { encodeDefaults = false }

        /**
         * SHA-256 of [input] (UTF-8 encoded) → base64url-encoded (no padding).
         * Used for the email_not_found INFO-log `email_hash` field so the
         * same email is correlatable across attempts without exposing the
         * plaintext.
         */
        fun sha256Base64Url(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
