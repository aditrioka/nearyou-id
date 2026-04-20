package id.nearyou.app.infra.repo

import java.sql.Connection
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
}
