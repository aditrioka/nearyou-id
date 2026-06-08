package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeNearbyPost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Pure coverage of the [nearbyTimelineUiState] projection (design D6/D7 + D3) — the outcome→state
 * mapping the screen renders, exercised without a Compose UI runner (mirroring `AgeGateUiStateTest`).
 * Exhaustive over [NearbyTimelineOutcome] + the **initial-load** flag (NOT a generic in-flight flag):
 * the refresh-vs-initial distinction is pinned here (a retained `Loaded` during a refresh projects to
 * `Content`/`Empty`, never back to the skeleton). Also asserts the projected state carries no PII (spec
 * § "Pure NearbyTimelineUiState ... MUST NOT carry any PII").
 */
class NearbyTimelineUiStateTest {
    private val onePost = listOf(fakeNearbyPost())

    @Test
    fun `initial load maps to Loading and wins over any prior outcome`() {
        assertEquals(NearbyTimelineUiState.Loading, nearbyTimelineUiState(outcome = null, isInitialLoad = true))
        assertEquals(
            NearbyTimelineUiState.Loading,
            nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(onePost, null, null), isInitialLoad = true),
        )
    }

    @Test
    fun `null outcome while not initial-load maps to Loading`() {
        assertEquals(NearbyTimelineUiState.Loading, nearbyTimelineUiState(outcome = null, isInitialLoad = false))
    }

    @Test
    fun `loaded non-empty with no upsell maps to Content`() {
        val state = nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(onePost, "tok", null), isInitialLoad = false)
        assertEquals(1, assertIs<NearbyTimelineUiState.Content>(state).posts.size)
    }

    @Test
    fun `a retained Loaded outcome during refresh projects to Content not Loading`() {
        // isInitialLoad = false + a previous Loaded(non-empty) outcome → Content (the list stays
        // mounted); the refresh indicator is conveyed by the separate isRefreshing value, NOT by
        // flipping the state to Loading (design D3).
        val state = nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(onePost, null, null), isInitialLoad = false)
        assertIs<NearbyTimelineUiState.Content>(state)
    }

    @Test
    fun `loaded empty with no upsell maps to Empty`() {
        assertEquals(
            NearbyTimelineUiState.Empty,
            nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(emptyList(), null, null), isInitialLoad = false),
        )
    }

    @Test
    fun `a refresh while empty retains Empty not the initial-load skeleton`() {
        // A refresh from the empty state (retained Loaded(empty) + isInitialLoad = false) stays Empty —
        // it does NOT flip to the Loading skeleton (design D3 / spec § pull-to-refresh-from-non-Content).
        assertEquals(
            NearbyTimelineUiState.Empty,
            nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(emptyList(), null, null), isInitialLoad = false),
        )
    }

    @Test
    fun `loaded empty with upsell hard maps to HardLimit distinct from Empty`() {
        assertEquals(
            NearbyTimelineUiState.HardLimit,
            nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)), isInitialLoad = false),
        )
    }

    @Test
    fun `loaded non-empty with upsell soft maps to SoftLimit carrying posts`() {
        val state =
            nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(onePost, null, UpsellDto(soft = true)), isInitialLoad = false)
        assertEquals(1, assertIs<NearbyTimelineUiState.SoftLimit>(state).posts.size)
    }

    @Test
    fun `NetworkError and Error both map to the Error state`() {
        assertEquals(NearbyTimelineUiState.Error, nearbyTimelineUiState(NearbyTimelineOutcome.NetworkError, isInitialLoad = false))
        assertEquals(NearbyTimelineUiState.Error, nearbyTimelineUiState(NearbyTimelineOutcome.Error, isInitialLoad = false))
    }

    @Test
    fun `SessionExpired maps to the neutral SessionRedirect state distinct from Error`() {
        // Terminal 401 → SessionRedirect (no retry), NOT the connectivity Error state (D4).
        assertEquals(
            NearbyTimelineUiState.SessionRedirect,
            nearbyTimelineUiState(NearbyTimelineOutcome.SessionExpired, isInitialLoad = false),
        )
    }

    @Test
    fun `projected content carries no author id or raw coordinates`() {
        val piiPost = fakeNearbyPost(authorUserId = "AUTHOR-SENTINEL", latitude = -6.21, longitude = 106.85)
        val rendered = nearbyTimelineUiState(NearbyTimelineOutcome.Loaded(listOf(piiPost), null, null), isInitialLoad = false).toString()
        assertFalse(rendered.contains("AUTHOR-SENTINEL"), "author id must not reach the UI state: $rendered")
        assertFalse(rendered.contains("-6.21"), "raw latitude must not reach the UI state: $rendered")
        assertFalse(rendered.contains("106.85"), "raw longitude must not reach the UI state: $rendered")
    }
}
