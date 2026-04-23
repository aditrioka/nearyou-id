package id.nearyou.app.follow

import id.nearyou.app.common.Cursor
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.FollowListRow
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationType
import id.nearyou.data.repository.UserFollowsRepository
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import javax.sql.DataSource

/**
 * Follow lifecycle: create / delete / list. Self-follow is rejected at this layer with
 * [CannotFollowSelfException] before any DB round-trip (the V6 CHECK constraint serves
 * as defense-in-depth). All mutual-block and FK-violation exception types come from the
 * repository; the route handler maps them to HTTP error codes.
 *
 * List methods own the `page-size + 1` probe pattern for keyset pagination — same shape
 * `BlockService.listOutbound` uses.
 *
 * V10 notification coupling: `follow` opens a single transaction for the
 * `follows` INSERT + `followed` emit. Re-follow (ON CONFLICT no-op) does NOT
 * emit — only the new-edge transition does. Unfollow deliberately has no
 * counter-notification (no `unfollowed` type in the enum). Dispatch runs
 * AFTER commit.
 */
class FollowService(
    private val dataSource: DataSource,
    private val follows: UserFollowsRepository,
    private val notifications: NotificationEmitter,
    private val dispatcher: NotificationDispatcher,
) {
    fun follow(
        followerId: UUID,
        followeeId: UUID,
    ) {
        if (followerId == followeeId) throw CannotFollowSelfException()
        var emittedId: UUID? = null
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val inserted = follows.followInTx(conn, followerId, followeeId)
                if (inserted) {
                    emittedId =
                        notifications.emit(
                            conn = conn,
                            recipientId = followeeId,
                            actorUserId = followerId,
                            type = NotificationType.FOLLOWED,
                            targetType = null,
                            targetId = null,
                            bodyData = buildJsonObject {},
                        )
                }
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }
        emittedId?.let(dispatcher::dispatch)
    }

    fun unfollow(
        followerId: UUID,
        followeeId: UUID,
    ) {
        follows.unfollow(followerId, followeeId)
    }

    fun listFollowers(
        profileId: UUID,
        viewerId: UUID,
        cursor: Cursor?,
    ): FollowPage =
        paginate { limit ->
            follows.listFollowers(
                profileId = profileId,
                viewerId = viewerId,
                cursorCreatedAt = cursor?.createdAt,
                cursorUserId = cursor?.id,
                limit = limit,
            )
        }

    fun listFollowing(
        profileId: UUID,
        viewerId: UUID,
        cursor: Cursor?,
    ): FollowPage =
        paginate { limit ->
            follows.listFollowing(
                profileId = profileId,
                viewerId = viewerId,
                cursorCreatedAt = cursor?.createdAt,
                cursorUserId = cursor?.id,
                limit = limit,
            )
        }

    private fun paginate(fetch: (Int) -> List<FollowListRow>): FollowPage {
        val rows = fetch(PAGE_SIZE + 1)
        return if (rows.size > PAGE_SIZE) {
            val page = rows.take(PAGE_SIZE)
            val last = page.last()
            FollowPage(rows = page, nextCursor = Cursor(createdAt = last.createdAt, id = last.userId))
        } else {
            FollowPage(rows = rows, nextCursor = null)
        }
    }

    companion object {
        const val PAGE_SIZE: Int = 30
    }
}

data class FollowPage(
    val rows: List<FollowListRow>,
    val nextCursor: Cursor?,
)

class CannotFollowSelfException : RuntimeException("cannot follow self")
