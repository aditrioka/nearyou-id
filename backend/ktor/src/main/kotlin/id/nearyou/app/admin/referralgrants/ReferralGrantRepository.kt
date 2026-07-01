package id.nearyou.app.admin.referralgrants

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.ratelimit.ReferralGrantActionRateLimiter
import id.nearyou.app.infra.revenuecatapi.GrantRequest
import id.nearyou.app.infra.revenuecatapi.GrantResult
import id.nearyou.app.infra.revenuecatapi.ReferralEntitlementGranter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Read + write repository for the Referral Manual Grant surface
 * (`admin-referral-manual-grant`, admin board frame 19) — the support-desk
 * remedy for a false-negatived referral (docs/07 § Referral Manual Grant Path,
 * *"bukan aksi sistem"*).
 *
 * Three responsibilities:
 *  1. [lookup] — resolve a target user (by username or UUID) and read their
 *     premium / `subscription_status` + referral context, so the admin can
 *     verify the support claim before granting.
 *  2. [grant] — dispatch a 1-week promotional Premium grant through the shipped
 *     [ReferralEntitlementGranter] port and write ONE immutable
 *     `admin_actions_log` row as the authoritative record (design D2/D3). It
 *     deliberately writes NEITHER `granted_entitlements` NOR `subscription_events`
 *     NOR `users.subscription_status` — activation + the events row stay owned by
 *     the existing GRANT webhook echo.
 *  3. [pastGrants] — the read-only-admin-viewer over the audit log (design D5;
 *     keyset pagination + lenient parameterized filters, cloning the grace/
 *     privacy-flip precedent).
 *
 * Admin-module raw reads of `users` / `granted_entitlements` / `subscription_events`
 * are permitted (the `RawFromPostsRule` / block-exclusion lint rules do not fire
 * on these tables); every value is autoescaped at render.
 */
