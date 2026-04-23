package id.nearyou.app.engagement

import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationType
import id.nearyou.data.repository.PostLikeRepository
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import javax.sql.DataSource

/**
 * Like lifecycle: create / remove / count. Both `like` and `countLikes` resolve the
 * target post through `PostLikeRepository.resolveVisiblePost` BEFORE their real work
 * and throw [PostNotFoundException] on null — the HTTP layer maps that to
 * `404 post_not_found` (one code for missing, soft-deleted, or block-hidden).
 *
 * `unlike` deliberately skips the visibility resolve: the caller is always allowed to
 * remove their OWN like row regardless of current block state (post-likes spec
 * requirement "DELETE /like is a pure no-op on zero rows"). The PK scopes the DELETE
 * to the caller's own row, so there is no cross-user side effect to protect against.
 *
 * V10 notification coupling: `like` opens a single transaction that spans the
 * `post_likes` INSERT + the `post_liked` notification INSERT. If the emit fails,
 * the like itself rolls back (in-app-notifications design Decision 1). Re-likes
 * (ON CONFLICT no-op) do NOT emit — only the not-liked → liked transition does.
 * Dispatch runs AFTER commit so a future FCM dispatcher never observes a
 * notification that ended up rolled back.
 */
class LikeService(
    private val dataSource: DataSource,
    private val likes: PostLikeRepository,
    private val notifications: NotificationEmitter,
    private val dispatcher: NotificationDispatcher,
) {
    fun like(
        postId: UUID,
        userId: UUID,
    ) {
        likes.resolveVisiblePost(postId, userId) ?: throw PostNotFoundException()
        var emittedId: UUID? = null
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val inserted = likes.likeInTx(conn, postId, userId)
                if (inserted) {
                    val target = likes.loadPostAuthorAndExcerpt(conn, postId)
                    if (target != null) {
                        emittedId =
                            notifications.emit(
                                conn = conn,
                                recipientId = target.authorId,
                                actorUserId = userId,
                                type = NotificationType.POST_LIKED,
                                targetType = "post",
                                targetId = postId,
                                bodyData =
                                    buildJsonObject {
                                        put("post_excerpt", JsonPrimitive(target.excerpt))
                                    },
                            )
                    }
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

    fun unlike(
        postId: UUID,
        userId: UUID,
    ) {
        likes.unlike(postId, userId)
    }

    fun countLikes(
        postId: UUID,
        viewerId: UUID,
    ): Long {
        likes.resolveVisiblePost(postId, viewerId) ?: throw PostNotFoundException()
        return likes.countVisibleLikes(postId)
    }
}

class PostNotFoundException : RuntimeException("post not found or not visible to caller")
