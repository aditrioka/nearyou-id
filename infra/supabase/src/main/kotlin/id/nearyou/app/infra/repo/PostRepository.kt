package id.nearyou.app.infra.repo

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * NOT NULL input needed to INSERT a new `posts` row. Nullable columns
 * (`city_name`, `city_match_type`, `image_id`, `updated_at`, `deleted_at`) and
 * DB-defaulted columns (`is_auto_hidden`, `created_at`) are left to Postgres.
 */
data class NewPostRow(
    val id: UUID,
    val authorId: UUID,
    val content: String,
    val actualLat: Double,
    val actualLng: Double,
    val displayLat: Double,
    val displayLng: Double,
)

/**
 * The post fields the edit transaction reads under the `FOR UPDATE` row lock — the
 * current `content` (for the no-op diff per design D9) plus the `author_id` (already
 * matched in the WHERE, returned for parity with docs/05 § Transactional Atomicity).
 * `location_snapshot` is copied straight from the locked row by the snapshot INSERT,
 * so no location field is surfaced here.
 */
data class LockedPostForEdit(
    val id: UUID,
    val content: String,
    val authorId: UUID,
)

/**
 * Result of the author-scoped disambiguation read (design D7) run when the
 * `FOR UPDATE` select returns 0 rows: distinguishes "author's own post, merely past
 * the 30-minute window" (→ 409 `edit_window_expired`) from every other 0-row cause
 * (non-author / non-existent / author's own soft-deleted → uniform 404).
 */
data class PostEditEligibility(
    val createdAt: Instant,
    val softDeleted: Boolean,
)

interface PostRepository {
    /**
     * Insert a new `posts` row inside the caller's transaction. Returns the
     * row id. Throws `java.sql.SQLException` on FK / CHECK / unique violations
     * — callers propagate to StatusPages which renders the 500 envelope.
     */
    fun create(
        conn: Connection,
        row: NewPostRow,
    ): UUID

    /**
     * Select-for-edit: locks the author's own, in-window, non-deleted post row
     * (`docs/05` § Transactional Atomicity, verbatim). Returns the locked row or
     * `null` (0 rows). Takes the open transaction [Connection] — the lock is held
     * until the caller commits/rolls back. A `null` return is disambiguated by
     * [eligibilityForEdit] at the service layer (design D7).
     */
    fun selectForEdit(
        conn: Connection,
        postId: UUID,
        authorId: UUID,
    ): LockedPostForEdit?

    /**
     * Author-scoped disambiguation for a 0-row [selectForEdit] (design D7). Returns
     * the post's `created_at` + soft-delete flag IFF the post exists AND is authored
     * by [authorId]; `null` otherwise (non-author / non-existent — the uniform-404
     * cause). Author-scoped, so it can never reveal another user's post.
     */
    fun eligibilityForEdit(
        conn: Connection,
        postId: UUID,
        authorId: UUID,
    ): PostEditEligibility?

    /**
     * Insert the BEFORE-edit snapshot into `post_edits` inside the caller's
     * transaction (`docs/05` § Transactional Atomicity). Copies the post's current
     * `content` + `actual_location` + `author_id` via an `INSERT ... SELECT FROM
     * posts WHERE id`, stamping `edited_at = clock_timestamp()`. MUST run before
     * [updateContent] in the same transaction so the snapshot captures the
     * pre-edit content. Throws `java.sql.SQLException` (the temporal-index
     * `unique_violation` on the sub-microsecond collision edge is caught + retried
     * once by the service).
     */
    fun insertEditSnapshot(
        conn: Connection,
        postId: UUID,
    )

    /**
     * Apply the new content to the post (`UPDATE posts SET content=:new,
     * updated_at=NOW() WHERE id=:id`) inside the caller's transaction. MUST run
     * after [insertEditSnapshot].
     */
    fun updateContent(
        conn: Connection,
        postId: UUID,
        newContent: String,
    )
}
