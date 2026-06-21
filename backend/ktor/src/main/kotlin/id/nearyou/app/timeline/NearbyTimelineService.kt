package id.nearyou.app.timeline

import id.nearyou.app.common.Cursor
import id.nearyou.app.infra.repo.PostsTimelineRepository
import id.nearyou.app.infra.repo.TimelineRow
import id.nearyou.app.post.LocationOutOfBoundsException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Nearby timeline orchestration: validate envelope + radius → repository call → assemble
 * page with `next_cursor`. The repository owns the SQL; this service owns shape and bounds.
 *
 * Bounds policy mirrors `post-creation`: same envelope constants, same error code
 * `location_out_of_bounds`. Radius is a discrete Premium-gated set (the 10/20/50/100 km
 * slider per `mobile-nearby-radius-slider`); a value not in `ALLOWED_RADII_M` is
 * `radius_out_of_bounds`. The Free-vs-Premium tier gate (Free → only `FREE_RADIUS_M`) is
 * enforced at the route (it owns the auth principal), not here.
 */
class NearbyTimelineService(
    private val timeline: PostsTimelineRepository,
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun nearby(
        viewerId: UUID,
        viewerLat: Double,
        viewerLng: Double,
        radiusMeters: Int,
        cursor: Cursor?,
    ): NearbyPage {
        if (viewerLat !in LAT_MIN..LAT_MAX || viewerLng !in LNG_MIN..LNG_MAX) {
            throw LocationOutOfBoundsException(viewerLat, viewerLng)
        }
        if (radiusMeters !in ALLOWED_RADII_M) {
            throw RadiusOutOfBoundsException(radiusMeters)
        }
        val rows =
            withContext(dbDispatcher) {
                timeline.nearby(
                    viewerId = viewerId,
                    viewerLat = viewerLat,
                    viewerLng = viewerLng,
                    radiusMeters = radiusMeters,
                    cursorCreatedAt = cursor?.createdAt,
                    cursorPostId = cursor?.id,
                    limit = PAGE_SIZE + 1,
                )
            }
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

        /**
         * The discrete Nearby filter positions in metres — the 10/20/50/100 km slider per
         * `docs/02-Product.md` § Nearby Timeline (`mobile-nearby-radius-slider`). Replaces the
         * former continuous `[100, 50000]` range; a radius not in this set is `radius_out_of_bounds`.
         */
        val ALLOWED_RADII_M: Set<Int> = setOf(10_000, 20_000, 50_000, 100_000)

        /**
         * The single radius a Free viewer may use. Every other `ALLOWED_RADII_M` member is
         * Premium-only (`docs/01-Business.md`:22 — "Free 20km fixed"); the route enforces the gate.
         */
        const val FREE_RADIUS_M: Int = 20_000

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
    RuntimeException("radius $radius not in allowed set ${NearbyTimelineService.ALLOWED_RADII_M}")

/**
 * Pure hide-distance decision (hide-distance capability). The Nearby distance NUMBER is shown
 * only when NEITHER the post author nor the viewer is effectively hiding — symmetric: author-on
 * OR viewer-on suppresses it. Returns the raw meters when shown, or `null` when suppressed; the
 * route omits a `null` `distanceM` from the wire via the app-wide `explicitNulls = false`.
 *
 * This stays a small pure function (no DB, no fuzzing) so the symmetric rule is unit-testable in
 * isolation; the `:shared:distance` `DistanceRenderer.render(Double)` remains a pure formatter
 * and is NOT given a hidden mode (design D2).
 */
fun effectiveDistanceMeters(
    rawMeters: Double,
    authorHidesDistance: Boolean,
    viewerHidesDistance: Boolean,
): Double? = if (authorHidesDistance || viewerHidesDistance) null else rawMeters
