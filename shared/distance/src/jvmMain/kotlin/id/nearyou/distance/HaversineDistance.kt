package id.nearyou.distance

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Server-side haversine distance computation. Matches PostGIS `ST_Distance(::geography,
 * ::geography)` to within ~0.5% over the Indonesia envelope and the 100m–50km Nearby radius
 * band — well below the 1km rounding floor in [DistanceRenderer]. Vincenty would be more
 * accurate but adds iterative complexity unnecessary for our use case.
 *
 * The Nearby query computes `distance_m` server-side via `ST_Distance` directly; this helper
 * exists for service-layer assertions, test parity checks, and any future computation that
 * is not driven by a SQL query. Backend code MUST NOT reimplement haversine outside this
 * module — `:shared:distance` is the canonical distance source for both backend and mobile.
 *
 * Sphere radius 6371008.8 m matches PostGIS's WGS84 mean radius default.
 */
object Distance {
    private const val EARTH_RADIUS_M: Double = 6_371_008.8

    fun metersBetween(
        a: LatLng,
        b: LatLng,
    ): Double {
        val lat1 = a.lat * PI / 180.0
        val lat2 = b.lat * PI / 180.0
        val dLat = (b.lat - a.lat) * PI / 180.0
        val dLng = (b.lng - a.lng) * PI / 180.0
        val sinDLat = sin(dLat / 2.0)
        val sinDLng = sin(dLng / 2.0)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLng * sinDLng
        val c = 2.0 * atan2(sqrt(h), sqrt(1.0 - h))
        return EARTH_RADIUS_M * c
    }
}
