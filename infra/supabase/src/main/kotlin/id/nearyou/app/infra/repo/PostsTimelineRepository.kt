package id.nearyou.app.infra.repo

import java.time.Instant
import java.util.UUID

data class TimelineRow(
    val id: UUID,
    val authorId: UUID,
    // Author display identity (NOT NULL since V2): visible_users on the visible arm; raw
    // users on the own-content self arm, whose rows are always the viewer's own
    // (shadow-ban-feed-self-visibility).
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val createdAt: Instant,
    val likedByViewer: Boolean,
    val replyCount: Int,
    val cityName: String? = null,
    // Author's effective hide-distance preference, projected from the per-arm author join
    // (hide-distance capability): `hide_distance_opt_in AND subscription_status IN
    // ('premium_active','premium_billing_retry')`. Defaulted FALSE so Following/Global rows
    // (which carry no distance) and existing constructors are unaffected. Combined with the
    // viewer's preference by `effectiveDistanceMeters` to decide Nearby distance suppression.
    val authorHidesDistance: Boolean = false,
)

interface PostsTimelineRepository {
    /**
     * Canonical Nearby query — returns up to [limit] rows within [radiusMeters] of the
     * viewer: `visible_posts` with bidirectional `user_blocks` exclusion for everyone
     * else's posts, UNION ALL'd with the viewer's own-content self arm
     * (shadow-ban-feed-self-visibility — a shadow-banned author still sees their own
     * posts). Keyset on `(created_at DESC, id DESC)`.
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