class ReferralGrantRepository(
    private val dataSource: DataSource,
    private val granter: ReferralEntitlementGranter,
    private val auditLogger: AdminAuditLogger,
    private val rateLimiter: ReferralGrantActionRateLimiter,
    private val clock: () -> Instant = Instant::now,
) {
    /** Resolve [q] (a UUID or an exact case-insensitive username) to the target
     *  user + their premium / referral context; null when no user matches. */
    fun lookup(q: String): ReferralGrantUserContext? {
        val asUuid = runCatching { UUID.fromString(q) }.getOrNull()
        return dataSource.connection.use { conn ->
            val user =
                conn.prepareStatement(
                    """
                    SELECT id, username, subscription_status, deleted_at
                      FROM users
                     WHERE ${if (asUuid != null) "id = ?" else "LOWER(username) = LOWER(?)"}
                     LIMIT 1
                    """.trimIndent(),
                ).use { ps ->
                    if (asUuid != null) ps.setObject(1, asUuid) else ps.setString(1, q)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        UserRow(
                            id = rs.getObject("id", UUID::class.java),
                            username = rs.getString("username"),
                            subscriptionStatus = rs.getString("subscription_status"),
                            deleted = rs.getTimestamp("deleted_at") != null,
                        )
                    }
                } ?: return null

            val (asInvitee, asInviter) = countReferralGrants(conn, user.id)
            ReferralGrantUserContext(
                id = user.id,
                username = user.username,
                subscriptionStatus = user.subscriptionStatus,
                entitlementEnd = currentEntitlementEnd(conn, user.id),
                referralGrantsAsInvitee = asInvitee,
                referralGrantsAsInviter = asInviter,
                deleted = user.deleted,
            )
        }
    }

    /**
     * Issue a manual 1-week promotional Premium grant to [userId]. Order
     * (design D2/D3/D4): resolve the target (→ [GrantOutcome.NotFound] when
     * absent, nothing dispatched/written) → distinct restorative rate-limit gate
     * (→ [GrantOutcome.RateLimited], nothing dispatched/written) → dispatch the
     * RC promotional grant with `endTimeMs = GREATEST(current_end, NOW()) + 7d`
     * → write exactly one immutable `admin_actions_log` row REGARDLESS of the
     * dispatch outcome. The RC call is `suspend`, so it runs OUTSIDE the audit
     * write's transaction (a pooled connection is never held across the network
     * call); the ±1 soft-cap tolerance (design D4) makes that safe.
     *
     * NEVER writes `granted_entitlements`, `subscription_events`, or
     * `users.subscription_status`.
     */
    suspend fun grant(
        userId: UUID,
        actingAdminId: UUID,
        reason: String,
        ip: String,
        userAgent: String?,
    ): GrantOutcome {
        // Resolve target + read the pre-grant snapshot + gate the cap on one
        // committed-read connection (no write held across the dispatch below).
        val (status, currentEnd) =
            dataSource.connection.use { conn ->
                val s =
                    conn.prepareStatement(
                        "SELECT subscription_status FROM users WHERE id = ?",
                    ).use { ps ->
                        ps.setObject(1, userId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) return GrantOutcome.NotFound
                            rs.getString("subscription_status")
                        }
                    }
                if (rateLimiter.isAtOrOverCap(conn, actingAdminId)) return GrantOutcome.RateLimited
                s to currentEntitlementEnd(conn, userId)
            }

        val now = clock()
        val end = maxOf(currentEnd ?: now, now).plus(Duration.ofDays(GRANT_DAYS))
        val result =
            granter.grant(
                GrantRequest(
                    appUserId = userId.toString(),
                    entitlementId = ENTITLEMENT_ID,
                    endTimeMs = end.toEpochMilli(),
                    // Our-side sentinel only — the RC v1 promotional endpoint has
                    // no dedup param (design Risk), so this is never sent upstream.
                    dedupKey = "manual_admin:${UUID.randomUUID()}",
                ),
            )

        val dispatch =
            when (result) {
                GrantResult.Dispatched -> "dispatched"
                GrantResult.NotConfigured -> "not_configured"
                is GrantResult.Failed -> "failed"
            }
        val beforeState =
            buildJsonObject {
                put("subscription_status", JsonPrimitive(status))
                put("entitlement_end", currentEnd?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            }
        val afterState =
            buildJsonObject {
                // subscription_status is UNCHANGED here — activation is owned by
                // the GRANT webhook echo (design D3), not this handler.
                put("subscription_status", JsonPrimitive(status))
                put("entitlement_end_requested", JsonPrimitive(end.toString()))
                put("grant_days", JsonPrimitive(GRANT_DAYS))
                put("dispatch", JsonPrimitive(dispatch))
                if (result is GrantResult.Failed) put("dispatch_reason", JsonPrimitive(result.reason))
            }
        dataSource.connection.use { conn ->
            auditLogger.logReferralManualGrant(
                conn = conn,
                adminId = actingAdminId,
                targetUserId = userId,
                reason = reason,
                beforeState = beforeState,
                afterState = afterState,
                ip = ip,
                userAgent = userAgent,
            )
        }

        return when (result) {
            GrantResult.Dispatched -> GrantOutcome.Dispatched(end)
            GrantResult.NotConfigured -> GrantOutcome.DispatchSkipped(end)
            is GrantResult.Failed -> GrantOutcome.DispatchFailed(result.reason)
        }
    }

    /** Keyset-paginated (newest-first) page of past manual grants (design D5). */
    fun pastGrants(q: GrantsQuery): GrantsPage {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        q.qUserId?.let {
            conditions += "aal.target_id = ?"
            params += it.toString()
        }
        q.qUsername?.let {
            conditions += "LOWER(u.username) = LOWER(?)"
            params += it
        }
        q.dateFrom?.let {
            conditions += "aal.created_at >= ?"
            params += java.sql.Timestamp.from(it)
        }
        q.dateTo?.let {
            conditions += "aal.created_at < ?"
            params += java.sql.Timestamp.from(it)
        }
        q.cursor?.let {
            conditions += "(aal.created_at, aal.id) < (?, ?)"
            params += java.sql.Timestamp.from(it.createdAt)
            params += it.id
        }

        val sql =
            buildString {
                append(BASE_SELECT)
                conditions.forEach { append("\n   AND ").append(it) }
                append("\n ORDER BY aal.created_at DESC, aal.id DESC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<GrantRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) fetched += rs.toGrantRow()
                }
            }
        }

        val hasNext = fetched.size > q.pageSize
        val display = if (hasNext) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasNext && display.isNotEmpty()) display.last().let { GrantsCursor(it.createdAt, it.id) } else null
        return GrantsPage(rows = display, nextCursor = nextCursor)
    }

    /** `GREATEST(MAX(subscription_events.entitlement_end), MAX(granted_entitlements.entitlement_end))`
     *  — the stacking floor, identical to the referral worker. NULL when the
     *  user never had a window (caller floors with NOW()). */
    private fun currentEntitlementEnd(
        conn: Connection,
        userId: UUID,
    ): Instant? =
        conn.prepareStatement(
            """
            SELECT GREATEST(
                (SELECT MAX(entitlement_end) FROM subscription_events WHERE user_id = ?),
                (SELECT MAX(entitlement_end) FROM granted_entitlements WHERE user_id = ?)
            )
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setObject(2, userId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getObject(1, OffsetDateTime::class.java)?.toInstant() else null
            }
        }

    /** (asInvitee, asInviter) referral-grant counts from `granted_entitlements`. */
    private fun countReferralGrants(
        conn: Connection,
        userId: UUID,
    ): Pair<Int, Int> =
        conn.prepareStatement(
            "SELECT grant_role, COUNT(*) FROM granted_entitlements WHERE user_id = ? GROUP BY grant_role",
        ).use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                var invitee = 0
                var inviter = 0
                while (rs.next()) {
                    when (rs.getString(1)) {
                        "invitee" -> invitee = rs.getInt(2)
                        "inviter" -> inviter = rs.getInt(2)
                    }
                }
                invitee to inviter
            }
        }

    private fun java.sql.ResultSet.toGrantRow(): GrantRow =
        GrantRow(
            id = getObject("id", UUID::class.java),
            createdAt = getTimestamp("created_at").toInstant(),
            granteeId = getString("target_id"),
            granteeUsername = getString("grantee_username"),
            reason = getString("reason"),
            adminName = getString("admin_name"),
            dispatch = getString("dispatch"),
        )

    companion object {
        /** RC entitlement id — the same hardcoded id the referral worker grants
         *  (`ReferralActivityCheckWorker.ENTITLEMENT_ID`). */
        const val ENTITLEMENT_ID = "premium"

        /** Grant window — one week, identical to the worker. */
        const val GRANT_DAYS = 7L

        /** Fixed page size for the past-grants viewer (design D5). */
        const val PAGE_SIZE = 50

        // LEFT JOIN `users` tolerates a soft-deleted/tombstoned grantee (design
        // D5): the grant row still renders with a null username. The join casts
        // the UUID column to text (`u.id::text = aal.target_id`) rather than
        // parsing `target_id::uuid` — the latter would throw on unrelated audit
        // rows with a non-UUID `target_id` if the planner evaluated the ON before
        // pushing the action_type filter down (the grace-repo idiom). `after_state`
        // JSONB carries the dispatch outcome recorded at grant time.
        private val BASE_SELECT =
            """
            SELECT aal.id, aal.created_at, aal.target_id, aal.reason,
                   au.display_name              AS admin_name,
                   u.username                   AS grantee_username,
                   aal.after_state ->> 'dispatch' AS dispatch
              FROM admin_actions_log aal
              JOIN admin_users au ON au.id = aal.admin_id
              LEFT JOIN users u ON u.id::text = aal.target_id
             WHERE aal.action_type = 'referral_manual_grant'
            """.trimIndent()
    }
}

private data class UserRow(
    val id: UUID,
    val username: String,
    val subscriptionStatus: String,
    val deleted: Boolean,
)

/** A user resolved for the grant form + the referral context shown to verify the claim. */
data class ReferralGrantUserContext(
    val id: UUID,
    val username: String,
    val subscriptionStatus: String,
    val entitlementEnd: Instant?,
    val referralGrantsAsInvitee: Int,
    val referralGrantsAsInviter: Int,
    val deleted: Boolean,
)

/** Typed result of [ReferralGrantRepository.grant]. */
sealed interface GrantOutcome {
    /** RC accepted the promotional grant; Premium activates on the webhook echo. */
    data class Dispatched(val entitlementEnd: Instant) : GrantOutcome

    /** RC unconfigured (fail-soft): audit row written, dispatch skipped. */
    data class DispatchSkipped(val entitlementEnd: Instant) : GrantOutcome

    /** RC configured but rejected/errored: audit row written, Premium not activated. */
    data class DispatchFailed(val reason: String) : GrantOutcome

    /** No user matched the id; nothing dispatched or written. */
    data object NotFound : GrantOutcome

    /** Acting admin at/over the per-hour cap; nothing dispatched or written. */
    data object RateLimited : GrantOutcome
}

/** One rendered past-manual-grant row. */
data class GrantRow(
    val id: UUID,
    val createdAt: Instant,
    val granteeId: String,
    /** Null when the grantee was soft-deleted/tombstoned after the grant. */
    val granteeUsername: String?,
    val reason: String?,
    val adminName: String,
    val dispatch: String?,
)

/** Active filters + page size + optional cursor for one past-grants page. */
data class GrantsQuery(
    val qUserId: UUID? = null,
    val qUsername: String? = null,
    val dateFrom: Instant? = null,
    val dateTo: Instant? = null,
    val cursor: GrantsCursor? = null,
    val pageSize: Int = ReferralGrantRepository.PAGE_SIZE,
)

/** One page of past grants + the cursor for the next page (null = last page). */
data class GrantsPage(
    val rows: List<GrantRow>,
    val nextCursor: GrantsCursor?,
)

/**
 * Opaque keyset cursor over `(created_at, id)` DESC, `base64url("<micros>|<id>")`.
 * Opaque but NOT a security boundary; a malformed token decodes to null (→ first
 * page), never throws. Clones [id.nearyou.app.admin.subscriptiongrace.GraceCursor].
 */
data class GrantsCursor(
    val createdAt: Instant,
    val id: UUID,
) {
    fun encode(): String {
        val raw = "${createdAt.toEpochMicros()}|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): GrantsCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                val sep = raw.indexOf('|')
                if (sep <= 0 || sep == raw.length - 1) return null
                val micros = raw.substring(0, sep).toLong()
                val id = UUID.fromString(raw.substring(sep + 1))
                GrantsCursor(instantFromEpochMicros(micros), id)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun Instant.toEpochMicros(): Long = epochSecond * 1_000_000 + nano / 1_000

        private fun instantFromEpochMicros(micros: Long): Instant =
            Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1_000L)
    }
}
