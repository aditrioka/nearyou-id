package id.nearyou.app.infra.repo

import java.time.Instant
import java.util.UUID

/**
 * No-PII single-post projection for `GET /api/v1/posts/{post_id}` (single-post-read capability).
 *
 * Deliberately omits the `authorId` / `latitude` / `longitude` that [TimelineRow] carries: this
 * read backs a notification deep-link header and must not leak the author UUID or coordinates
 * (issue #202; the `PostDetailRoute` no-coordinates/no-author-UUID discipline). `cityName` is
 * nullable (legacy rows / polygon-coverage gaps) — the HTTP layer maps `null` to `""`.
 */
data class SinglePostRow(
    val id: UUID,
    // Author display identity (NOT NULL since V2): visible_users on the visible arm; raw users on
    // the own-content self arm, whose row is always the viewer's own (shadow-ban-feed-self-visibility).
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val cityName: String?,
    val createdAt: Instant,
    val likedByViewer: Boolean,
    val replyCount: Int,
)

interface SinglePostRepository {
    /**
     * Resolve one viewer-visible post by id, projecting the no-PII display fields. Returns `null`
     * for every "not visible to [viewerId]" cause — unknown / soft-deleted / author shadow-banned /
     * auto-hidden / blocked-either-direction — which the caller maps to a single constant
     * `404 post_not_found` (the leak-safe collapse; see the `single-post-read` spec). A shadow-banned
     * author still resolves their OWN non-deleted post via the own-content self arm.
     */
    fun findById(
        viewerId: UUID,
        postId: UUID,
    ): SinglePostRow?
}
