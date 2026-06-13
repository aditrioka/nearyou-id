package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.FollowingTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeFollowingPost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Pure coverage of the [followingTimelineUiState] projection (design D1 + D3) — the outcome→state
 * mapping the screen renders, exercised without a Compose UI runner (mirroring `GlobalTimelineUiStateTest`).
 * Exhaustive over [FollowingTimelineOutcome] + the **initial-load** flag. Pins the one divergence from
 * Global: a `Loaded(empty, no upsell)` maps to the directive [FollowingTimelineUiState.Empty] (distinct
 * from [FollowingTimelineUiState.Loading]), NOT a skeleton-empty. Also asserts the projected state carries
 * no PII (no `author_user_id`, no coordinates — and structurally no distance).
 */
class FollowingTimelineUiStateTest {
    private val onePost = listOf(fakeFollowingPost())

    @Test
    fun `initial load maps to Loading and wins over any prior outcome`() {
        assertEquals(FollowingTimelineUiState.Loading, followingTimelineUiState(outcome = null, isInitialLoad = true))
        assertEquals(
            FollowingTimelineUiState.Loading,
            followingTimelineUiState(FollowingTimelineOutcome.Loaded(onePost, null, null), isInitialLoad = true),
        )
    }

    @Test
    fun `null outcome while not initial-load maps to Loading`() {
        assertEquals(FollowingTimelineUiState.Loading, followingTimelineUiState(outcome = null, isInitialLoad = false))
    }

    @Test
    fun `loaded non-empty with no upsell maps to Content`() {
        val state = followingTimelineUiState(FollowingTimelineOutcome.Loaded(onePost, "tok", null), isInitialLoad = false)
        assertEquals(1, assertIs<FollowingTimelineUiState.Content>(state).posts.size)
    }

    @Test
    fun `a retained Loaded outcome during refresh projects to Content not Loading`() {
        // isInitialLoad = false + a previous Loaded(non-empty) outcome → Content (the list stays
        // mounted); the refresh indicator is the separate isRefreshing value (design D3).
        val state = followingTimelineUiState(FollowingTimelineOutcome.Loaded(onePost, null, null), isInitialLoad = false)
        assertIs<FollowingTimelineUiState.Content>(state)
    }

    @Test
    fun `loaded empty with no upsell maps to the directive Empty distinct from Loading`() {
        // The Following divergence from Global: empty is a REAL state (the directive Empty member), NOT a
        // re-skinned loading skeleton. The render attaches the placeholder copy + the "Lihat Global" CTA.
        val state = followingTimelineUiState(FollowingTimelineOutcome.Loaded(emptyList(), null, null), isInitialLoad = false)
        assertEquals(FollowingTimelineUiState.Empty, state)
        assertFalse(state == FollowingTimelineUiState.Loading, "the directive Empty is distinct from the initial-load Loading")
    }

    @Test
    fun `a refresh while empty retains Empty not the initial-load skeleton`() {
        // A refresh from the empty state (retained Loaded(empty) + isInitialLoad = false) stays the
        // directive Empty — it does NOT re-enter the initial-load Loading member (design D3).
        assertEquals(
            FollowingTimelineUiState.Empty,
            followingTimelineUiState(FollowingTimelineOutcome.Loaded(emptyList(), null, null), isInitialLoad = false),
        )
    }

    @Test
    fun `loaded empty with upsell hard maps to HardLimit distinct from Empty`() {
        assertEquals(
            FollowingTimelineUiState.HardLimit,
            followingTimelineUiState(FollowingTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)), isInitialLoad = false),
        )
    }

    @Test
    fun `loaded non-empty with upsell soft maps to SoftLimit carrying posts`() {
        val state =
            followingTimelineUiState(FollowingTimelineOutcome.Loaded(onePost, null, UpsellDto(soft = true)), isInitialLoad = false)
        assertEquals(1, assertIs<FollowingTimelineUiState.SoftLimit>(state).posts.size)
    }

    @Test
    fun `NetworkError and Error both map to the Error state`() {
        assertEquals(FollowingTimelineUiState.Error, followingTimelineUiState(FollowingTimelineOutcome.NetworkError, isInitialLoad = false))
        assertEquals(FollowingTimelineUiState.Error, followingTimelineUiState(FollowingTimelineOutcome.Error, isInitialLoad = false))
    }

    @Test
    fun `SessionExpired maps to the neutral SessionRedirect state distinct from Error`() {
        // Terminal 401 → SessionRedirect (no retry), NOT the connectivity Error state.
        assertEquals(
            FollowingTimelineUiState.SessionRedirect,
            followingTimelineUiState(FollowingTimelineOutcome.SessionExpired, isInitialLoad = false),
        )
    }

    @Test
    fun `projected content carries no author id or raw coordinates`() {
        val piiPost = fakeFollowingPost(authorUserId = "AUTHOR-SENTINEL", latitude = -6.21, longitude = 106.85)
        val rendered =
            followingTimelineUiState(FollowingTimelineOutcome.Loaded(listOf(piiPost), null, null), isInitialLoad = false).toString()
        assertFalse(rendered.contains("AUTHOR-SENTINEL"), "author id must not reach the UI state: $rendered")
        assertFalse(rendered.contains("-6.21"), "raw latitude must not reach the UI state: $rendered")
        assertFalse(rendered.contains("106.85"), "raw longitude must not reach the UI state: $rendered")
    }
}
