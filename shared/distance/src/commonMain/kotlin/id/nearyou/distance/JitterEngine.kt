package id.nearyou.distance

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Deterministic coordinate jitter per `docs/05-Implementation.md § Coordinate Fuzzing`.
 *
 * Given a stable `postId` + server-only `secret`, derives an `(bearing, distance)` pair
 * from `HMAC-SHA256(secret, postId.bytes)` and returns the forward-geodesic offset of
 * the actual point on the WGS84 spheroid. Output distance is always in `[50, 500]` m.
 *
 * Non-reversibility rests on HMAC-SHA256 preimage resistance: without the secret, recovery
 * of `actual` from `(display, postId)` is infeasible.
 */
@OptIn(ExperimentalUuidApi::class)
object JitterEngine {
    private const val EARTH_RADIUS_M: Double = 6_371_008.8

    /**
     * Returns the fuzzed display location.
     *
     * @param actual the true coordinate (not exposed to non-admin readers).
     * @param postId any stable 16-byte identifier; the same bytes always produce the same
     *   output for a given `secret`.
     * @param secret HMAC-SHA256 key. 32 bytes recommended.
     */
    fun offsetByBearing(
        actual: LatLng,
        postId: Uuid,
        secret: ByteArray,
    ): LatLng {
        val hmac = hmacSha256(secret, postId.toByteArray())
        val bearingRadians = uint32BigEndian(hmac, 0).toDouble() / UINT32_MAX_PLUS_1 * 2.0 * PI
        val distanceMeters = 50.0 + uint32BigEndian(hmac, 4).toDouble() / UINT32_MAX_PLUS_1 * 450.0
        return greatCircleOffset(actual, bearingRadians, distanceMeters)
    }

    /**
     * WGS84 great-circle forward problem on a spherical approximation (EARTH_RADIUS_M).
     * Precision loss vs full-spheroid is < 0.3% at 500 m scale — well within the docs/08
     * "~500 m acceptable precision loss" decision.
     */
    private fun greatCircleOffset(
        origin: LatLng,
        bearingRadians: Double,
        distanceMeters: Double,
    ): LatLng {
        val angularDistance = distanceMeters / EARTH_RADIUS_M
        val lat1 = origin.lat.toRadians()
        val lng1 = origin.lng.toRadians()

        val sinLat1 = sin(lat1)
        val cosLat1 = cos(lat1)
        val sinAd = sin(angularDistance)
        val cosAd = cos(angularDistance)

        val sinLat2 = sinLat1 * cosAd + cosLat1 * sinAd * cos(bearingRadians)
        val lat2 = asin(sinLat2)
        val lng2 =
            lng1 +
                atan2(
                    sin(bearingRadians) * sinAd * cosLat1,
                    cosAd - sinLat1 * sinLat2,
                )

        return LatLng(lat = lat2.toDegrees(), lng = normalizeLongitude(lng2.toDegrees()))
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private fun Double.toDegrees(): Double = this * 180.0 / PI

    private fun normalizeLongitude(deg: Double): Double {
        var x = deg
        while (x > 180.0) x -= 360.0
        while (x < -180.0) x += 360.0
        return x
    }

    private fun uint32BigEndian(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private const val UINT32_MAX_PLUS_1: Double = 4_294_967_296.0
}
