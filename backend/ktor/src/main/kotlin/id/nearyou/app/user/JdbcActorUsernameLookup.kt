package id.nearyou.app.user

import id.nearyou.app.lint.AllowMissingBlockJoin
import id.nearyou.data.repository.ActorUsernameLookup
import java.util.UUID
import javax.sql.DataSource

/**
 * Production [ActorUsernameLookup] reading from the `visible_users` view
 * (NOT raw `users`) so shadow-banned and hard-deleted actors return `null`.
 *
 * Per `openspec/changes/fcm-push-dispatch/design.md` D13: the visible_users
 * read masks the actor's handle from the FCM push body, aligning with
 * industry shadow-ban convention (Reddit / HN / X — shadow-banned actors
 * produce no recipient signal). The downstream `PushCopy.bodyFor(notification,
 * actorUsername=null)` renders the actor-less fallback (e.g.,
 * `"Seseorang menyukai post-mu"`) when the lookup returns null.
 */
class JdbcActorUsernameLookup(
    private val dataSource: DataSource,
) : ActorUsernameLookup {
    /**
     * Single PK seek. Allowlisted from `BlockExclusionJoinRule` because:
     *
     *  1. The recipient is the known audience and was already cleared via
     *     the `NotificationEmitter` bidirectional block-check at emit-time
     *     (in-app-notifications spec, NotificationEmitter write-path
     *     requirement). Re-applying the block-join here would re-litigate an
     *     already-gated relationship.
     *  2. The read goes through `visible_users` which applies shadow-ban +
     *     hard-delete filtering — that is the active privacy mechanism on
     *     this surface (see fcm-push-dispatch design D13). Reads to
     *     `visible_users` in this module are restricted to the actor-username
     *     path.
     */
    @AllowMissingBlockJoin(
        "Notification-rendering actor-username lookup (PK seek on visible_users). " +
            "Two reasons block-exclusion does not apply: (1) the recipient is the known " +
            "audience and was already cleared via the NotificationEmitter bidirectional " +
            "block-check at emit-time (see in-app-notifications spec, NotificationEmitter " +
            "write-path requirement) — re-applying the block-join here would re-litigate " +
            "an already-gated relationship; (2) the read goes through visible_users which " +
            "applies shadow-ban filtering — that is the active privacy filter on this " +
            "surface (see fcm-push-dispatch design D13). Reads to visible_users in this " +
            "module are restricted to the actor-username path.",
    )
    override fun lookup(actorUserId: UUID?): String? {
        if (actorUserId == null) return null
        dataSource.connection.use { conn ->
            conn.prepareStatement(SQL_SELECT_USERNAME).use { ps ->
                ps.setObject(1, actorUserId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.getString("username")
                }
            }
        }
    }

    private companion object {
        const val SQL_SELECT_USERNAME = """
            SELECT username FROM visible_users WHERE id = ? LIMIT 1
        """
    }
}
