package id.nearyou.app.admin.moderation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.util.UUID

/**
 * The SINGLE implementation of the permanent-ban / shadow-ban `users` column
 * writes + the sanitized ban notification, shared by BOTH admin entry points
 * (`admin-user-ban-shadowban-actions` design D1 — one ban behavior):
 *
 *  - the standalone user-page actions ([UserModerationRepository.permanentBan] /
 *    [UserModerationRepository.shadowBan]);
 *  - the report-queue `ban_author` / `shadow_ban_author` resolutions
 *    (`ReportResolutionRepository.resolveQueueItem`).
 *
 * Each function runs on a CALLER-owned [Connection] (no commit/rollback, no
 * audit row — the caller writes its own audit row and owns the transaction),
 * mirroring the [UserModerationRepository.applySuspendUserUpdate] seam. Guards
 * (soft-deleted / already-banned / role tier / rate limit) are the caller's:
 * these are the post-guard primitives only.
 *
 * Raw `UPDATE users` is permitted here: admin module (package
 * `id.nearyou.app.admin.*`), exempt from the timeline Detekt rules.
 */
internal object BanPrimitives {
    /**
     * Sanitized, non-free-text code written to the permanent-ban notification's
     * `body_data.reason` — NEVER the admin's free-text reason (echoing a
     * moderator note would leak moderator-internal rationale / third-party PII
     * to the offender; mirrors [UserModerationRepository.SANITIZED_REASON_CODE]
     * for suspend). The free-text reason lives ONLY in `admin_actions_log`.
     */
    const val BAN_REASON_CODE = "ban"

    private val json = Json { encodeDefaults = false }

    /** Permanent ban: `is_banned = TRUE`, `suspended_until = NULL` (the shape
     *  the unban path lifts). `token_version` is never modified. */
    fun applyPermanentBan(
        conn: Connection,
        userId: UUID,
    ) {
        conn.prepareStatement("UPDATE users SET is_banned = TRUE, suspended_until = NULL WHERE id = ?").use { ps ->
            ps.setObject(1, userId)
            ps.executeUpdate()
        }
    }

    /** Shadow ban: `is_shadow_banned = TRUE`. No other column, and callers
     *  write NO notification (stealth invariant — invisible to the offender). */
    fun applyShadowBan(
        conn: Connection,
        userId: UUID,
    ) {
        conn.prepareStatement("UPDATE users SET is_shadow_banned = TRUE WHERE id = ?").use { ps ->
            ps.setObject(1, userId)
            ps.executeUpdate()
        }
    }

    /**
     * Insert the permanent-ban-side `account_action_applied` notification on
     * [conn] (joins the caller's transaction). `body_data.reason` is the
     * sanitized fixed [BAN_REASON_CODE]; `actor_user_id` is left NULL (the
     * actor is an admin, not a `public.users` row), mirroring the suspend
     * notification.
     */
    fun insertBanNotification(
        conn: Connection,
        userId: UUID,
    ) {
        val bodyData =
            buildJsonObject {
                put("action_type", JsonPrimitive("user_banned"))
                put("reason", JsonPrimitive(BAN_REASON_CODE))
            }
        conn.prepareStatement(
            "INSERT INTO notifications (user_id, type, body_data) VALUES (?, 'account_action_applied', ?::jsonb)",
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, json.encodeToString(bodyData))
            ps.executeUpdate()
        }
    }
}
