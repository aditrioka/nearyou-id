package id.nearyou.app.post

import id.nearyou.app.engagement.PostNotFoundException
import id.nearyou.app.infra.repo.SinglePostRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Maps the [SinglePostRepository] row to the wire [SinglePostResponse] for
 * `GET /api/v1/posts/{post_id}` (`single-post-read` capability).
 *
 * An unresolvable target (the repository returns `null` for unknown / soft-deleted / author
 * shadow-banned / auto-hidden / blocked-either-direction) is mapped to [PostNotFoundException],
 * which the route turns into the single constant `404 post_not_found` (the leak-safe collapse —
 * see [singlePostRoutes]). A shadow-banned author still resolves their own non-deleted post via the
 * repository's own-content self arm. `distanceM` is left `null` (v1 has no viewer-location context).
 */
class PostReadService(
    private val repository: SinglePostRepository,
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun getById(
        viewerId: UUID,
        postId: UUID,
    ): SinglePostResponse {
        val row =
            withContext(dbDispatcher) { repository.findById(viewerId = viewerId, postId = postId) }
                ?: throw PostNotFoundException()
        return SinglePostResponse(
            id = row.id.toString(),
            authorUsername = row.authorUsername,
            authorDisplayName = row.authorDisplayName,
            content = row.content,
            cityName = row.cityName.orEmpty(),
            createdAt = row.createdAt.toString(),
            likedByViewer = row.likedByViewer,
            replyCount = row.replyCount,
        )
    }
}
