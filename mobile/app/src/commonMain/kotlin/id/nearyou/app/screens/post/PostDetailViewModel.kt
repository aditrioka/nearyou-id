package id.nearyou.app.screens.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.ReplyDto
import id.nearyou.app.ui.timeline.LoadMoreController
import id.nearyou.app.ui.timeline.LoadMorePage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * NavEntry-scoped holder for the post-detail **replies list + paging** (`mobile-nearby-timeline-infinite-scroll`,
 * design D5 — mirrors the timeline-VM migration #167). Owns the replies outcome (list + cursor), the
 * initial-load flag, the cursor load-more footer state (via the shared [LoadMoreController]), and the
 * header reply count (bumped on a posted reply). The like state + the reply-composer field state stay
 * composition-local in `PostDetailScreen` (migrating those is a noted follow-up — this change moves only
 * the replies-list + paging state). Resolved via `viewModel { PostDetailViewModel(flow, postId, replyCount) }`
 * keyed to the `PostDetailRoute` entry, so the loaded replies + paging survive recomposition + config change.
 *
 * The optimistic new-reply behavior is preserved: [onReplyPosted] **prepends** the posted reply (the list
 * renders newest-first, so the fresh reply sits at the top of page 1) and bumps the count, with NO re-fetch —
 * appended later pages are undisturbed.
 */
class PostDetailViewModel(
    private val flow: PostDetailFlow,
    private val postId: String,
    initialReplyCount: Int,
) : ViewModel() {
    private val _repliesOutcome = MutableStateFlow<RepliesOutcome?>(null)
    val repliesOutcome: StateFlow<RepliesOutcome?> = _repliesOutcome.asStateFlow()

    private val _repliesInFlight = MutableStateFlow(true)
    val repliesInFlight: StateFlow<Boolean> = _repliesInFlight.asStateFlow()

    private val _replyCount = MutableStateFlow(initialReplyCount)
    val replyCount: StateFlow<Int> = _replyCount.asStateFlow()

    private val loadMoreController =
        LoadMoreController<ReplyDto>(
            scope = viewModelScope,
            currentCursor = { (_repliesOutcome.value as? RepliesOutcome.Loaded)?.nextCursor },
            // No load-more while the first page (or a retry) is still loading; replies have no pull-to-refresh.
            canLoadMore = { !_repliesInFlight.value },
            fetchPage = { cursor ->
                when (val outcome = flow.loadMoreReplies(postId, cursor)) {
                    is RepliesOutcome.Loaded -> LoadMorePage.Success(outcome.replies, outcome.nextCursor)
                    else -> LoadMorePage.Failure
                }
            },
            appendItems = { items, next ->
                val current = _repliesOutcome.value
                if (current is RepliesOutcome.Loaded) {
                    _repliesOutcome.value = RepliesOutcome.Loaded(current.replies + items, next)
                }
            },
        )

    /** True while a replies load-more page is in flight — drives only the list-end footer spinner. */
    val isLoadingMore: StateFlow<Boolean> = loadMoreController.isLoadingMore

    /** True after a failed replies load-more — drives the non-destructive retry footer. */
    val loadMoreError: StateFlow<Boolean> = loadMoreController.loadMoreError

    init {
        loadReplies()
    }

    /** Retry control (the replies error state) — re-fetches page 1, resetting paging. */
    fun reloadReplies() = loadReplies()

    private fun loadReplies() {
        viewModelScope.launch {
            _repliesInFlight.value = true
            // A (re)load resets paging — the fresh first page replaces any appended tail; clear the footer.
            loadMoreController.reset()
            try {
                _repliesOutcome.value = flow.loadReplies(postId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _repliesOutcome.value = RepliesOutcome.NetworkError
            } finally {
                _repliesInFlight.value = false
            }
        }
    }

    /** Scroll-end trigger — appends the next replies page (no-op during the initial load or at end). */
    fun onLoadMore() = loadMoreController.loadMore()

    /** Retry control on the load-more error footer — re-issues for the still-current cursor. */
    fun onRetryLoadMore() = loadMoreController.retry()

    /**
     * Called by the screen on a successful reply POST: prepend the new reply + bump the header count, with
     * NO list re-fetch. If replies never loaded (error/in-flight) the prepend has nowhere to land, so
     * re-fetch page 1 instead — the fresh page includes the reply at its true position.
     */
    fun onReplyPosted(reply: ReplyDto) {
        val current = _repliesOutcome.value
        if (current is RepliesOutcome.Loaded) {
            _repliesOutcome.value = RepliesOutcome.Loaded(listOf(reply) + current.replies, current.nextCursor)
            _replyCount.value += 1
        } else {
            reloadReplies()
            _replyCount.value += 1
        }
    }
}
