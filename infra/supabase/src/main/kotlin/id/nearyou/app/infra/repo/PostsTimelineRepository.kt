package id.nearyou.app.infra.repo

import java.time.Instant
import java.util.UUID

data class TimelineRow(
    val id: UUID,
    val authorId: UUID,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val createdAt: Instant,
    val likedByViewer: Boolean,
)

interface PostsTimelineRepository {
    /**
     * Canonical Nearby query — returns up to [limit] rows from `visible_posts` within
     * [radiusMeters] of the viewer, with bidirectional `user_blocks` exclusion baked in.
     * Keyset on `(created_at DESC, id DESC)`.
     */
    fun nearby(
        viewerId: UUID,
        viewerLat: Double,
        viewerLng: Double,
        radiusMeters: Int,
        cursorCreatedAt: Instant?,
        cursorPostId: UUID?,
        limit: Int,
    ): List<TimelineRow>
}
