package id.nearyou.data.repository

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Post-replies repository — the block-aware write + list surface consumed by
 * `POST /replies`, `GET /replies`, `DELETE /replies/{reply_id}`.
 *
 * Idempotency / opaqueness at the HTTP boundary:
 *  - `insert` always produces a row (no ON CONFLICT — PK is a fresh UUID).
 *  - `listByPost` excludes soft-deleted rows, shadow-banned authors (via
 *    `visible_users`), and bidirectionally-blocked authors. Auto-hidden replies
 *    remain visible to their own author and are hidden from everyone else.
 *  - `softDeleteOwn` UPDATEs with a `deleted_at IS NULL AND author_id = ?` guard
 *    so repeat calls are idempotent and non-author DELETE calls are no-ops. The
 *    route ALWAYS returns 204 regardless of affected row count.
 *  - `resolveVisiblePost` is the shared visibility check used by POST and GET
 *    (NOT by DELETE — the caller may always clean up their own reply, even if
 *    the parent post is now block-hidden from them).
 *
 * NEVER execute a hard `DELETE FROM post_replies` from any implementation of
 * this interface. Soft-delete only. The single legitimate hard-delete site is
 * the tombstone/cascade worker (separate change), which is NOT a consumer of
 * this interface.
 */
interface PostReplyRepository {
    /**
     * `INSERT INTO post_replies (post_id, author_id, content) VALUES (?, ?, ?)
     *  RETURNING <full row>`. Returns the newly inserted row's snapshot.
     */
    fun insert(
        postId: UUID,
        authorId: UUID,
        content: String,
    ): PostReplyRow

    /**
     * Canonical reply-list query — keyset on `(created_at DESC, id DESC)` via
     * `post_replies_post_idx`. Applies:
     *  - shadow-ban exclusion via `JOIN visible_users`,
     *  - bidirectional `user_blocks` NOT-IN on the reply author,
     *  - `deleted_at IS NULL`,
     *  - `is_auto_hidden = FALSE OR author_id = :viewer` (author bypass).
     *
     * Returns up to [limit] rows. Caller is responsible for the probe-row trick.
     */
    fun listByPost(
        postId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorReplyId: UUID?,
        limit: Int,
    ): List<PostReplyRow>

    /**
     * `UPDATE post_replies SET deleted_at = NOW() WHERE id = ? AND author_id = ?
     *  AND deleted_at IS NULL`. Ignores rowcount. Does NOT throw on zero rows;
     *  does NOT resolve parent-post visibility.
     */
    fun softDeleteOwn(
        replyId: UUID,
        authorId: UUID,
    )

    /**
     * Resolve the parent post through `visible_posts` with bidirectional
     * `user_blocks` NOT-IN subqueries against [viewerId]. Returns the post id
     * when visible, or `null` for any of: missing / soft-deleted / auto-hidden
     * / caller-blocked-author / author-blocked-caller. The caller maps `null`
     * to `PostNotFoundException`; the endpoint MUST NOT distinguish the cases
     * on the wire (identical opaque 404 envelope to the V7 like endpoint).
     */
    fun resolveVisiblePost(
        postId: UUID,
        viewerId: UUID,
    ): UUID?

    /**
     * Transactional variant of [insert] — runs on the caller-supplied
     * [Connection] so the INSERT rides the primary action's open transaction
     * alongside a same-TX notification emit.
     */
    fun insertInTx(
        conn: Connection,
        postId: UUID,
        authorId: UUID,
        content: String,
    ): PostReplyRow

    /**
     * Transactional parent-post author lookup used by the reply emit path to
     * address the `post_replied` notification. Returns `null` on missing /
     * soft-deleted parent post (the INSERT would already have failed its FK,
     * so this is belt-and-suspenders).
     */
    fun loadParentAuthorId(
        conn: Connection,
        parentPostId: UUID,
    ): UUID?
}

data class PostReplyRow(
    val id: UUID,
    val postId: UUID,
    val authorId: UUID,
    val content: String,
    val isAutoHidden: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val deletedAt: Instant?,
)
