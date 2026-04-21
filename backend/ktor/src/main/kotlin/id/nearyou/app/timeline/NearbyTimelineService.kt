package id.nearyou.app.timeline

import id.nearyou.app.common.Cursor
import id.nearyou.app.infra.repo.PostsTimelineRepository
import id.nearyou.app.infra.repo.TimelineRow
import id.nearyou.app.post.LocationOutOfBoundsException
import java.util.UUID

/**
 * Nearby timeline orchestration: validate envelope + radius → repository call → assemble
 * page with `next_cursor`. The repository owns the SQL; this service owns shape and bounds.
 *
 * Bounds policy mirrors `post-creation`: same envelope constants, same error code
 * `location_out_of_bounds`. Radius bounds are timeline-specific (100 m floor matches the
 * jitter band; 50 km ceiling matches docs §15 to prevent nation-wide scans).
 */
class NearbyTimelineService(
    private val timeline: PostsTimelineRepository,
) {
    fun nearby(
        viewerId: UUID,
        viewerLat: Double,
        viewerLng: Double,
        radiusMeters: Int,
        cursor: Cursor?,
    ): NearbyPage {
        if (viewerLat !in LAT_MIN..LAT_MAX || viewerLng !in LNG_MIN..LNG_MAX) {
            throw LocationOutOfBoundsException(viewerLat, viewerLng)
        }
        if (radiusMeters !in RADIUS_MIN..RADIUS_MAX) {
            throw RadiusOutOfBoundsException(radiusMeters)
        }
        val rows =
            timeline.nearby(
                viewerId = viewerId,
                viewerLat = viewerLat,
                viewerLng = viewerLng,
                radiusMeters = radiusMeters,
                cursorCreatedAt = cursor?.createdAt,
                cursorPostId = cursor?.id,
                limit = PAGE_SIZE + 1,
            )
        return if (rows.size > PAGE_SIZE) {
            val page = rows.take(PAGE_SIZE)
            val last = page.last()
            NearbyPage(rows = page, nextCursor = Cursor(createdAt = last.createdAt, id = last.id))
        } else {
            NearbyPage(rows = rows, nextCursor = null)
        }
    }

    companion object {
        const val PAGE_SIZE: Int = 30
        const val RADIUS_MIN: Int = 100
        const val RADIUS_MAX: Int = 50_000

        // Envelope constants mirror post-creation (id.nearyou.app.post.CreatePostService)
        // intentionally — kept duplicated rather than centralized so each capability
        // states its own bound, and a future per-capability divergence (e.g., admin
        // endpoints with a wider envelope) doesn't require a refactor.
        const val LAT_MIN: Double = -11.0
        const val LAT_MAX: Double = 6.5
        const val LNG_MIN: Double = 94.0
        const val LNG_MAX: Double = 142.0
    }
}

data class NearbyPage(
    val rows: List<TimelineRow>,
    val nextCursor: Cursor?,
)

class RadiusOutOfBoundsException(val radius: Int) :
    RuntimeException("radius $radius outside [${NearbyTimelineService.RADIUS_MIN}, ${NearbyTimelineService.RADIUS_MAX}]")
