package id.nearyou.app.admin.usermanagement

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Read-only repository for the per-user admin profile + history page
 * (`admin-user-management` capability — board frame 6, `GET /admin/users/{id}`).
 *
 * Single responsibility: the identity + current moderation state read, plus the
 * two history reads (`admin_actions_log` affecting the user + `username_history`)
 * — distinct from [id.nearyou.app.admin.moderation.UserModerationRepository],
 * which owns the state-MUTATING transactions (design D7).
 *
 *  - Every query is a parameterized JDBC `PreparedStatement` — never string-
 *    interpolated, so a `{id}` carrying SQL metacharacters can never be executed.
 *  - Raw reads of `users` / `admin_actions_log` / `username_history` are
 *    permitted: this is the admin module (package `id.nearyou.app.admin.*`),
 *    exempt from the `RawFromPostsRule` / `BlockExclusionJoinRule` Detekt rules.
 *  - Exposes NO write path — the profile GET route mutates nothing.
 */
class UserProfileRepository(
    private val dataSource: DataSource,
) {
    /** Load the target's identity + current moderation state; null → the route
     *  renders the inline empty-state (200, never 404). */
    fun loadProfile(userId: UUID): UserProfile? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, username, display_name, subscription_status, created_at,
                       private_profile_opt_in, is_banned, suspended_until, is_shadow_banned
                  FROM users
                 WHERE id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    UserProfile(
                        id = rs.getObject("id", UUID::class.java),
                        username = rs.getString("username"),
                        displayName = rs.getString("display_name"),
                        // `is_premium` (spec) maps to the shipped enum column
                        // subscription_status (no `is_premium` column exists).
                        subscriptionStatus = rs.getString("subscription_status"),
                        createdAt = rs.getTimestamp("created_at")?.toInstant(),
                        privateProfileOptIn = rs.getBoolean("private_profile_opt_in"),
                        isBanned = rs.getBoolean("is_banned"),
                        suspendedUntil = rs.getTimestamp("suspended_until")?.toInstant(),
                        isShadowBanned = rs.getBoolean("is_shadow_banned"),
                    )
                }
            }
        }

    /**
     * The target user's admin-action history, newest-first, with the acting
     * admin resolved to a human-readable identity (JOIN `admin_users`) — the same
     * read shape the audit-log viewer renders. Two arms: rows targeting the user
     * directly (`target_type = 'user' AND target_id = {id}`), and rows keyed to
     * another object (a queue item, an appeal, a deletion request) whose
     * `after_state.user_id` names the user as the affected account. The second
     * arm is served by the V41 partial index `admin_actions_after_user_idx`.
     */
    fun loadAdminActionHistory(userId: UUID): List<ProfileActionRow> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(ACTION_HISTORY_SQL).use { ps ->
                ps.setString(1, userId.toString())
                ps.setString(2, userId.toString())
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ProfileActionRow(
                                    createdAt = rs.getTimestamp("created_at").toInstant(),
                                    adminDisplayName = rs.getString("admin_display_name"),
                                    actionType = rs.getString("action_type"),
                                    reason = rs.getString("reason"),
                                    beforeState = rs.getString("before_state"),
                                    afterState = rs.getString("after_state"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /** The target's username-change history (`username_history`), newest-first. */
    fun loadUsernameHistory(userId: UUID): List<UsernameChangeRow> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT old_username, new_username, changed_at
                  FROM username_history
                 WHERE user_id = ?
                 ORDER BY changed_at DESC
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                UsernameChangeRow(
                                    oldUsername = rs.getString("old_username"),
                                    newUsername = rs.getString("new_username"),
                                    changedAt = rs.getTimestamp("changed_at").toInstant(),
                                ),
                            )
                        }
                    }
                }
            }
        }

    companion object {
        /** The two history arms (both bound to the user id). Exposed so the index-served
         *  test plans exactly this predicate against `admin_actions_log`. */
        internal const val ACTION_HISTORY_PREDICATE =
            "(l.target_type = 'user' AND l.target_id = ?) OR (l.after_state ->> 'user_id') = ?"

        private val ACTION_HISTORY_SQL =
            """
            SELECT l.created_at, u.display_name AS admin_display_name,
                   l.action_type, l.reason,
                   l.before_state::text AS before_state,
                   l.after_state::text AS after_state
              FROM admin_actions_log l
              JOIN admin_users u ON u.id = l.admin_id
             WHERE $ACTION_HISTORY_PREDICATE
             ORDER BY l.created_at DESC, l.id DESC
            """.trimIndent()
    }
}

/** Identity + current moderation state for the profile block. */
data class UserProfile(
    val id: UUID,
    val username: String,
    val displayName: String,
    val subscriptionStatus: String,
    val createdAt: Instant?,
    val privateProfileOptIn: Boolean,
    val isBanned: Boolean,
    val suspendedUntil: Instant?,
    val isShadowBanned: Boolean,
) {
    /** Premium = any non-`free` subscription tier. */
    val isPremium: Boolean get() = subscriptionStatus != "free"
}

/** One admin-action history row (a subset of the audit-log viewer's columns). */
data class ProfileActionRow(
    val createdAt: Instant,
    val adminDisplayName: String,
    val actionType: String,
    val reason: String?,
    val beforeState: String?,
    val afterState: String?,
)

/** One username-change history row. */
data class UsernameChangeRow(
    val oldUsername: String,
    val newUsername: String,
    val changedAt: Instant,
)
