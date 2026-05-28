package id.nearyou.app.admin.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest
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
                ps.setString(8, ip)
                ps.setString(9, userAgent)
                ps.executeUpdate()
            }
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
