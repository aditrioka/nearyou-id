package id.nearyou.app.engagement

import id.nearyou.data.repository.PostLikeRepository
import java.util.UUID

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
 */
class LikeService(
    private val likes: PostLikeRepository,
) {
    fun like(
        postId: UUID,
        userId: UUID,
    ) {
        likes.resolveVisiblePost(postId, userId) ?: throw PostNotFoundException()
        likes.like(postId, userId)
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
