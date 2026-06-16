package id.nearyou.app.screens.post

import id.nearyou.app.post.FakePostDetailFlow
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.fakeReply
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
import kotlin.test.assertTrue

/**
 * Unit coverage of [PostDetailViewModel] — the NavEntry-scoped holder for the post-detail replies list +
 * cursor paging + header count (`mobile-nearby-timeline-infinite-scroll`, design D5). Pins: the first
 * page loads once on construction; cursor load-more appends below + advances + records the retained
 * cursor; the end-reached terminal (null cursor) is a no-op; a failed load-more raises the footer while
 * retaining the loaded replies; the optimistic new-reply **prepend** bumps the count without a re-fetch
 * and leaves appended pages undisturbed; a reply posted while replies are NOT loaded re-fetches page 1;
 * and a reload resets the footer state.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] runs the init / load
 * coroutines eagerly + synchronously against the (non-suspending) fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDetailViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun loaded(
        cursor: String?,
        vararg ids: String,
    ) = RepliesOutcome.Loaded(ids.map { fakeReply(id = it) }, nextCursor = cursor)

    private fun PostDetailViewModel.replyIds(): List<String> =
        (repliesOutcome.value as RepliesOutcome.Loaded).replies.map { it.id }

    @Test
    fun init_loadsRepliesOnce_andExposesOutcome() {
        val fake = FakePostDetailFlow(repliesOutcome = loaded(null, "r1"))
        val viewModel = PostDetailViewModel(fake, postId = "p1", initialReplyCount = 2)
        assertEquals(1, fake.loadRepliesCount, "the first replies page loads exactly once on construction")
        assertEquals(listOf("r1"), viewModel.replyIds())
        assertFalse(viewModel.repliesInFlight.value, "repliesInFlight clears after the first load")
        assertEquals(2, viewModel.replyCount.value, "the header count seeds from the nav arg")
    }

    @Test
    fun onLoadMore_appendsBelow_advancesCursor_andRecordsTheCursor() {
        val fake =
            FakePostDetailFlow(
                repliesOutcome = loaded("c1", "r1"),
                loadMoreRepliesPages = listOf(loaded("c2", "r2")),
            )
        val viewModel = PostDetailViewModel(fake, postId = "p1", initialReplyCount = 0)

        viewModel.onLoadMore()

        assertEquals(listOf("r1", "r2"), viewModel.replyIds(), "page 2 appends below page 1")
        assertEquals(listOf("c1"), fake.loadMoreRepliesCalls, "load-more fetched the retained cursor c1")
    }

    @Test
    fun onLoadMore_whenEndReached_isNoOp() {
        val fake = FakePostDetailFlow(repliesOutcome = loaded(null, "r1"))
        val viewModel = PostDetailViewModel(fake, postId = "p1", initialReplyCount = 0)

        viewModel.onLoadMore()

        assertTrue(fake.loadMoreRepliesCalls.isEmpty(), "no load-more when the cursor is null (end-reached)")
    }

    @Test
    fun onLoadMore_failure_raisesFooter_andKeepsReplies() {
        val fake =
            FakePostDetailFlow(
                repliesOutcome = loaded("c1", "r1"),
                loadMoreRepliesPages = listOf(RepliesOutcome.NetworkError),
            )
        val viewModel = PostDetailViewModel(fake, postId = "p1", initialReplyCount = 0)

        viewModel.onLoadMore()

        assertTrue(viewModel.loadMoreError.value, "a failed load-more raises the non-destructive footer")
        assertEquals(listOf("r1"), viewModel.replyIds(), "the loaded replies are retained on failure")
    }

    @Test
    fun onReplyPosted_prepends_bumpsCount_withoutRefetch() {
        val fake = FakePostDetailFlow(repliesOutcome = loaded("c1", "r1"))
        val viewModel = PostDetailViewModel(fake, postId = "p1", initialReplyCount = 2)

        viewModel.onReplyPosted(fakeReply(id = "rNew"))

        assertEquals(listOf("rNew", "r1"), viewModel.replyIds(), "the new reply is prepended (top of page 1)")
        assertEquals(3, viewModel.replyCount.value, "the header count bumps")
        assertEquals(1, fake.loadRepliesCount, "the append does NOT trigger a replies re-fetch")
    }

    @Test
    fun onReplyPosted_prependLeavesAppendedPagesUndisturbed() {
        val fake =
            FakePostDetailFlow(
                repliesOutcome = loaded("c1", "r1"),
                loadMoreRepliesPages = listOf(loaded("c2", "r2")),
            )
        val viewModel = PostDetailViewModel(fake, postId = "p1", initialReplyCount = 0)
        viewModel.onLoadMore()

        viewModel.onReplyPosted(fakeReply(id = "rNew"))

        assertEquals(
            listOf("rNew", "r1", "r2"),
            viewModel.replyIds(),
            "the prepend lands at the top; the appended later page is undisturbed",
        )
    }

    @Test
    fun onReplyPosted_whenRepliesNotLoaded_refetchesPageOne() {
        val fake =
            FakePostDetailFlow(
                repliesOutcome = RepliesOutcome.NetworkError,
                secondRepliesOutcome = loaded(null, "rNew"),
            )
        val viewModel = PostDetailViewModel(fake, postId = "p1", initialReplyCount = 0)

        viewModel.onReplyPosted(fakeReply(id = "rNew"))

        assertEquals(2, fake.loadRepliesCount, "a reply posted while replies aren't loaded re-fetches page 1")
        assertEquals(1, viewModel.replyCount.value, "the header count still bumps")
    }

    @Test
    fun reloadReplies_clearsLoadMoreError() {
        val fake =
            FakePostDetailFlow(
                repliesOutcome = loaded("c1", "r1"),
                loadMoreRepliesPages = listOf(RepliesOutcome.NetworkError),
            )
        val viewModel = PostDetailViewModel(fake, postId = "p1", initialReplyCount = 0)
        viewModel.onLoadMore()
        assertTrue(viewModel.loadMoreError.value)

        viewModel.reloadReplies()

        assertFalse(viewModel.loadMoreError.value, "a reload resets the load-more footer state")
    }
}
