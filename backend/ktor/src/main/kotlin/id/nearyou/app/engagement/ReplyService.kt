package id.nearyou.app.engagement

import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationType
import id.nearyou.data.repository.PostReplyRepository
import id.nearyou.data.repository.PostReplyRow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Reply lifecycle: create / list / soft-delete.
 *
 * Both `post` and `list` resolve the parent post through
 * [PostReplyRepository.resolveVisiblePost] BEFORE their real work and throw
 * [PostNotFoundException] on null — the HTTP layer maps that to the same opaque
 * `404 post_not_found` envelope the V7 like endpoint uses.
 *
 * `softDelete` deliberately skips the visibility resolve: the caller is always
 * allowed to remove their own reply regardless of the parent post's current
 * visibility (post-replies-v8 design Decision 3 — opaque 204, no case split).
 * The repository's `WHERE id = ? AND author_id = ? AND deleted_at IS NULL`
 * guard makes non-author / already-tombstoned / never-existed all no-ops.
 *
 * V10 notification coupling: `post` opens a single transaction for the reply
 * INSERT + `post_replied` emit. Emit failure rolls back the reply.
 */
class ReplyService(
    private val dataSource: DataSource,
    private val replies: PostReplyRepository,
    private val notifications: NotificationEmitter,
) {
    fun post(
        postId: UUID,
        authorId: UUID,
        content: String,
    ): PostReplyRow {
        replies.resolveVisiblePost(postId, authorId) ?: throw PostNotFoundException()
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val row = replies.insertInTx(conn, postId = postId, authorId = authorId, content = content)
                val parentAuthor = replies.loadParentAuthorId(conn, postId)
                if (parentAuthor != null) {
                    notifications.emit(
                        conn = conn,
                        recipientId = parentAuthor,
                        actorUserId = authorId,
                        type = NotificationType.POST_REPLIED,
                        targetType = "post",
                        targetId = postId,
                        bodyData =
                            buildJsonObject {
                                put("reply_id", JsonPrimitive(row.id.toString()))
                                put("reply_excerpt", JsonPrimitive(row.content.firstCodePoints(80)))
                            },
                    )
                }
                conn.commit()
                return row
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun list(
        postId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorReplyId: UUID?,
        limit: Int,
    ): List<PostReplyRow> {
        replies.resolveVisiblePost(postId, viewerId) ?: throw PostNotFoundException()
        return replies.listByPost(
            postId = postId,
            viewerId = viewerId,
            cursorCreatedAt = cursorCreatedAt,
            cursorReplyId = cursorReplyId,
            limit = limit,
        )
    }

    fun softDelete(
        replyId: UUID,
        authorId: UUID,
    ) {
        replies.softDeleteOwn(replyId, authorId)
    }

    companion object {
        const val PAGE_SIZE: Int = 30
    }
}

/**
 * Local duplicate of the `firstCodePoints` helper in `:infra:supabase`. Kept
 * private here because surrogate-pair-safe truncation is a cross-cutting
 * concern used at two boundaries: the repo (LikeService excerpt loader) and
 * the service (ReplyService reply excerpt). Keeping both avoids a cross-module
 * shared-utils introduction for a 10-line helper.
 */
private fun String.firstCodePoints(n: Int): String {
    if (n <= 0) return ""
    var cpCount = 0
    var i = 0
    while (i < length && cpCount < n) {
        val cp = codePointAt(i)
        i += Character.charCount(cp)
        cpCount++
    }
    return substring(0, i)
}
