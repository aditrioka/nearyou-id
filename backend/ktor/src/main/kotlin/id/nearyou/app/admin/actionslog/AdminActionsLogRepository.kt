package id.nearyou.app.admin.actionslog

import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Read-only repository for `admin_actions_log` — backs the audit-log viewer
 * (`admin-actions-log-viewer` capability, Admin #4).
 *
 * Design references: `openspec/changes/admin-actions-log-viewer/design.md`
 * D1 (keyset pagination over `(created_at, id)` DESC), D2 (composable
 * index-aligned filters), D7 (parameterized dynamic WHERE + `admin_users`
 * JOIN).
 *
 *  - All queries via JDBC `PreparedStatement`; every filter value is bound
 *    positionally, NEVER string-interpolated — so a filter value carrying
 *    SQL metacharacters is treated as a literal, never executed.
 *  - Raw read of `admin_actions_log` / `admin_users` is permitted: this is
 *    the admin module, exempt from the `RawFromPostsRule` /
 *    `BlockExclusionJoinRule` Detekt rules (per `openspec/project.md`
 *    § Coding Conventions — "Raw reads allowed only in Repository own-content
 *    paths and the admin module"). These are admin tables, not user-content
 *    tables, so no `visible_*` view or block-exclusion join applies.
 *  - The `admin_users` JOIN (not LEFT JOIN) is safe: `admin_actions_log
 *    .admin_id` is `NOT NULL` with an FK to `admin_users(id)`, so every
 *    audit row has a resolvable actor.
 *  - This repository exposes NO write path — `admin_actions_log` immutability
 *    is enforced at the `admin_app` DB-role level (`REVOKE UPDATE, DELETE`),
 *    provisioned operationally (out of scope here).
 */
class AdminActionsLogRepository(
    private val dataSource: DataSource,
) {
    /**
     * Run the filtered + keyset-paginated query and return one page of rows
     * plus the cursor for the next-older page (null when there is none).
     *
     * Fetches `pageSize + 1` rows: the extra row (if present) signals "there
     * is an older page" and its predecessor becomes the next cursor — no
     * `OFFSET`, no total-count query (design.md D1).
     */
    fun query(q: ActionLogQuery): ActionLogPage {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()

        q.actionType?.let {
            conditions += "l.action_type = ?"
            params += it
        }
        q.adminId?.let {
            conditions += "l.admin_id = ?"
            params += it
        }
        q.targetType?.let {
            conditions += "l.target_type = ?"
            params += it
        }
        q.targetId?.let {
            conditions += "l.target_id = ?"
            params += it
        }
        q.fromInclusive?.let {
            conditions += "l.created_at >= ?"
            params += java.sql.Timestamp.from(it)
        }
        q.toExclusive?.let {
            conditions += "l.created_at < ?"
            params += java.sql.Timestamp.from(it)
        }
        q.cursor?.let {
            // Keyset "older" predicate: row-value comparison aligned with the
            // `created_at DESC, id DESC` ordering. `id` is the deterministic
            // tiebreaker for rows sharing an identical `created_at`.
            conditions += "(l.created_at, l.id) < (?, ?)"
            params += java.sql.Timestamp.from(it.createdAt)
            params += it.id
        }

        val sql =
            buildString {
                append(
                    """
                    SELECT l.id, l.admin_id, u.display_name, u.email,
                           l.action_type, l.target_type, l.target_id, l.reason,
                           l.before_state::text AS before_state,
                           l.after_state::text AS after_state,
                           l.ip::text AS ip, l.user_agent, l.created_at
                      FROM admin_actions_log l
                      JOIN admin_users u ON u.id = l.admin_id
                    """.trimIndent(),
                )
                if (conditions.isNotEmpty()) {
                    append("\n WHERE ")
                    append(conditions.joinToString(" AND "))
                }
                append("\n ORDER BY l.created_at DESC, l.id DESC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<ActionLogRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        fetched +=
                            ActionLogRow(
                                id = rs.getObject("id", UUID::class.java),
                                adminId = rs.getObject("admin_id", UUID::class.java),
                                adminDisplayName = rs.getString("display_name"),
                                adminEmail = rs.getString("email"),
                                actionType = rs.getString("action_type"),
                                targetType = rs.getString("target_type"),
                                targetId = rs.getString("target_id"),
                                reason = rs.getString("reason"),
                                beforeState = rs.getString("before_state"),
                                afterState = rs.getString("after_state"),
                                ip = rs.getString("ip"),
                                userAgent = rs.getString("user_agent"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            )
                    }
                }
            }
        }

        val hasOlder = fetched.size > q.pageSize
        val display = if (hasOlder) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasOlder && display.isNotEmpty()) {
                display.last().let { ActionLogCursor(it.createdAt, it.id) }
            } else {
                null
            }
        return ActionLogPage(rows = display, nextCursor = nextCursor)
    }

    companion object {
        /** Fixed page size (design.md D1 — implementation constant, not a client param). */
        const val PAGE_SIZE: Int = 50
    }
}

/** A single rendered `admin_actions_log` row joined to its acting admin. */
data class ActionLogRow(
    val id: UUID,
    val adminId: UUID,
    val adminDisplayName: String,
    val adminEmail: String,
    val actionType: String,
    val targetType: String?,
    val targetId: String?,
    val reason: String?,
    val beforeState: String?,
    val afterState: String?,
    val ip: String?,
    val userAgent: String?,
    val createdAt: Instant,
)

/** The active filter set + page size + optional keyset cursor for one query. */
data class ActionLogQuery(
    val actionType: String? = null,
    val adminId: UUID? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val fromInclusive: Instant? = null,
    val toExclusive: Instant? = null,
    val cursor: ActionLogCursor? = null,
    val pageSize: Int = AdminActionsLogRepository.PAGE_SIZE,
)

/** One page of results plus the cursor for the next-older page (null = last page). */
data class ActionLogPage(
    val rows: List<ActionLogRow>,
    val nextCursor: ActionLogCursor?,
)

/**
 * Opaque keyset cursor over `(created_at, id)`. Encoded as
 * `base64url("<createdAtEpochMicros>|<id>")`. It is opaque to the user but
 * NOT a security boundary — it only encodes a sort position over data the
 * admin is already authorized to read. A malformed token decodes to `null`
 * (→ first page), never throws (design.md D1).
 */
data class ActionLogCursor(
    val createdAt: Instant,
    val id: UUID,
) {
    fun encode(): String {
        val raw = "${createdAt.toEpochMicros()}|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): ActionLogCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                val sep = raw.indexOf('|')
                if (sep <= 0 || sep == raw.length - 1) return null
                val micros = raw.substring(0, sep).toLong()
                val id = UUID.fromString(raw.substring(sep + 1))
                ActionLogCursor(instantFromEpochMicros(micros), id)
            } catch (_: IllegalArgumentException) {
                // Not valid base64url, not a parseable Long, or not a UUID.
                null
            }
        }

        private fun Instant.toEpochMicros(): Long = epochSecond * 1_000_000 + nano / 1_000

        private fun instantFromEpochMicros(micros: Long): Instant =
            Instant.ofEpochSecond(
                Math.floorDiv(micros, 1_000_000),
                Math.floorMod(micros, 1_000_000) * 1_000L,
            )
    }
}
