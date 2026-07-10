package id.nearyou.app.screens.timeline

import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.timeline.FakeFollowingTimelineFlow
import id.nearyou.app.timeline.FollowingPostDto
import id.nearyou.app.timeline.FollowingTimelineOutcome
import id.nearyou.app.timeline.fakeFollowingPost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage of [FollowingTimelineViewModel] — the `HomeRoute`-scoped holder for the Following feed's
 * load state. Pins: the first page loads exactly once on construction, [FollowingTimelineViewModel.reload]
 * re-fetches (pull-to-refresh + error retry), a load failure maps to the EXISTING retryable
 * [FollowingTimelineOutcome.NetworkError] (no new outcome member), and the split-loading contract (design
 * D3): a reload toggles `isRefreshing` (NOT `isInitialLoad`) and RETAINS the prior outcome. Mirrors
 * `GlobalTimelineViewModelTest`. Also covers the inline-like delegation to the SAME shared controller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowingTimelineViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_loadsFirstPageExactlyOnce_andExposesTheOutcome() {
        val fake = FakeFollowingTimelineFlow(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "X")), null, null))
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        assertEquals(1, fake.loadInvocationCount, "the first page loads exactly once on construction")
        assertTrue(viewModel.outcome.value is FollowingTimelineOutcome.Loaded, "the loaded outcome is exposed")
        assertFalse(viewModel.isRefreshing.value, "isRefreshing is false after the initial load completes")
    }

    @Test
    fun reload_reFetchesPageOne() {
        val fake = FakeFollowingTimelineFlow(FollowingTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        assertEquals(1, fake.loadInvocationCount)
        viewModel.reload()
        assertEquals(2, fake.loadInvocationCount, "reload re-fetches page 1 (pull-to-refresh / error retry)")
    }

    @Test
    fun reload_keepsPriorOutcome_andTogglesIsRefreshing_uiStateStaysContent() {
        // First load completes; the SECOND call (reload) suspends, so we observe the in-flight refresh:
        // isRefreshing = true, uiState stays Content (NOT Loading), prior outcome retained (design D3).
        val loaded = FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "RETAINED")), null, null)
        val fake = FakeFollowingTimelineFlow(loaded, suspendFromCall = 2)
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        viewModel.activateUiState()
        assertTrue(viewModel.uiState.value is FollowingTimelineUiState.Content, "after the first load uiState is Content")
        assertFalse(viewModel.isRefreshing.value, "not refreshing before reload")
        val priorOutcome = viewModel.outcome.value

        viewModel.reload()

        assertTrue(viewModel.isRefreshing.value, "a reload-in-flight sets isRefreshing")
        assertTrue(
            viewModel.uiState.value is FollowingTimelineUiState.Content,
            "a reload does NOT re-enter the Loading skeleton (uiState stays Content)",
        )
        assertEquals(priorOutcome, viewModel.outcome.value, "the prior outcome is retained during the refresh")
    }

    @Test
    fun loadFailure_mapsToExistingNetworkError() {
        val fake = FakeFollowingTimelineFlow(failWith = IllegalStateException("fetch threw"))
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        viewModel.activateUiState()
        assertEquals(
            FollowingTimelineOutcome.NetworkError,
            viewModel.outcome.value,
            "a fetch failure maps to the existing retryable NetworkError (no new outcome member)",
        )
        assertTrue(viewModel.uiState.value is FollowingTimelineUiState.Error, "and uiState projects to Error")
    }

    @Test
    fun uiState_delegatesToTheProjection() {
        val fake = FakeFollowingTimelineFlow(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "X")), null, null))
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        viewModel.activateUiState()
        // The single uiState equals the pure projection for the held outcome (reused, not reimplemented);
        // after the first outcome arrives the initial-load flag is false.
        assertEquals(
            followingTimelineUiState(viewModel.outcome.value, isInitialLoad = false),
            viewModel.uiState.value,
            "uiState delegates to followingTimelineUiState(outcome, isInitialLoad)",
        )
        assertTrue(viewModel.uiState.value is FollowingTimelineUiState.Content, "a loaded non-empty outcome projects to Content")
    }

    @Test
    fun uiState_retainsContentAcrossAFreshCollector() {
        // The config-change proxy: the entry-scoped VM retains the resolved outcome, so a FRESH uiState
        // collector (the recomposed screen) still sees Content — not a reset to Loading.
        val fake = FakeFollowingTimelineFlow(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "X")), null, null))
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        viewModel.activateUiState()
        assertTrue(viewModel.uiState.value is FollowingTimelineUiState.Content, "loaded → Content")
        viewModel.activateUiState() // a second, fresh collector (the config-change case)
        assertTrue(
            viewModel.uiState.value is FollowingTimelineUiState.Content,
            "a fresh collector still sees the retained Content (not reset to Loading)",
        )
    }

    // ---- mobile-inline-post-actions: the inline-like delegation to the SAME shared controller ----

    private fun loadedWith(vararg posts: FollowingPostDto) = FollowingTimelineOutcome.Loaded(posts.toList(), null, null)

    private fun FollowingTimelineViewModel.likedOf(postId: String): Boolean =
        (outcome.value as FollowingTimelineOutcome.Loaded).posts.first { it.id == postId }.likedByViewer

    @Test
    fun toggleLike_flipsThePostInTheRetainedLoadedOutcome_bothDirections() {
        val likeFlow = FakeLikeFlow(LikeOutcome.Liked)
        val viewModel =
            FollowingTimelineViewModel(
                FakeFollowingTimelineFlow(loadedWith(fakeFollowingPost(id = "p1", likedByViewer = false))),
                likeFlow,
                FakeReportSubmitter(),
                FakeSelfUserId("self"),
            )

        viewModel.toggleLike("p1", currentlyLiked = false)
        assertTrue(viewModel.likedOf("p1"), "the like flip lands in the retained Loaded outcome")
        assertEquals("p1" to false, likeFlow.invocations.single(), "the like direction is currentlyLiked = false")

        likeFlow.outcome = LikeOutcome.Unliked
        viewModel.toggleLike("p1", currentlyLiked = true)
        assertFalse(viewModel.likedOf("p1"), "the unlike flip lands too (direction from the CURRENT state)")
        assertEquals("p1" to true, likeFlow.invocations.last(), "the unlike direction is currentlyLiked = true")
    }

    @Test
    fun rateLimitedLike_reverts_andRaisesTheOneShotCapState_dismissClears() {
        val likeFlow = FakeLikeFlow(LikeOutcome.RateLimited(retryAfterSeconds = 1140))
        val viewModel =
            FollowingTimelineViewModel(
                FakeFollowingTimelineFlow(loadedWith(fakeFollowingPost(id = "p1", likedByViewer = false))),
                likeFlow,
                FakeReportSubmitter(),
                FakeSelfUserId("self"),
            )

        viewModel.toggleLike("p1", currentlyLiked = false)

        assertFalse(viewModel.likedOf("p1"), "the optimistic flip is reverted on the 429")
        assertEquals(1140L, viewModel.likeCapRetryAfterSeconds.value, "the one-shot cap state carries Retry-After")
        viewModel.onLikeCapDialogDismissed()
        assertNull(viewModel.likeCapRetryAfterSeconds.value, "dismiss clears the one-shot state")
    }

    @Test
    fun postGoneLike_reverts_andSelfHealsViaReload() {
        val fake = FakeFollowingTimelineFlow(loadedWith(fakeFollowingPost(id = "p1", likedByViewer = false)))
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(LikeOutcome.PostGone), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.toggleLike("p1", currentlyLiked = false)

        assertFalse(viewModel.likedOf("p1"), "the flip is reverted on PostGone")
        assertEquals(2, fake.loadInvocationCount, "PostGone triggers the existing reload (self-heal)")
    }

    // mobile-following-timeline § "PostGone and NetworkError mirror the Nearby/Global handling" names BOTH
    // cases on the Following surface — assert NetworkError directly here (not only transitively via
    // InlineLikeControllerTest) so the Following-named fixture honors the spec wording.
    @Test
    fun networkErrorLike_revertsSilently() {
        val fake = FakeFollowingTimelineFlow(loadedWith(fakeFollowingPost(id = "p1", likedByViewer = false)))
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(LikeOutcome.NetworkError), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.toggleLike("p1", currentlyLiked = false)

        assertFalse(viewModel.likedOf("p1"), "the flip is reverted on NetworkError")
        assertNull(viewModel.likeCapRetryAfterSeconds.value, "no cap state — the declared silent v1 posture")
        assertEquals(1, fake.loadInvocationCount, "no reload on NetworkError")
    }

    @Test
    fun inFlightLikeReTaps_areIgnored() {
        val likeFlow = FakeLikeFlow().apply { suspendForever = true }
        val viewModel =
            FollowingTimelineViewModel(
                FakeFollowingTimelineFlow(loadedWith(fakeFollowingPost(id = "p1", likedByViewer = false))),
                likeFlow,
                FakeReportSubmitter(),
                FakeSelfUserId("self"),
            )

        viewModel.toggleLike("p1", currentlyLiked = false)
        viewModel.toggleLike("p1", currentlyLiked = true)

        assertEquals(1, likeFlow.invocationCount, "the per-post in-flight guard ignores the re-tap")
    }

    // ---- mobile-nearby-timeline-infinite-scroll (extended to Following): cursor load-more (no anchor) ----

    private fun loadedPage1(
        cursor: String?,
        vararg posts: FollowingPostDto,
    ) = FollowingTimelineOutcome.Loaded(posts.toList(), cursor, null)

    @Test
    fun onLoadMore_appendsBelowPage1_andAdvancesCursor() {
        val fake =
            FakeFollowingTimelineFlow(
                outcome = loadedPage1("c1", fakeFollowingPost(id = "p1")),
                loadMorePages = listOf(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(id = "p2")), "c2", null)),
            )
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.onLoadMore()

        val loaded = viewModel.outcome.value as FollowingTimelineOutcome.Loaded
        assertEquals(listOf("p1", "p2"), loaded.posts.map { it.id }, "page 2 appends below page 1")
        assertEquals("c2", loaded.nextCursor, "the cursor advances to the new page's cursor")
        assertEquals(
            listOf("c1"),
            fake.loadMoreCalls,
            "load-more fetches the retained cursor c1 (cursor-only — Following has no anchor)",
        )
    }

    @Test
    fun onLoadMore_whenEndReached_isNoOp() {
        // First page already end-reached (null cursor) → load-more must not fire.
        val fake = FakeFollowingTimelineFlow(outcome = loadedPage1(null, fakeFollowingPost(id = "p1")))
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.onLoadMore()

        assertTrue(fake.loadMoreCalls.isEmpty(), "no load-more request when the cursor is null (end-reached)")
    }

    @Test
    fun onLoadMore_failure_raisesErrorFooter_andKeepsLoadedPosts() {
        val fake =
            FakeFollowingTimelineFlow(
                outcome = loadedPage1("c1", fakeFollowingPost(id = "p1")),
                loadMorePages = listOf(FollowingTimelineOutcome.NetworkError),
            )
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.onLoadMore()

        assertTrue(viewModel.loadMoreError.value, "a failed load-more raises the non-destructive error footer")
        val loaded = viewModel.outcome.value as FollowingTimelineOutcome.Loaded
        assertEquals(listOf("p1"), loaded.posts.map { it.id }, "the loaded posts are retained on load-more failure")
    }

    @Test
    fun reload_clearsTheLoadMoreErrorFooter() {
        val fake =
            FakeFollowingTimelineFlow(
                outcome = loadedPage1("c1", fakeFollowingPost(id = "p1")),
                loadMorePages = listOf(FollowingTimelineOutcome.NetworkError),
            )
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        viewModel.onLoadMore()
        assertTrue(viewModel.loadMoreError.value)

        viewModel.reload()

        assertFalse(viewModel.loadMoreError.value, "a refresh resets paging — the load-more footer state clears")
    }

    @Test
    fun onLoadMore_isSuppressedWhileARefreshIsInFlight() {
        // suspendFromCall = 2 → the reload's loadFirstPage suspends, so the refresh stays in flight.
        val fake =
            FakeFollowingTimelineFlow(
                outcome = loadedPage1("c1", fakeFollowingPost(id = "p1")),
                suspendFromCall = 2,
                loadMorePages = listOf(FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(id = "p2")), "c2", null)),
            )
        val viewModel = FollowingTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.reload()
        assertTrue(viewModel.isRefreshing.value, "the reload is in flight")
        viewModel.onLoadMore()

        assertTrue(fake.loadMoreCalls.isEmpty(), "load-more is suppressed while a refresh is in flight (canLoadMore gate)")
    }

    // Activates the WhileSubscribed(5000) uiState share (on the Unconfined Main) so uiState.value reflects
    // the projected state in these synchronous tests; the collector is abandoned at test end (no runTest).
    private fun FollowingTimelineViewModel.activateUiState() {
        CoroutineScope(Dispatchers.Main).launch { uiState.collect {} }
    }
}
