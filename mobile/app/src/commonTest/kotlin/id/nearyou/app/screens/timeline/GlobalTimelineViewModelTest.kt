package id.nearyou.app.screens.timeline

import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.timeline.FakeGlobalTimelineFlow
import id.nearyou.app.timeline.GlobalPostDto
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.fakeGlobalPost
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
 * Unit coverage of [GlobalTimelineViewModel] — the `HomeRoute`-scoped holder for the Global feed's
 * load state. Pins: the first page loads exactly once on construction, [GlobalTimelineViewModel.reload]
 * re-fetches (pull-to-refresh + error retry), a load failure maps to the EXISTING retryable
 * [GlobalTimelineOutcome.NetworkError] (no new outcome member), and the split-loading contract (design
 * D3): a reload toggles `isRefreshing` (NOT `isInitialLoad`) and RETAINS the prior outcome. Mirrors
 * `NearbyTimelineViewModelTest`.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] is installed as Main
 * so the init/reload coroutines run eagerly and synchronously against the (non-suspending) fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalTimelineViewModelTest {
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
        val fake = FakeGlobalTimelineFlow(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "X")), null, null))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        assertEquals(1, fake.loadInvocationCount, "the first page loads exactly once on construction")
        assertTrue(viewModel.outcome.value is GlobalTimelineOutcome.Loaded, "the loaded outcome is exposed")
        assertFalse(viewModel.isRefreshing.value, "isRefreshing is false after the initial load completes")
    }

    @Test
    fun reload_reFetchesPageOne() {
        val fake = FakeGlobalTimelineFlow(GlobalTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        assertEquals(1, fake.loadInvocationCount)
        viewModel.reload()
        assertEquals(2, fake.loadInvocationCount, "reload re-fetches page 1 (pull-to-refresh / error retry)")
    }

    @Test
    fun reload_keepsPriorOutcome_andTogglesIsRefreshing_uiStateStaysContent() {
        // First load completes; the SECOND call (reload) suspends, so we observe the in-flight refresh:
        // isRefreshing = true, uiState stays Content (NOT Loading), prior outcome retained (design D3).
        val loaded = GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "RETAINED")), null, null)
        val fake = FakeGlobalTimelineFlow(loaded, suspendFromCall = 2)
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        viewModel.activateUiState()
        assertTrue(viewModel.uiState.value is GlobalTimelineUiState.Content, "after the first load uiState is Content")
        assertFalse(viewModel.isRefreshing.value, "not refreshing before reload")
        val priorOutcome = viewModel.outcome.value

        viewModel.reload()

        assertTrue(viewModel.isRefreshing.value, "a reload-in-flight sets isRefreshing")
        assertTrue(
            viewModel.uiState.value is GlobalTimelineUiState.Content,
            "a reload does NOT re-enter the Loading skeleton (uiState stays Content)",
        )
        assertEquals(priorOutcome, viewModel.outcome.value, "the prior outcome is retained during the refresh")
    }

    @Test
    fun loadFailure_mapsToExistingNetworkError() {
        val fake = FakeGlobalTimelineFlow(failWith = IllegalStateException("fetch threw"))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        viewModel.activateUiState()
        assertEquals(
            GlobalTimelineOutcome.NetworkError,
            viewModel.outcome.value,
            "a fetch failure maps to the existing retryable NetworkError (no new outcome member)",
        )
        assertTrue(viewModel.uiState.value is GlobalTimelineUiState.Error, "and uiState projects to Error")
    }

    @Test
    fun uiState_delegatesToTheProjection() {
        val fake = FakeGlobalTimelineFlow(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "X")), null, null))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        viewModel.activateUiState()
        // The single uiState equals the pure projection for the held outcome (reused, not reimplemented);
        // after the first outcome arrives the initial-load flag is false.
        assertEquals(
            globalTimelineUiState(viewModel.outcome.value, isInitialLoad = false),
            viewModel.uiState.value,
            "uiState delegates to globalTimelineUiState(outcome, isInitialLoad)",
        )
        assertTrue(viewModel.uiState.value is GlobalTimelineUiState.Content, "a loaded outcome projects to Content")
    }

    @Test
    fun uiState_retainsContentAcrossAFreshCollector() {
        // The config-change proxy: the entry-scoped VM retains the resolved outcome, so a FRESH uiState
        // collector (the recomposed screen) still sees Content — not a reset to Loading.
        val fake = FakeGlobalTimelineFlow(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "X")), null, null))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        viewModel.activateUiState()
        assertTrue(viewModel.uiState.value is GlobalTimelineUiState.Content, "loaded → Content")
        viewModel.activateUiState() // a second, fresh collector (the config-change case)
        assertTrue(
            viewModel.uiState.value is GlobalTimelineUiState.Content,
            "a fresh collector still sees the retained Content (not reset to Loading)",
        )
    }

    // ---- mobile-inline-post-actions: the inline-like delegation to the SAME shared controller ----

    private fun loadedWith(vararg posts: GlobalPostDto) = GlobalTimelineOutcome.Loaded(posts.toList(), null, null)

    private fun GlobalTimelineViewModel.likedOf(postId: String): Boolean =
        (outcome.value as GlobalTimelineOutcome.Loaded).posts.first { it.id == postId }.likedByViewer

    @Test
    fun toggleLike_flipsThePostInTheRetainedLoadedOutcome_bothDirections() {
        val likeFlow = FakeLikeFlow(LikeOutcome.Liked)
        val viewModel =
            GlobalTimelineViewModel(
                FakeGlobalTimelineFlow(loadedWith(fakeGlobalPost(id = "p1", likedByViewer = false))),
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
            GlobalTimelineViewModel(
                FakeGlobalTimelineFlow(loadedWith(fakeGlobalPost(id = "p1", likedByViewer = false))),
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
        val fake = FakeGlobalTimelineFlow(loadedWith(fakeGlobalPost(id = "p1", likedByViewer = false)))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(LikeOutcome.PostGone), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.toggleLike("p1", currentlyLiked = false)

        assertFalse(viewModel.likedOf("p1"), "the flip is reverted on PostGone")
        assertEquals(2, fake.loadInvocationCount, "PostGone triggers the existing reload (self-heal)")
    }

    // mobile-global-timeline § "PostGone and NetworkError mirror the Nearby handling" names BOTH cases
    // on the Global surface — assert NetworkError directly here (not only transitively via
    // InlineLikeControllerTest) so the Global-named fixture honors the spec wording.
    @Test
    fun networkErrorLike_revertsSilently() {
        val fake = FakeGlobalTimelineFlow(loadedWith(fakeGlobalPost(id = "p1", likedByViewer = false)))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(LikeOutcome.NetworkError), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.toggleLike("p1", currentlyLiked = false)

        assertFalse(viewModel.likedOf("p1"), "the flip is reverted on NetworkError")
        assertNull(viewModel.likeCapRetryAfterSeconds.value, "no cap state — the declared silent v1 posture")
        assertEquals(1, fake.loadInvocationCount, "no reload on NetworkError")
    }

    @Test
    fun inFlightLikeReTaps_areIgnored() {
        val likeFlow = FakeLikeFlow().apply { suspendForever = true }
        val viewModel =
            GlobalTimelineViewModel(
                FakeGlobalTimelineFlow(loadedWith(fakeGlobalPost(id = "p1", likedByViewer = false))),
                likeFlow,
                FakeReportSubmitter(),
                FakeSelfUserId("self"),
            )

        viewModel.toggleLike("p1", currentlyLiked = false)
        viewModel.toggleLike("p1", currentlyLiked = true)

        assertEquals(1, likeFlow.invocationCount, "the per-post in-flight guard ignores the re-tap")
    }

    // ---- mobile-nearby-timeline-infinite-scroll (extended to Global): cursor load-more (NO anchor) ----

    private fun loadedPage1(
        cursor: String?,
        vararg posts: GlobalPostDto,
    ) = GlobalTimelineOutcome.Loaded(posts.toList(), cursor, null)

    @Test
    fun onLoadMore_appendsBelowPage1_andAdvancesCursor_cursorOnly() {
        val fake =
            FakeGlobalTimelineFlow(
                outcome = loadedPage1("c1", fakeGlobalPost(id = "p1")),
                loadMorePages = listOf(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "p2")), "c2", null)),
            )
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.onLoadMore()

        val loaded = viewModel.outcome.value as GlobalTimelineOutcome.Loaded
        assertEquals(listOf("p1", "p2"), loaded.posts.map { it.id }, "page 2 appends below page 1")
        assertEquals("c2", loaded.nextCursor, "the cursor advances to the new page's cursor")
        assertEquals(
            listOf("c1"),
            fake.loadMoreCalls,
            "load-more fetches the retained cursor c1 (cursor-only — Global has no anchor)",
        )
    }

    @Test
    fun onLoadMore_whenEndReached_isNoOp() {
        // First page already end-reached (null cursor) → load-more must not fire.
        val fake = FakeGlobalTimelineFlow(outcome = loadedPage1(null, fakeGlobalPost(id = "p1")))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.onLoadMore()

        assertTrue(fake.loadMoreCalls.isEmpty(), "no load-more request when the cursor is null (end-reached)")
    }

    @Test
    fun onLoadMore_failure_raisesErrorFooter_andKeepsLoadedPosts() {
        val fake =
            FakeGlobalTimelineFlow(
                outcome = loadedPage1("c1", fakeGlobalPost(id = "p1")),
                loadMorePages = listOf(GlobalTimelineOutcome.NetworkError),
            )
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.onLoadMore()

        assertTrue(viewModel.loadMoreError.value, "a failed load-more raises the non-destructive error footer")
        val loaded = viewModel.outcome.value as GlobalTimelineOutcome.Loaded
        assertEquals(listOf("p1"), loaded.posts.map { it.id }, "the loaded posts are retained on load-more failure")
    }

    @Test
    fun reload_clearsTheLoadMoreErrorFooter() {
        val fake =
            FakeGlobalTimelineFlow(
                outcome = loadedPage1("c1", fakeGlobalPost(id = "p1")),
                loadMorePages = listOf(GlobalTimelineOutcome.NetworkError),
            )
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))
        viewModel.onLoadMore()
        assertTrue(viewModel.loadMoreError.value)

        viewModel.reload()

        assertFalse(viewModel.loadMoreError.value, "a refresh resets paging — the load-more footer state clears")
    }

    @Test
    fun onLoadMore_isSuppressedWhileARefreshIsInFlight() {
        // suspendFromCall = 2 → the reload's loadFirstPage suspends, so the refresh stays in flight.
        val fake =
            FakeGlobalTimelineFlow(
                outcome = loadedPage1("c1", fakeGlobalPost(id = "p1")),
                suspendFromCall = 2,
                loadMorePages = listOf(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(id = "p2")), "c2", null)),
            )
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))

        viewModel.reload()
        assertTrue(viewModel.isRefreshing.value, "the reload is in flight")
        viewModel.onLoadMore()

        assertTrue(fake.loadMoreCalls.isEmpty(), "load-more is suppressed while a refresh is in flight (canLoadMore gate)")
    }

    // timeline-card-report-kebab: the authorship gate is FAIL-CLOSED — no kebab action while the self
    // id is unresolved, for a post gone from the loaded set, or for the viewer's own post; only a
    // resolved-self + other-author post yields an action (spec § "The viewer's own post exposes no
    // report entry point").
    @Test
    fun reportActionFor_isFailClosed_andYieldsAnActionOnlyForAnotherAuthorsLoadedPost() {
        val fake = FakeGlobalTimelineFlow(loadedWith(fakeGlobalPost(id = "p1", authorUserId = "other")))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(), FakeReportSubmitter(), FakeSelfUserId("self"))

        assertNull(viewModel.reportActionFor("p1", selfUserId = null), "unresolved self id → no action (fail-closed)")
        assertNull(viewModel.reportActionFor("gone", selfUserId = "self"), "post not in the loaded set → no action")
        assertNull(viewModel.reportActionFor("p1", selfUserId = "other"), "own post → no action")
        val action = viewModel.reportActionFor("p1", selfUserId = "self")
        assertTrue(action != null, "another author's loaded post → an action")
        action.invoke()
        assertEquals("p1", viewModel.reportingPostId.value, "the action opens the dialog targeting the tapped post")
    }

    // Activates the WhileSubscribed(5000) uiState share (on the Unconfined Main) so uiState.value reflects
    // the projected state in these synchronous tests; the collector is abandoned at test end (no runTest).
    private fun GlobalTimelineViewModel.activateUiState() {
        CoroutineScope(Dispatchers.Main).launch { uiState.collect {} }
    }
}
