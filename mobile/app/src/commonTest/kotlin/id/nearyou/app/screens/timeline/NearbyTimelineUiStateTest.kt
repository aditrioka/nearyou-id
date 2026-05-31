package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeNearbyPost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Pure coverage of the [nearbyTimelineUiState] projection (design D6/D7) — the outcome→state mapping
 * the screen renders, exercised without a Compose UI runner (mirroring `AgeGateUiStateTest`).
 * Exhaustive over [NearbyTimelineOutcome] + the in-flight flag; also asserts the projected state
 * carries no PII (spec § "Pure NearbyTimelineUiState ... MUST NOT carry any PII").
 */
class NearbyTimelineUiStateTest {
    private val onePost = listOf(fakeNearbyPost())

    @Test
    fun `in-flight maps to Loading and wins over any prior outcome`() {
        assertEquals(NearbyTimelineUiState.Loading, nearbyTimelineUiState(outcome = null, inFlight = true))
        assertEquals(
            NearbyTimelineUiState.Loading,
            nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(onePost, null, null), inFlight = true),
        )
    }

    @Test
    fun `null outcome while not in-flight maps to Loading`() {
        assertEquals(NearbyTimelineUiState.Loading, nearbyTimelineUiState(outcome = null, inFlight = false))
    }

    @Test
    fun `loaded non-empty with no upsell maps to Content`() {
        val state = nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(onePost, "tok", null), inFlight = false)
        assertEquals(1, assertIs<NearbyTimelineUiState.Content>(state).posts.size)
    }

    @Test
    fun `loaded empty with no upsell maps to Empty`() {
        assertEquals(
            NearbyTimelineUiState.Empty,
            nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(emptyList(), null, null), inFlight = false),
        )
    }

    @Test
    fun `loaded empty with upsell hard maps to HardLimit (distinct from Empty)`() {
        assertEquals(
            NearbyTimelineUiState.HardLimit,
            nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)), inFlight = false),
        )
    }

    @Test
    fun `loaded non-empty with upsell soft maps to SoftLimit carrying posts`() {
        val state =
            nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(onePost, null, UpsellDto(soft = true)), inFlight = false)
        assertEquals(1, assertIs<NearbyTimelineUiState.SoftLimit>(state).posts.size)
    }

    @Test
    fun `NetworkError and Error both map to the Error state`() {
        assertEquals(NearbyTimelineUiState.Error, nearbyTimelineUiState(NearbyTimelineOutcome.NetworkError, inFlight = false))
        assertEquals(NearbyTimelineUiState.Error, nearbyTimelineUiState(NearbyTimelineOutcome.Error, inFlight = false))
    }

    @Test
    fun `projected content carries no author id or raw coordinates`() {
        val piiPost = fakeNearbyPost(authorUserId = "AUTHOR-SENTINEL", latitude = -6.21, longitude = 106.85)
        val rendered = nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(listOf(piiPost), null, null), inFlight = false).toString()
        assertFalse(rendered.contains("AUTHOR-SENTINEL"), "author id must not reach the UI state: $rendered")
        assertFalse(rendered.contains("-6.21"), "raw latitude must not reach the UI state: $rendered")
        assertFalse(rendered.contains("106.85"), "raw longitude must not reach the UI state: $rendered")
    }
}
