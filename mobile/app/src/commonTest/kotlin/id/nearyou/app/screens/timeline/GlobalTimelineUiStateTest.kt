package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeGlobalPost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Pure coverage of the [globalTimelineUiState] projection (design D4) — the outcome→state mapping the
 * screen renders, exercised without a Compose UI runner (mirroring `NearbyTimelineUiStateTest`).
 * Exhaustive over [GlobalTimelineOutcome] + the in-flight flag; also asserts the projected state
 * carries no PII (no `author_user_id`, no coordinates — and structurally no distance).
 */
class GlobalTimelineUiStateTest {
    private val onePost = listOf(fakeGlobalPost())

    @Test
    fun `in-flight maps to Loading and wins over any prior outcome`() {
        assertEquals(GlobalTimelineUiState.Loading, globalTimelineUiState(outcome = null, inFlight = true))
        assertEquals(
            GlobalTimelineUiState.Loading,
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(onePost, null, null), inFlight = true),
        )
    }

    @Test
    fun `null outcome while not in-flight maps to Loading`() {
        assertEquals(GlobalTimelineUiState.Loading, globalTimelineUiState(outcome = null, inFlight = false))
    }

    @Test
    fun `loaded non-empty with no upsell maps to Content`() {
        val state = globalTimelineUiState(GlobalTimelineOutcome.Loaded(onePost, "tok", null), inFlight = false)
        assertEquals(1, assertIs<GlobalTimelineUiState.Content>(state).posts.size)
    }

    @Test
    fun `loaded empty with no upsell maps to Empty`() {
        assertEquals(
            GlobalTimelineUiState.Empty,
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(emptyList(), null, null), inFlight = false),
        )
    }

    @Test
    fun `loaded empty with upsell hard maps to HardLimit distinct from Empty`() {
        assertEquals(
            GlobalTimelineUiState.HardLimit,
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)), inFlight = false),
        )
    }

    @Test
    fun `loaded non-empty with upsell soft maps to SoftLimit carrying posts`() {
        val state =
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(onePost, null, UpsellDto(soft = true)), inFlight = false)
        assertEquals(1, assertIs<GlobalTimelineUiState.SoftLimit>(state).posts.size)
    }

    @Test
    fun `NetworkError and Error both map to the Error state`() {
        assertEquals(GlobalTimelineUiState.Error, globalTimelineUiState(GlobalTimelineOutcome.NetworkError, inFlight = false))
        assertEquals(GlobalTimelineUiState.Error, globalTimelineUiState(GlobalTimelineOutcome.Error, inFlight = false))
    }

    @Test
    fun `projected content carries no author id or raw coordinates`() {
        val piiPost = fakeGlobalPost(authorUserId = "AUTHOR-SENTINEL", latitude = -6.21, longitude = 106.85)
        val rendered =
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(listOf(piiPost), null, null), inFlight = false).toString()
        assertFalse(rendered.contains("AUTHOR-SENTINEL"), "author id must not reach the UI state: $rendered")
        assertFalse(rendered.contains("-6.21"), "raw latitude must not reach the UI state: $rendered")
        assertFalse(rendered.contains("106.85"), "raw longitude must not reach the UI state: $rendered")
    }
}
