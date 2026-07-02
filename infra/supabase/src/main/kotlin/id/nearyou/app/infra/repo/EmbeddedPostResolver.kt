package id.nearyou.app.infra.repo

import java.time.Instant
import java.util.UUID

/**
 * Coordinate-free projection used to build a `chat_messages.embedded_post_snapshot` when a user
 * shares a post into a DM (`chat-embedded-posts`). Mirrors the shipped `single-post-read`
 * projection ([SinglePostRow]) — author handle + display name, `content`, `cityName`, `createdAt`,
 * `editedAt` — and DELIBERATELY omits `latitude` / `longitude` / `display_location` / the author
 * UUID (spatial-fuzzing critical invariant: a snapshot is a NEW serialization path that must not
 * regress `display_location`-only).
 *
 * [latestEditId] is the post's most-recent `post_edits.id` at resolution time (NULL when the post
 * has never been edited) — the "version-at-share-time" anchor persisted to
 * `chat_messages.embedded_post_edit_id`. It is the FK-integral version handle the mobile thread
 * compares against the live post to decide whether to show the "diedit sejak dibagikan" banner.
 * [editedAt] is the matching `post_edits.edited_at` timestamp (the snapshot's own "Diedit" signal);
 * the two are distinct (design D2): the anchor is the edit-row id, `editedAt` is the timestamp.
 */
data class ResolvedEmbeddedPost(
    val postId: UUID,
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val cityName: String?,
    val createdAt: Instant,
    val editedAt: Instant?,
    val latestEditId: UUID?,
)

fun interface EmbeddedPostResolver {
    /**
     * Resolve [postId] **for [senderId] as viewer** through the shipped visibility rules — the
     * `visible_posts` shadow-ban view, the bidirectional `user_blocks` NOT-IN exclusion, and the
     * auto-hide / soft-delete filters — projecting the coordinate-free snapshot fields plus the
     * latest-edit anchor. Returns `null` for every "not visible to [senderId]" cause (unknown /
     * soft-deleted / author-shadow-banned-and-sender-is-not-author / auto-hidden / blocked-either-
     * direction), which the caller maps to the project constant-404 (forbidden indistinguishable
     * from absent). A shadow-banned author still resolves their OWN non-deleted post via the
     * own-content self arm.
     */
    fun resolveForSender(
        senderId: UUID,
        postId: UUID,
    ): ResolvedEmbeddedPost?
}
