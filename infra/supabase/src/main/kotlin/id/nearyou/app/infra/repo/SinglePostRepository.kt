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
 *
 * `editedAt` is the timestamp of the post's most recent `post_edits` row (`MAX(edited_at)`), or
 * `null` when the post has never been edited. It is the only edited-signal the mobile client uses to
 * render the "Diedit" label (`mobile-post-editing` capability); it carries no PII/coordinate and is
 * derived from edit-history existence, NOT from `posts.updated_at`.
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
    // True iff the calling viewer authored this post (computed from author_id, which is NEVER projected as
    // a UUID) — gates the mobile-post-editing edit affordance. Defaulted for non-DB construction.
    val isAuthor: Boolean = false,
    // Most-recent post_edits.edited_at (NULL ⇒ never edited). Derived from the resolved post row, so an
    // invisible/blocked post never yields an edit-existence signal. Defaulted for non-DB construction.
    val editedAt: Instant? = null,
    // Cloudflare image id of an attached image (`posts.image_id`), or null for a text-only post
    // (image-attached-posts). Projected in BOTH UNION arms so an author reads their own image on the
    // self arm. The route maps it to the public delivery URL via the shared builder; the raw id never
    // reaches the wire. Defaulted for non-DB construction.
    val imageId: String? = null,
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
