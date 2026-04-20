package id.nearyou.distance

import kotlin.math.roundToInt

/**
 * User-facing distance string renderer — **input is a fuzzed distance** already computed
 * against `display_location`, NOT the true distance. This function does not fuzz; it only
 * applies the display floor + rounding rules from
 * `docs/05-Implementation.md § renderDistance`:
 *
 *  - `d <  5000` → `"5km"`   (floor at 5 km — keeps the fuzz envelope from being visible)
 *  - `d >= 5000` → `"<round(d/1000)>km"`
 */
object DistanceRenderer {
    fun render(distanceMeters: Double): String {
        if (distanceMeters < 5000.0) return "5km"
        return "${(distanceMeters / 1000.0).roundToInt()}km"
    }
}
