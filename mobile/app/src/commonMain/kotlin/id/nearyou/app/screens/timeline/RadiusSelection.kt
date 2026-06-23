package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.NEARBY_RADIUS_M

/**
 * The four discrete Nearby filter positions in metres — the 10 / 20 / 50 / 100 km slider per
 * `docs/02-Product.md` § Nearby Timeline (`mobile-nearby-radius-slider`). The default position is
 * [NEARBY_RADIUS_M] (20 km), the only Free-tier value; every other position is Premium-only.
 */
val NEARBY_RADIUS_POSITIONS_M: List<Int> = listOf(10_000, 20_000, 50_000, 100_000)

/**
 * Pure result of a radius-selection attempt — no Compose / platform / wall-clock dependency, so the
 * gate logic is `commonTest`-able (mirrors `nearbyTimelineUiState` / `AgeGateUiState`).
 */
sealed interface RadiusSelectionDecision {
    /** The selection is permitted; (re)fetch page 1 at [radiusM]. */
    data class Apply(val radiusM: Int) : RadiusSelectionDecision

    /** A non-Premium viewer chose a Premium-only radius → keep 20 km AND show the Premium upsell. */
    data object SnapBackAndUpsell : RadiusSelectionDecision
}

/**
 * Pure projection of a radius selection (`mobile-nearby-radius-slider`). The 20 km default
 * ([NEARBY_RADIUS_M]) is permitted for every tier; any other position requires a KNOWN Premium tier
 * ([isPremiumKnown] == true). A Free (`false`) OR not-yet-resolved (`null`, the Resolving window) tier
 * is treated as Free → [RadiusSelectionDecision.SnapBackAndUpsell]. Per design Decision 6 a grace-period
 * `premium_billing_retry` viewer reads as `is_premium = false`, so it too is conservatively snapped back
 * (the server would permit a wider radius, but the client never issues it). Deterministic; no platform deps.
 */
fun radiusSelectionDecision(
    isPremiumKnown: Boolean?,
    requestedRadiusM: Int,
): RadiusSelectionDecision =
    if (requestedRadiusM == NEARBY_RADIUS_M || isPremiumKnown == true) {
        RadiusSelectionDecision.Apply(requestedRadiusM)
    } else {
        RadiusSelectionDecision.SnapBackAndUpsell
    }
