package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeGlobalPost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Pure coverage of the [globalTimelineUiState] projection (design D4 + D3) — the outcome→state mapping
 * the screen renders, exercised without a Compose UI runner (mirroring `NearbyTimelineUiStateTest`).
 * Exhaustive over [GlobalTimelineOutcome] + the **initial-load** flag (NOT a generic in-flight flag):
 * the refresh-vs-initial distinction is pinned here (a retained `Loaded` during a refresh projects to
 * `Content`/`Empty`, never back to the skeleton). Also asserts the projected state carries no PII (no
 * `author_user_id`, no coordinates — and structurally no distance).
 */
class GlobalTimelineUiStateTest {
    private val onePost = listOf(fakeGlobalPost())

    @Test
    fun `initial load maps to Loading and wins over any prior outcome`() {
        assertEquals(GlobalTimelineUiState.Loading, globalTimelineUiState(outcome = null, isInitialLoad = true))
        assertEquals(
            GlobalTimelineUiState.Loading,
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(onePost, null, null), isInitialLoad = true),
        )
    }

    @Test
    fun `null outcome while not initial-load maps to Loading`() {
        assertEquals(GlobalTimelineUiState.Loading, globalTimelineUiState(outcome = null, isInitialLoad = false))
    }

    @Test
    fun `loaded non-empty with no upsell maps to Content`() {
        val state = globalTimelineUiState(GlobalTimelineOutcome.Loaded(onePost, "tok", null), isInitialLoad = false)
        assertEquals(1, assertIs<GlobalTimelineUiState.Content>(state).posts.size)
    }

    @Test
    fun `a retained Loaded outcome during refresh projects to Content not Loading`() {
        // isInitialLoad = false + a previous Loaded(non-empty) outcome → Content (the list stays
        // mounted); the refresh indicator is the separate isRefreshing value (design D3).
        val state = globalTimelineUiState(GlobalTimelineOutcome.Loaded(onePost, null, null), isInitialLoad = false)
        assertIs<GlobalTimelineUiState.Content>(state)
    }

    @Test
    fun `loaded empty with no upsell maps to Empty`() {
        assertEquals(
            GlobalTimelineUiState.Empty,
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(emptyList(), null, null), isInitialLoad = false),
        )
    }

    @Test
    fun `a refresh while empty retains Empty not the initial-load skeleton`() {
        // A refresh from the empty state (retained Loaded(empty) + isInitialLoad = false) stays Empty
        // (the Global Empty member, which renders the same skeleton copy) — it does NOT re-enter the
        // initial-load Loading member (design D3 / spec § pull-to-refresh-from-non-Content).
        assertEquals(
            GlobalTimelineUiState.Empty,
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(emptyList(), null, null), isInitialLoad = false),
        )
    }

    @Test
    fun `loaded empty with upsell hard maps to HardLimit distinct from Empty`() {
        assertEquals(
            GlobalTimelineUiState.HardLimit,
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)), isInitialLoad = false),
        )
    }

    @Test
    fun `loaded non-empty with upsell soft maps to SoftLimit carrying posts`() {
        val state =
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(onePost, null, UpsellDto(soft = true)), isInitialLoad = false)
        assertEquals(1, assertIs<GlobalTimelineUiState.SoftLimit>(state).posts.size)
    }

    @Test
    fun `NetworkError and Error both map to the Error state`() {
        assertEquals(GlobalTimelineUiState.Error, globalTimelineUiState(GlobalTimelineOutcome.NetworkError, isInitialLoad = false))
        assertEquals(GlobalTimelineUiState.Error, globalTimelineUiState(GlobalTimelineOutcome.Error, isInitialLoad = false))
    }

    @Test
    fun `projected content carries no author id or raw coordinates`() {
        val piiPost = fakeGlobalPost(authorUserId = "AUTHOR-SENTINEL", latitude = -6.21, longitude = 106.85)
        val rendered =
            globalTimelineUiState(GlobalTimelineOutcome.Loaded(listOf(piiPost), null, null), isInitialLoad = false).toString()
        assertFalse(rendered.contains("AUTHOR-SENTINEL"), "author id must not reach the UI state: $rendered")
        assertFalse(rendered.contains("-6.21"), "raw latitude must not reach the UI state: $rendered")
        assertFalse(rendered.contains("106.85"), "raw longitude must not reach the UI state: $rendered")
    }
}
