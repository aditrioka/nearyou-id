package id.nearyou.app.screens.timeline

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage of [radiusSelectionDecision] (`mobile-nearby-radius-slider`) — the Compose-free
 * gate mapping (isPremiumKnown, requestedRadius) → Apply vs SnapBackAndUpsell, with no platform / Compose
 * / wall-clock dependency (mirrors the `nearbyTimelineUiState` projection test style).
 */
class RadiusSelectionTest {
    @Test
    fun the20kmDefaultIsAlwaysApplied_regardlessOfTier() {
        // Free, Premium, and Resolving (null) all permit the 20 km anchor.
        assertEquals(RadiusSelectionDecision.Apply(20_000), radiusSelectionDecision(isPremiumKnown = false, requestedRadiusM = 20_000))
        assertEquals(RadiusSelectionDecision.Apply(20_000), radiusSelectionDecision(isPremiumKnown = true, requestedRadiusM = 20_000))
        assertEquals(RadiusSelectionDecision.Apply(20_000), radiusSelectionDecision(isPremiumKnown = null, requestedRadiusM = 20_000))
    }

    @Test
    fun premiumViewer_appliesAnyPosition() {
        assertEquals(RadiusSelectionDecision.Apply(10_000), radiusSelectionDecision(isPremiumKnown = true, requestedRadiusM = 10_000))
        assertEquals(RadiusSelectionDecision.Apply(50_000), radiusSelectionDecision(isPremiumKnown = true, requestedRadiusM = 50_000))
        assertEquals(RadiusSelectionDecision.Apply(100_000), radiusSelectionDecision(isPremiumKnown = true, requestedRadiusM = 100_000))
    }

    @Test
    fun freeViewer_snapsBackOnAnyNon20kmPosition_including10km() {
        // No "smaller is fine" exception (Decision 3) — 10 km is Premium-only too.
        assertEquals(RadiusSelectionDecision.SnapBackAndUpsell, radiusSelectionDecision(isPremiumKnown = false, requestedRadiusM = 10_000))
        assertEquals(RadiusSelectionDecision.SnapBackAndUpsell, radiusSelectionDecision(isPremiumKnown = false, requestedRadiusM = 50_000))
        assertEquals(RadiusSelectionDecision.SnapBackAndUpsell, radiusSelectionDecision(isPremiumKnown = false, requestedRadiusM = 100_000))
    }

    @Test
    fun resolvingTier_behavesAsFreeOnNon20km() {
        // null = Resolving (also a grace-period viewer reading as is_premium=false) → snapped back.
        assertEquals(RadiusSelectionDecision.SnapBackAndUpsell, radiusSelectionDecision(isPremiumKnown = null, requestedRadiusM = 50_000))
    }

    @Test
    fun positionsAreExactlyTheFourProductValues() {
        assertEquals(listOf(10_000, 20_000, 50_000, 100_000), NEARBY_RADIUS_POSITIONS_M)
    }
}
