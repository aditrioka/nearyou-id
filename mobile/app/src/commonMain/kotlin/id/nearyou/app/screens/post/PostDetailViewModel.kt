package id.nearyou.app.screens.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.data.report.ReportTargetType
import id.nearyou.app.post.LikeCountOutcome
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.PostRefreshOutcome
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.ReplyDeleteOutcome
import id.nearyou.app.post.ReplyDto
import id.nearyou.app.post.ReplyPostOutcome
import id.nearyou.app.screens.routing.PostDetailRoute
import id.nearyou.app.ui.timeline.LoadMoreController
import id.nearyou.app.ui.timeline.LoadMorePage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The NavEntry-scoped state holder for the whole post-detail surface (docs/11 § 2.2), resolved via
 * `viewModel { PostDetailViewModel(route, …) }` keyed to the `PostDetailRoute` entry. As of
 * `post-detail-vm-reply-delete-restyle` it owns EVERY post-detail network operation — the replies list +
 * cursor paging (via the shared [LoadMoreController]), the like toggle + like count, the reply POST, the
 * own-reply DELETE, the resume-time freshness read ([refreshPost]), the session self-id read, and the
 * report / block submissions — and exposes ONE [uiState] (`stateIn(WhileSubscribed)`) over a single private
 * [VmState] (the `ProfileViewModel` shape). Nothing launches from the composable any more.
 *
 * **Cancel-safe writes (design D2).** The network leg of each write (like, reply, delete, report, block) runs under
 * [NonCancellable]: the VM survives rotation and a push-forward (the paywall), but popping the entry clears
 * it and cancels `viewModelScope` — an already-issued write must still complete (the server applies it
 * anyway; aborting it only loses the client's view of it). The state update after the write is skipped by
 * `withContext`'s prompt-cancellation check once the VM is gone. Reads stay cancellable.
 *
 * One-shots are nullable / boolean state fields cleared by callbacks — never a `Channel`/`SharedFlow` bus:
 * the report / block / delete messages, the post-block pop-back, and [PostDetailUiState.replyPosted] (the
 * screen clears its saveable reply draft, then calls [onReplyPostedShown]).
 *
 * Counter semantics: a posted reply bumps [PostDetailUiState.replyCount]; an own-reply delete decrements it
 * (the public `reply_count` excludes soft-deleted replies); a reply **block** does NOT (the counter is the
 * public, viewer-independent aggregate — the documented post-replies-v8 tradeoff).
 */
class PostDetailViewModel(
    private val route: PostDetailRoute,
    private val flow: PostDetailFlow,
    private val editFlow: PostEditFlow,
    private val selfUserIdProvider: SelfUserIdProvider,
    private val reportSubmitter: ReportSubmitter,
    private val blockSubmitter: BlockSubmitter,
) : ViewModel() {
    private val postId: String = route.postId

    private data class VmState(
        val content: String,
        val editedAtIso: String? = null,
        val isAuthor: Boolean = false,
        // The post author's UUID from the freshness read — the block target + profile nav arg only.
        val authorUserId: String? = null,
        // The session user id — stamps ReplyUi.isOwn; never projected itself.
        val selfUserId: String? = null,
        val liked: Boolean,
        val likeCount: Long? = null,
        val likeInFlight: Boolean = false,
        val likeOutcome: LikeOutcome? = null,
        val replyCount: Int,
        val repliesOutcome: RepliesOutcome? = null,
        val repliesInFlight: Boolean = true,
        val replyInFlight: Boolean = false,
        val replyOutcome: ReplyPostOutcome? = null,
        val replyPosted: Boolean = false,
        val reportTarget: ReportTarget? = null,
        val reportMessage: PostDetailReportMessage? = null,
        val blockTarget: BlockTarget? = null,
        val blockMessage: PostDetailBlockMessage? = null,
        val blockPopBack: Boolean = false,
        val deleteTarget: String? = null,
        val deleteFailed: Boolean = false,
    )

    private val state =
        MutableStateFlow(VmState(content = route.content, liked = route.likedByViewer, replyCount = route.replyCount))

    private val loadMoreController =
        LoadMoreController<ReplyDto>(
            scope = viewModelScope,
            currentCursor = { (state.value.repliesOutcome as? RepliesOutcome.Loaded)?.nextCursor },
            // No load-more while the first page (or a retry) is still loading; replies have no pull-to-refresh.
            canLoadMore = { !state.value.repliesInFlight },
            fetchPage = { cursor ->
                when (val outcome = flow.loadMoreReplies(postId, cursor)) {
                    is RepliesOutcome.Loaded -> LoadMorePage.Success(outcome.replies, outcome.nextCursor)
                    else -> LoadMorePage.Failure
                }
            },
            appendItems = { items, next ->
                state.update { s ->
                    val current = s.repliesOutcome
                    if (current is RepliesOutcome.Loaded) {
                        s.copy(
                            repliesOutcome = RepliesOutcome.Loaded(current.replies + items, next),
                        )
                    } else {
                        s
                    }
                }
            },
        )

    val uiState: StateFlow<PostDetailUiState> =
        combine(state, loadMoreController.isLoadingMore, loadMoreController.loadMoreError) { s, loadingMore, loadMoreError ->
            s.toUiState(loadingMore, loadMoreError)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.toUiState(false, false))

    init {
        loadReplies()
        // The like count is fetched once (no single-post GET); it degrades to null when unavailable.
        viewModelScope.launch {
            val count =
                when (val outcome = flow.likeCount(postId)) {
                    is LikeCountOutcome.Available -> outcome.count
                    LikeCountOutcome.Unavailable -> null
                }
            state.update { it.copy(likeCount = count) }
        }
        // The session user id for the reply authorship gates. Null (resolving / malformed token) keeps every
        // gate CLOSED: no delete item anywhere, and the block item never shows on an own reply.
        viewModelScope.launch {
            val self = selfUserIdProvider.selfUserId()
            state.update { it.copy(selfUserId = self) }
        }
    }

    // ---- freshness read (mobile-post-editing) ----

    /** The resume-time single-post freshness read (the screen forwards every `ON_RESUME`, incl. the return
     *  from the editor): freshens the content + reads `editedAt` (the "Diedit" label), `isAuthor` (the Edit
     *  gate) and `authorUserId` (the block target / profile arg). `Unavailable` degrades silently. */
    fun refreshPost() {
        viewModelScope.launch {
            when (val refresh = editFlow.refreshPost(postId)) {
                is PostRefreshOutcome.Loaded ->
                    state.update {
                        it.copy(
                            content = refresh.content,
                            editedAtIso = refresh.editedAt,
                            isAuthor = refresh.isAuthor,
                            authorUserId = refresh.authorUserId,
                        )
                    }
                PostRefreshOutcome.Unavailable -> Unit
            }
        }
    }

    // ---- replies list + paging ----

    /** Retry control (the replies error state) — re-fetches page 1, resetting paging. */
    fun reloadReplies() = loadReplies()

    private fun loadReplies() {
        viewModelScope.launch {
            state.update { it.copy(repliesInFlight = true) }
            // A (re)load resets paging — the fresh first page replaces any appended tail; clear the footer.
            loadMoreController.reset()
            val outcome =
                try {
                    flow.loadReplies(postId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    RepliesOutcome.NetworkError
                }
            state.update { it.copy(repliesOutcome = outcome, repliesInFlight = false) }
        }
    }

    /** Scroll-end trigger — appends the next replies page (no-op during the initial load or at end). */
    fun onLoadMore() = loadMoreController.loadMore()

    /** Retry control on the load-more error footer — re-issues for the still-current cursor. */
    fun onRetryLoadMore() = loadMoreController.retry()

    // ---- like ----

    /**
     * The like toggle: claims the in-flight slot + flips optimistically (± the count when shown) BEFORE the
     * launch (no same-frame double-tap window, 05-#10), then issues the cancel-safe write. A non-`Liked`/
     * `Unliked` outcome restores the EXACT pre-tap state — the count fetch may resolve between the flip and
     * the failure, so a delta-revert could drift off-by-one — and surfaces the outcome (banner / cap dialog).
     */
    fun onToggleLike() {
        val before = state.value
        if (before.likeInFlight) return
        val wasLiked = before.liked
        val priorCount = before.likeCount
        state.update {
            it.copy(
                liked = !wasLiked,
                likeCount = it.likeCount?.let { count -> if (wasLiked) count - 1 else count + 1 },
                likeOutcome = null,
                likeInFlight = true,
            )
        }
        viewModelScope.launch {
            val outcome = withContext(NonCancellable) { flow.toggleLike(postId, currentlyLiked = wasLiked) }
            state.update {
                when (outcome) {
                    LikeOutcome.Liked, LikeOutcome.Unliked -> it.copy(likeInFlight = false)
                    is LikeOutcome.RateLimited, LikeOutcome.PostGone, LikeOutcome.NetworkError ->
                        it.copy(liked = wasLiked, likeCount = priorCount, likeOutcome = outcome, likeInFlight = false)
                }
            }
        }
    }

    /** Clears the like-cap outcome (the cap dialog's dismiss / CTA). A post-gone / network banner is NOT a
     *  one-shot — it stays until the next toggle. */
    fun onLikeCapDismissed() {
        state.update { it.copy(likeOutcome = null) }
    }

    // ---- reply composer ----

    /**
     * Submits [content] (the screen's saveable draft) when the composer gate allows it: claims the in-flight
     * slot synchronously, then issues the cancel-safe POST. A `201` prepends the reply + bumps the count with
     * NO re-fetch (if replies never loaded, page 1 is re-fetched instead) and raises the
     * [PostDetailUiState.replyPosted] one-shot so the screen clears the draft; any other outcome surfaces as
     * [PostDetailUiState.replyOutcome] (banner / cap dialog) with the draft kept.
     */
    fun onSubmitReply(content: String) {
        if (!replyComposerUiState(content, state.value.replyInFlight).submitEnabled) return
        state.update { it.copy(replyInFlight = true) }
        viewModelScope.launch {
            val outcome = withContext(NonCancellable) { flow.postReply(postId, content) }
            when (outcome) {
                is ReplyPostOutcome.Success -> {
                    prependPostedReply(outcome.reply)
                    state.update { it.copy(replyInFlight = false, replyOutcome = null, replyPosted = true) }
                }
                is ReplyPostOutcome.RateLimited,
                ReplyPostOutcome.PostGone,
                ReplyPostOutcome.InvalidContent,
                ReplyPostOutcome.NetworkError,
                -> state.update { it.copy(replyInFlight = false, replyOutcome = outcome) }
            }
        }
    }

    /** Clears the [PostDetailUiState.replyPosted] one-shot after the screen has cleared its draft. */
    fun onReplyPostedShown() {
        state.update { it.copy(replyPosted = false) }
    }

    /** Clears the reply-cap outcome (the cap dialog's dismiss / CTA). The draft is untouched; a post-gone /
     *  network banner stays until the next successful reply. */
    fun onReplyCapDismissed() {
        state.update { it.copy(replyOutcome = null) }
    }

    /** Prepends the posted reply (the list is newest-first, so it lands on top of page 1; appended later pages
     *  are undisturbed) + bumps the count. If replies never loaded the prepend has nowhere to land, so page 1
     *  is re-fetched instead — the fresh page includes the reply at its true position. */
    private fun prependPostedReply(reply: ReplyDto) {
        val loaded = state.value.repliesOutcome is RepliesOutcome.Loaded
        state.update { s ->
            val current = s.repliesOutcome
            s.copy(
                repliesOutcome =
                    if (current is RepliesOutcome.Loaded) {
                        RepliesOutcome.Loaded(
                            listOf(reply) + current.replies,
                            current.nextCursor,
                        )
                    } else {
                        current
                    },
                replyCount = s.replyCount + 1,
            )
        }
        if (!loaded) reloadReplies()
    }

    // ---- own-reply delete (#497) ----

    /** Opens the delete confirmation for one of the viewer's own replies (the row offers it only when own). */
    fun onDeleteReplyClicked(replyId: String) {
        state.update { it.copy(deleteTarget = replyId) }
    }

    /** "Batal" / scrim — closes the dialog with no request. */
    fun onDeleteReplyDialogDismissed() {
        state.update { it.copy(deleteTarget = null) }
    }

    /**
     * Confirms the delete: closes the dialog, removes the row + decrements the count optimistically, then
     * issues the cancel-safe DELETE. `Deleted` keeps the optimistic state (the backend `204` is idempotent).
     * `NetworkError` puts the row back at its index, restores the count, and raises [PostDetailUiState.deleteFailed].
     * Defence in depth: only a reply this session authored is ever deleted — the backend answers `204` for a
     * stranger's reply too (anti-enumeration), which would otherwise hide that reply locally for no reason.
     */
    fun onDeleteReplyConfirmed() {
        val before = state.value
        val replyId = before.deleteTarget ?: return
        state.update { it.copy(deleteTarget = null) }
        val replies = (before.repliesOutcome as? RepliesOutcome.Loaded)?.replies ?: return
        val index = replies.indexOfFirst { it.id == replyId }
        val reply = replies.getOrNull(index) ?: return
        if (before.selfUserId == null || reply.authorId != before.selfUserId) return
        val decremented = before.replyCount > 0
        state.update { s ->
            s.copy(
                repliesOutcome = s.repliesOutcome.withoutReply(replyId),
                replyCount = if (decremented) s.replyCount - 1 else s.replyCount,
            )
        }
        viewModelScope.launch {
            val outcome = withContext(NonCancellable) { flow.deleteReply(postId, replyId) }
            if (outcome == ReplyDeleteOutcome.NetworkError) {
                state.update { s ->
                    s.copy(
                        repliesOutcome = s.repliesOutcome.withReplyRestored(reply, index),
                        replyCount = if (decremented) s.replyCount + 1 else s.replyCount,
                        deleteFailed = true,
                    )
                }
            }
        }
    }

    /** Clears the [PostDetailUiState.deleteFailed] one-shot after the snackbar has shown it. */
    fun onDeleteMessageShown() {
        state.update { it.copy(deleteFailed = false) }
    }

    // ---- report (mobile-content-report) ----

    /** Opens the report dialog targeting the post (the screen gates this on `!isAuthor`); the post report
     *  `target_id` is this VM's post id. */
    fun onReportPostClicked() {
        state.update { it.copy(reportTarget = ReportTarget.Post) }
    }

    /** Opens the report dialog targeting a reply (ungated by authorship). Carries ONLY the reply id (the
     *  report `target_id`); no author identity is introduced. */
    fun onReportReplyClicked(replyId: String) {
        state.update { it.copy(reportTarget = ReportTarget.Reply(replyId)) }
    }

    /** Dismiss the report dialog without submitting. */
    fun onReportDialogDismissed() {
        state.update { it.copy(reportTarget = null) }
    }

    /**
     * Submits the report for the targeted content via the shared [reportSubmitter]: post → `target_type =
     * "post"`, `target_id = postId`; reply → `target_type = "reply"`, `target_id = <reply id>`. Dismisses the
     * dialog, then maps the outcome to the one-shot message (Submitted AND Duplicate → the SAME success message
     * — anti-enumeration). A no-op if no target is set.
     */
    fun onReportSubmitted(
        category: ReportReasonCategory,
        note: String?,
    ) {
        val target = state.value.reportTarget ?: return
        state.update { it.copy(reportTarget = null) }
        val (targetType, targetId) =
            when (target) {
                ReportTarget.Post -> ReportTargetType.POST to postId
                is ReportTarget.Reply -> ReportTargetType.REPLY to target.replyId
            }
        viewModelScope.launch {
            val outcome = withContext(NonCancellable) { reportSubmitter.submit(targetType, targetId, category, note) }
            state.update { it.copy(reportMessage = postDetailReportMessage(outcome)) }
        }
    }

    /** Clears the one-shot report message after the screen has shown it. */
    fun onReportMessageShown() {
        state.update { it.copy(reportMessage = null) }
    }

    // ---- block (mobile-block-from-content) ----

    /** Opens the block dialog targeting the POST author — only for a non-authored post whose freshness read
     *  resolved an `authorUserId` and whose payload carries a username (otherwise a no-op, matching the
     *  absent affordance). */
    fun onBlockPostClicked() {
        val current = state.value
        val target = current.authorUserId ?: return
        if (current.isAuthor || route.authorUsername.isEmpty()) return
        state.update { it.copy(blockTarget = BlockTarget.Post(targetUserId = target, username = route.authorUsername)) }
    }

    /** Opens the block dialog targeting a REPLY author (the row gates it on `!isOwn` + a non-blank wire
     *  username). [authorId] is used only as the block path param (never rendered). */
    fun onBlockReplyClicked(
        replyId: String,
        authorId: String,
        username: String,
    ) {
        state.update { it.copy(blockTarget = BlockTarget.Reply(replyId = replyId, targetUserId = authorId, username = username)) }
    }

    /** Dismiss the block dialog without blocking. */
    fun onBlockDialogDismissed() {
        state.update { it.copy(blockTarget = null) }
    }

    /**
     * Confirms the block via the shared [blockSubmitter]: `Blocked` on a POST target → success toast + the
     * pop-back one-shot (the just-blocked post 404s on any re-read); `Blocked` on a REPLY target → success
     * toast + local row removal WITHOUT touching the count (the public viewer-independent counter);
     * `RateLimited`/`NetworkError` → message only. A no-op if no target is set.
     */
    fun onBlockConfirmed() {
        val target = state.value.blockTarget ?: return
        state.update { it.copy(blockTarget = null) }
        viewModelScope.launch {
            val outcome = withContext(NonCancellable) { blockSubmitter.submit(target.targetUserId) }
            state.update { s ->
                val blocked = outcome == BlockOutcome.Blocked
                s.copy(
                    blockMessage = postDetailBlockMessage(outcome),
                    blockPopBack = s.blockPopBack || (blocked && target is BlockTarget.Post),
                    repliesOutcome =
                        if (blocked && target is BlockTarget.Reply) s.repliesOutcome.withoutReply(target.replyId) else s.repliesOutcome,
                )
            }
        }
    }

    /** Clears the one-shot block message after the screen has shown it. */
    fun onBlockMessageShown() {
        state.update { it.copy(blockMessage = null) }
    }

    /** Clears the one-shot pop-back after the screen has popped. */
    fun onBlockPoppedBack() {
        state.update { it.copy(blockPopBack = false) }
    }

    private fun VmState.toUiState(
        isLoadingMore: Boolean,
        loadMoreError: Boolean,
    ): PostDetailUiState =
        PostDetailUiState(
            content = content,
            editedAtIso = editedAtIso,
            isAuthor = isAuthor,
            authorUserId = authorUserId,
            liked = liked,
            likeCount = likeCount,
            likeInFlight = likeInFlight,
            likeOutcome = likeOutcome,
            replyCount = replyCount,
            replies = repliesUiState(repliesOutcome, repliesInFlight, selfUserId),
            selfResolved = selfUserId != null,
            isLoadingMore = isLoadingMore,
            loadMoreError = loadMoreError,
            replyInFlight = replyInFlight,
            replyOutcome = replyOutcome,
            replyPosted = replyPosted,
            reportTarget = reportTarget,
            reportMessage = reportMessage,
            blockTarget = blockTarget,
            blockMessage = blockMessage,
            blockPopBack = blockPopBack,
            deleteTarget = deleteTarget,
            deleteFailed = deleteFailed,
        )
}

/** The loaded list minus [replyId] (cursor untouched); any non-loaded outcome is returned as-is. */
private fun RepliesOutcome?.withoutReply(replyId: String): RepliesOutcome? =
    if (this is RepliesOutcome.Loaded) RepliesOutcome.Loaded(replies.filterNot { it.id == replyId }, nextCursor) else this

/** The loaded list with [reply] back at [index] — unless a reload already re-listed it.
 *  ponytail: index clamp, so a reply prepended while the DELETE was in flight shifts the restore by one row;
 *  re-anchor on the neighbouring reply id if that ever matters (the next reload reconciles it anyway). */
private fun RepliesOutcome?.withReplyRestored(
    reply: ReplyDto,
    index: Int,
): RepliesOutcome? {
    if (this !is RepliesOutcome.Loaded || replies.any { it.id == reply.id }) return this
    val at = index.coerceIn(0, replies.size)
    return RepliesOutcome.Loaded(replies.take(at) + reply + replies.drop(at), nextCursor)
}
