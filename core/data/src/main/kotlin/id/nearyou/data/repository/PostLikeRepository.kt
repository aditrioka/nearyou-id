package id.nearyou.data.repository

import java.sql.Connection
import java.util.UUID

/**
 * Post-likes repository — the visibility-aware `(post_id, user_id)` write + count
 * surface consumed by `POST /like`, `DELETE /like`, and `GET /likes/count`.
 *
 * All methods are idempotent / side-effect-free at the HTTP boundary:
 *  - `like` uses `ON CONFLICT DO NOTHING`.
 *  - `unlike` is a no-op on zero rows (no exception).
 *  - `countVisibleLikes` reads through `visible_users` to exclude shadow-banned
 *    contributors; it does NOT apply viewer-block exclusion by design (see the
 *    `post-likes` spec — deliberate privacy tradeoff).
 *  - `resolveVisiblePost` is the shared visibility check used by BOTH `POST /like`
 *    and `GET /likes/count` before any mutation/read; returns `null` when the post
 *    is missing, soft-deleted, or bidirectionally block-hidden to the viewer.
 */
interface PostLikeRepository {
    /** `INSERT INTO post_likes ... ON CONFLICT (post_id, user_id) DO NOTHING`. */
    fun like(
        postId: UUID,
        userId: UUID,
    )

    /** `DELETE FROM post_likes WHERE post_id = ? AND user_id = ?`. Zero-row no-op. */
    fun unlike(
        postId: UUID,
        userId: UUID,
    )

    /**
     * Shadow-ban-aware like count for [postId]. Does NOT filter by viewer blocks —
     * the count must be a function of `(post_id, shadow-ban state)` only, else a
     * per-viewer delta would leak the caller's private `user_blocks` set.
     */
    fun countVisibleLikes(postId: UUID): Long

    /**
     * Resolve the target post through `visible_posts` with bidirectional
     * `user_blocks` NOT-IN subqueries against [viewerId]. Returns the post id when
     * visible, or `null` for any of: missing post, soft-deleted post, caller
     * blocked the author, author blocked the caller. The caller maps `null` to
     * `PostNotFoundException` — the endpoint MUST NOT distinguish the four cases
     * on the wire (see `post-likes` spec: 404 `post_not_found` is the single code).
     */
    fun resolveVisiblePost(
        postId: UUID,
        viewerId: UUID,
    ): UUID?

    /**
     * Transactional variant of [like] — runs on the caller-supplied
     * [Connection] so the INSERT rides the primary action's open transaction
     * alongside a same-TX notification emit. Returns `true` when a row was
     * inserted (transition "not liked" → "liked"), `false` when `ON CONFLICT
     * DO NOTHING` absorbed the INSERT (re-like is a no-op, so no notification
     * should be emitted).
     */
    fun likeInTx(
        conn: Connection,
        postId: UUID,
        userId: UUID,
    ): Boolean

    /**
     * Transactional author + content-excerpt lookup used by the like emit path
     * to build the `post_liked` notification's `body_data.post_excerpt`. Returns
     * `null` when the post is missing or soft-deleted (the like INSERT would
     * have FK-cascaded away by then — belt-and-suspenders).
     */
    fun loadPostAuthorAndExcerpt(
        conn: Connection,
        postId: UUID,
    ): PostAuthorExcerpt?
}

data class PostAuthorExcerpt(
    val authorId: UUID,
    val excerpt: String,
)
