package id.nearyou.app.screens.timeline

import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.timeline.FakeGlobalTimelineFlow
import id.nearyou.app.timeline.GlobalPostDto
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.fakeGlobalPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow())
        assertEquals(1, fake.loadInvocationCount, "the first page loads exactly once on construction")
        assertTrue(viewModel.outcome.value is GlobalTimelineOutcome.Loaded, "the loaded outcome is exposed")
        assertFalse(viewModel.isInitialLoad.value, "isInitialLoad clears once the first outcome arrives")
        assertFalse(viewModel.isRefreshing.value, "isRefreshing is false after the initial load completes")
    }

    @Test
    fun reload_reFetchesPageOne() {
        val fake = FakeGlobalTimelineFlow(GlobalTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow())
        assertEquals(1, fake.loadInvocationCount)
        viewModel.reload()
        assertEquals(2, fake.loadInvocationCount, "reload re-fetches page 1 (pull-to-refresh / error retry)")
    }

    @Test
    fun reload_keepsPriorOutcome_andTogglesIsRefreshingNotIsInitialLoad() {
        // First load completes; the SECOND call (reload) suspends, so we observe the in-flight refresh:
        // isRefreshing = true, isInitialLoad stays false, prior outcome retained (design D3).
        val loaded = GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "RETAINED")), null, null)
        val fake = FakeGlobalTimelineFlow(loaded, suspendFromCall = 2)
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow())
        assertFalse(viewModel.isInitialLoad.value, "after the first load isInitialLoad is false")
        assertFalse(viewModel.isRefreshing.value, "not refreshing before reload")
        val priorOutcome = viewModel.outcome.value

        viewModel.reload()

        assertTrue(viewModel.isRefreshing.value, "a reload-in-flight sets isRefreshing")
        assertFalse(viewModel.isInitialLoad.value, "a reload does NOT re-enter the initial-load skeleton")
        assertEquals(priorOutcome, viewModel.outcome.value, "the prior outcome is retained during the refresh")
    }

    @Test
    fun loadFailure_mapsToExistingNetworkError() {
        val fake = FakeGlobalTimelineFlow(failWith = IllegalStateException("fetch threw"))
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow())
        assertEquals(
            GlobalTimelineOutcome.NetworkError,
            viewModel.outcome.value,
            "a fetch failure maps to the existing retryable NetworkError (no new outcome member)",
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
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(LikeOutcome.PostGone))

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
        val viewModel = GlobalTimelineViewModel(fake, FakeLikeFlow(LikeOutcome.NetworkError))

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
            )

        viewModel.toggleLike("p1", currentlyLiked = false)
        viewModel.toggleLike("p1", currentlyLiked = true)

        assertEquals(1, likeFlow.invocationCount, "the per-post in-flight guard ignores the re-tap")
    }
}
