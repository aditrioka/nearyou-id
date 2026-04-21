package id.nearyou.app.engagement

import id.nearyou.data.repository.PostReplyRepository
import id.nearyou.data.repository.PostReplyRow
import java.time.Instant
import java.util.UUID

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
 */
class ReplyService(
    private val replies: PostReplyRepository,
) {
    fun post(
        postId: UUID,
        authorId: UUID,
        content: String,
    ): PostReplyRow {
        replies.resolveVisiblePost(postId, authorId) ?: throw PostNotFoundException()
        return replies.insert(postId = postId, authorId = authorId, content = content)
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
