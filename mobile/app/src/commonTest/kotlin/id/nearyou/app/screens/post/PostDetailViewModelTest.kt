package id.nearyou.app.screens.post

import androidx.lifecycle.ViewModelStore
import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.block.FakeBlockSubmitter
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.data.report.ReportTargetType
import id.nearyou.app.post.FakePostDetailFlow
import id.nearyou.app.post.FakePostEditFlow
import id.nearyou.app.post.LikeCountOutcome
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.PostRefreshOutcome
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.ReplyDeleteOutcome
import id.nearyou.app.post.ReplyPostOutcome
import id.nearyou.app.post.fakeReply
import id.nearyou.app.screens.routing.PostDetailRoute
import id.nearyou.app.screens.username.FakeSelfUserIdProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SELF = "self-user"

/**
 * Unit coverage of [PostDetailViewModel] — the NavEntry-scoped holder for the whole post-detail surface
 * (post-detail-vm-reply-delete-restyle, #542), read through its single `uiState`:
 *  - replies + cursor paging (first page once on construction, append + cursor, end-reached no-op, the
 *    non-destructive load-more failure, reload resets the footer);
 *  - the reply composer (gate, synchronous in-flight claim, 201 prepend + count + the `replyPosted` one-shot,
 *    re-fetch when replies never loaded, failure outcomes kept until the cap is dismissed);
 *  - the like toggle (optimistic flip + exact-count revert, in-flight guard, the count read);
 *  - the resume freshness read + the session self id (`selfResolved`, `isOwn`);
 *  - own-reply delete (#497: optimistic remove + decrement, positional restore on failure, gates);
 *  - report + block (dialog targets, outcome → message mapping, pop-back / row removal);
 *  - **cancel safety** (design D2): a like / reply / delete write parked mid-flight still completes after
 *    the ViewModelStore is cleared (the Nav3 pop path).
 *
 * `viewModelScope` dispatches on `Dispatchers.Main` = an [UnconfinedTestDispatcher], so the VM's launches run
 * eagerly against the (non-suspending unless gated) fake; a background collector keeps the `WhileSubscribed`
 * uiState shared (the ProfileViewModelTest idiom).
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

    private fun route(
        replyCount: Int = 2,
        likedByViewer: Boolean = false,
        authorUsername: String = "raka.jkt",
    ) = PostDetailRoute(
        postId = "p1",
        content = "halo",
        cityName = "Jakarta Selatan",
        distanceM = null,
        createdAtIso = "2026-06-06T10:00:00Z",
        likedByViewer = likedByViewer,
        replyCount = replyCount,
        authorUsername = authorUsername,
        authorDisplayName = "Raka Pratama",
    )

    /** Creates the VM and activates a background collector so the `WhileSubscribed` uiState shares. */
    private fun TestScope.vm(
        flow: FakePostDetailFlow = FakePostDetailFlow(repliesOutcome = loaded(null, "r1")),
        editFlow: FakePostEditFlow = FakePostEditFlow(),
        self: String? = SELF,
        route: PostDetailRoute = route(),
        reportSubmitter: FakeReportSubmitter = FakeReportSubmitter(),
        blockSubmitter: FakeBlockSubmitter = FakeBlockSubmitter(),
    ): PostDetailViewModel {
        val viewModel =
            PostDetailViewModel(route, flow, editFlow, FakeSelfUserIdProvider(self), reportSubmitter, blockSubmitter)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        return viewModel
    }

    private val PostDetailViewModel.ui: PostDetailUiState get() = uiState.value

    private fun PostDetailViewModel.replyIds(): List<String> =
        when (val replies = ui.replies) {
            is RepliesUiState.Content -> replies.replies.map { it.id }
            else -> emptyList()
        }

    /** The Nav3 pop path: clearing the store the VM lives in runs onCleared → cancels viewModelScope. */
    private fun PostDetailViewModel.popEntry() {
        ViewModelStore().apply {
            put("postDetail", this@popEntry)
            clear()
        }
    }

    // ---- replies + paging ----

    @Test
    fun init_loadsRepliesOnce_andSeedsFromThePayload() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = loaded(null, "r1"))
            val viewModel = vm(fake)
            assertEquals(1, fake.loadRepliesCount, "the first replies page loads exactly once on construction")
            assertEquals(listOf("r1"), viewModel.replyIds())
            assertEquals(2, viewModel.ui.replyCount, "the header count seeds from the nav arg")
            assertEquals("halo", viewModel.ui.content, "the header content seeds from the nav arg")
            assertFalse(viewModel.ui.liked)
        }

    @Test
    fun onLoadMore_appendsBelow_advancesCursor_andRecordsTheCursor() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = loaded("c1", "r1"), loadMoreRepliesPages = listOf(loaded("c2", "r2")))
            val viewModel = vm(fake)

            viewModel.onLoadMore()

            assertEquals(listOf("r1", "r2"), viewModel.replyIds(), "page 2 appends below page 1")
            assertEquals(listOf("c1"), fake.loadMoreRepliesCalls, "load-more fetched the retained cursor c1")
        }

    @Test
    fun onLoadMore_whenEndReached_isNoOp() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = loaded(null, "r1"))
            val viewModel = vm(fake)

            viewModel.onLoadMore()

            assertTrue(fake.loadMoreRepliesCalls.isEmpty(), "no load-more when the cursor is null (end-reached)")
        }

    @Test
    fun onLoadMore_failure_raisesFooter_andKeepsReplies() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = loaded("c1", "r1"), loadMoreRepliesPages = listOf(RepliesOutcome.NetworkError))
            val viewModel = vm(fake)

            viewModel.onLoadMore()

            assertTrue(viewModel.ui.loadMoreError, "a failed load-more raises the non-destructive footer")
            assertEquals(listOf("r1"), viewModel.replyIds(), "the loaded replies are retained on failure")
        }

    @Test
    fun reloadReplies_clearsLoadMoreError() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = loaded("c1", "r1"), loadMoreRepliesPages = listOf(RepliesOutcome.NetworkError))
            val viewModel = vm(fake)
            viewModel.onLoadMore()
            assertTrue(viewModel.ui.loadMoreError)

            viewModel.reloadReplies()

            assertFalse(viewModel.ui.loadMoreError, "a reload resets the load-more footer state")
        }

    // ---- reply composer ----

    @Test
    fun submitReply_201_prepends_bumpsCount_withoutRefetch_andRaisesTheDraftClearOneShot() =
        runTest {
            val fake =
                FakePostDetailFlow(
                    repliesOutcome = loaded("c1", "r1"),
                    replyOutcome = ReplyPostOutcome.Success(fakeReply(id = "rNew")),
                )
            val viewModel = vm(fake)

            viewModel.onSubmitReply("halo")

            assertEquals("halo", fake.lastReplyContent)
            assertEquals(listOf("rNew", "r1"), viewModel.replyIds(), "the new reply is prepended (top of page 1)")
            assertEquals(3, viewModel.ui.replyCount, "the header count bumps")
            assertEquals(1, fake.loadRepliesCount, "the prepend does NOT trigger a replies re-fetch")
            assertTrue(viewModel.ui.replyPosted, "the screen is told to clear its draft")
            assertFalse(viewModel.ui.replyInFlight)

            viewModel.onReplyPostedShown()
            assertFalse(viewModel.ui.replyPosted, "the one-shot clears after the screen consumed it")
        }

    @Test
    fun submitReply_prependLeavesAppendedPagesUndisturbed() =
        runTest {
            val fake =
                FakePostDetailFlow(
                    repliesOutcome = loaded("c1", "r1"),
                    loadMoreRepliesPages = listOf(loaded("c2", "r2")),
                    replyOutcome = ReplyPostOutcome.Success(fakeReply(id = "rNew")),
                )
            val viewModel = vm(fake)
            viewModel.onLoadMore()

            viewModel.onSubmitReply("halo")

            assertEquals(listOf("rNew", "r1", "r2"), viewModel.replyIds(), "the prepend lands on top; page 2 is undisturbed")
        }

    @Test
    fun submitReply_whenRepliesNotLoaded_refetchesPageOne() =
        runTest {
            val fake =
                FakePostDetailFlow(
                    repliesOutcome = RepliesOutcome.NetworkError,
                    secondRepliesOutcome = loaded(null, "rNew"),
                    replyOutcome = ReplyPostOutcome.Success(fakeReply(id = "rNew")),
                )
            val viewModel = vm(fake, route = route(replyCount = 0))

            viewModel.onSubmitReply("halo")

            assertEquals(2, fake.loadRepliesCount, "a reply posted while replies aren't loaded re-fetches page 1")
            assertEquals(listOf("rNew"), viewModel.replyIds())
            assertEquals(1, viewModel.ui.replyCount, "the header count still bumps")
        }

    @Test
    fun submitReply_blankOrOverLimit_issuesNoPost() =
        runTest {
            val fake = FakePostDetailFlow()
            val viewModel = vm(fake)

            viewModel.onSubmitReply("   ")
            viewModel.onSubmitReply("a".repeat(MAX_REPLY_CONTENT_CODE_POINTS + 1))

            assertEquals(0, fake.postReplyCount, "the composer gate blocks empty and over-limit content")
        }

    @Test
    fun submitReply_doubleSubmit_issuesOnePost() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fake = FakePostDetailFlow(replyGate = gate)
            val viewModel = vm(fake)

            viewModel.onSubmitReply("halo")
            assertTrue(viewModel.ui.replyInFlight, "the in-flight slot is claimed synchronously")
            viewModel.onSubmitReply("halo")
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fake.postReplyCount, "the second tap while in flight is rejected")
        }

    @Test
    fun submitReply_rateLimited_keepsTheOutcome_untilTheCapIsDismissed() =
        runTest {
            val fake = FakePostDetailFlow(replyOutcome = ReplyPostOutcome.RateLimited(retryAfterSeconds = 3600))
            val viewModel = vm(fake)

            viewModel.onSubmitReply("halo")

            assertEquals(ReplyPostOutcome.RateLimited(3600), viewModel.ui.replyOutcome)
            assertFalse(viewModel.ui.replyPosted, "a failure never clears the draft")
            viewModel.onReplyCapDismissed()
            assertNull(viewModel.ui.replyOutcome)
        }

    @Test
    fun submitReply_completesAfterTheEntryIsPopped() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fake = FakePostDetailFlow(replyGate = gate)
            val viewModel = vm(fake)

            viewModel.onSubmitReply("halo")
            assertEquals(1, fake.postReplyCount, "the POST is parked on the gate")
            viewModel.popEntry()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fake.postReplyCompleted, "the issued POST ran to completion despite the cleared VM")
        }

    // ---- like ----

    @Test
    fun likeCount_isReadOnce_andDegradesToNull() =
        runTest {
            val available = FakePostDetailFlow(likeCountOutcome = LikeCountOutcome.Available(42))
            assertEquals(42L, vm(available).ui.likeCount)
            assertEquals(1, available.likeCountCount)

            assertNull(vm(FakePostDetailFlow(likeCountOutcome = LikeCountOutcome.Unavailable)).ui.likeCount)
        }

    @Test
    fun toggleLike_flipsOptimistically_andKeepsTheFlipOnSuccess() =
        runTest {
            val fake = FakePostDetailFlow(toggleOutcome = LikeOutcome.Liked, likeCountOutcome = LikeCountOutcome.Available(5))
            val viewModel = vm(fake)

            viewModel.onToggleLike()

            assertTrue(viewModel.ui.liked)
            assertEquals(6L, viewModel.ui.likeCount)
            assertEquals(false, fake.lastToggleCurrentlyLiked, "the toggle carries the pre-tap direction")
            assertFalse(viewModel.ui.likeInFlight)
        }

    @Test
    fun toggleLike_failure_restoresTheExactPriorState_andSurfacesTheOutcome() =
        runTest {
            val fake =
                FakePostDetailFlow(
                    toggleOutcome = LikeOutcome.RateLimited(retryAfterSeconds = 60),
                    likeCountOutcome = LikeCountOutcome.Available(5),
                )
            val viewModel = vm(fake)

            viewModel.onToggleLike()

            assertFalse(viewModel.ui.liked, "the optimistic flip is reverted")
            assertEquals(5L, viewModel.ui.likeCount, "the exact pre-tap count is restored")
            assertEquals(LikeOutcome.RateLimited(60), viewModel.ui.likeOutcome)
            viewModel.onLikeCapDismissed()
            assertNull(viewModel.ui.likeOutcome)
        }

    @Test
    fun toggleLike_doubleTap_issuesOneToggle() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fake = FakePostDetailFlow(toggleGate = gate)
            val viewModel = vm(fake)

            viewModel.onToggleLike()
            viewModel.onToggleLike()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fake.toggleLikeCount, "the second tap while in flight is rejected")
            assertTrue(viewModel.ui.liked, "the first tap's flip stands")
        }

    @Test
    fun toggleLike_completesAfterTheEntryIsPopped() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fake = FakePostDetailFlow(toggleGate = gate)
            val viewModel = vm(fake)

            viewModel.onToggleLike()
            assertEquals(1, fake.toggleLikeCount, "the like is parked on the gate")
            assertEquals(0, fake.toggleLikeCompleted)
            viewModel.popEntry()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fake.toggleLikeCompleted, "the issued like ran to completion despite the cleared VM")
        }

    // ---- freshness read + session id ----

    @Test
    fun refreshPost_populatesTheFreshnessFields() =
        runTest {
            val edit =
                FakePostEditFlow(
                    refreshOutcome =
                        PostRefreshOutcome.Loaded(
                            "isi baru",
                            editedAt = "2026-06-07T00:00:00Z",
                            isAuthor = true,
                            authorUserId = "A",
                        ),
                )
            val viewModel = vm(editFlow = edit)

            viewModel.refreshPost()

            assertEquals("isi baru", viewModel.ui.content)
            assertEquals("2026-06-07T00:00:00Z", viewModel.ui.editedAtIso)
            assertTrue(viewModel.ui.isAuthor)
            assertEquals("A", viewModel.ui.authorUserId)
        }

    @Test
    fun refreshPost_unavailable_keepsThePayload() =
        runTest {
            val viewModel = vm(editFlow = FakePostEditFlow(refreshOutcome = PostRefreshOutcome.Unavailable))

            viewModel.refreshPost()

            assertEquals("halo", viewModel.ui.content)
            assertNull(viewModel.ui.authorUserId)
            assertFalse(viewModel.ui.isAuthor)
        }

    @Test
    fun selfId_stampsIsOwn_andANullIdKeepsBothGatesClosed() =
        runTest {
            val replies = RepliesOutcome.Loaded(listOf(fakeReply(id = "mine", authorId = SELF)), nextCursor = null)

            val resolved = vm(FakePostDetailFlow(repliesOutcome = replies), self = SELF)
            assertTrue(resolved.ui.selfResolved)
            assertTrue(assertIs<RepliesUiState.Content>(resolved.ui.replies).replies.single().isOwn)

            val unresolved = vm(FakePostDetailFlow(repliesOutcome = replies), self = null)
            assertFalse(unresolved.ui.selfResolved, "the block item stays absent until the session id resolves")
            assertFalse(assertIs<RepliesUiState.Content>(unresolved.ui.replies).replies.single().isOwn, "fail closed")
        }

    // ---- own-reply delete (#497) ----

    private fun ownReplies(vararg ids: String) =
        RepliesOutcome.Loaded(ids.map { fakeReply(id = it, authorId = if (it == "mine") SELF else "other") }, nextCursor = null)

    @Test
    fun deleteConfirmed_removesTheRow_decrementsTheCount_andKeepsItOnDeleted() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = ownReplies("a", "mine", "c"), deleteOutcome = ReplyDeleteOutcome.Deleted)
            val viewModel = vm(fake, route = route(replyCount = 3))

            viewModel.onDeleteReplyClicked("mine")
            assertEquals("mine", viewModel.ui.deleteTarget, "the confirmation dialog opens")
            viewModel.onDeleteReplyConfirmed()

            assertNull(viewModel.ui.deleteTarget, "confirming closes the dialog")
            assertEquals(listOf("a", "c"), viewModel.replyIds())
            assertEquals(2, viewModel.ui.replyCount)
            assertEquals(listOf("p1" to "mine"), fake.deleteReplyCalls)
            assertFalse(viewModel.ui.deleteFailed)
        }

    @Test
    fun deleteFailed_restoresTheRowAtItsPosition_andTheCount_andRaisesTheMessage() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = ownReplies("a", "mine", "c"), deleteOutcome = ReplyDeleteOutcome.NetworkError)
            val viewModel = vm(fake, route = route(replyCount = 3))

            viewModel.onDeleteReplyClicked("mine")
            viewModel.onDeleteReplyConfirmed()

            assertEquals(listOf("a", "mine", "c"), viewModel.replyIds(), "the row is back at its original position")
            assertEquals(3, viewModel.ui.replyCount, "the count is restored")
            assertTrue(viewModel.ui.deleteFailed)
            viewModel.onDeleteMessageShown()
            assertFalse(viewModel.ui.deleteFailed, "the one-shot clears after the snackbar")
        }

    @Test
    fun deleteFailed_neverDuplicatesAReplyARelistAlreadyRestored() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fake =
                FakePostDetailFlow(
                    repliesOutcome = ownReplies("a", "mine"),
                    secondRepliesOutcome = ownReplies("mine", "a"),
                    deleteOutcome = ReplyDeleteOutcome.NetworkError,
                    deleteGate = gate,
                )
            val viewModel = vm(fake)
            viewModel.onDeleteReplyClicked("mine")
            viewModel.onDeleteReplyConfirmed()
            viewModel.reloadReplies() // the reload re-lists "mine" while the DELETE is in flight

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("mine", "a"), viewModel.replyIds(), "the restore skips an already re-listed reply")
        }

    @Test
    fun deleteFailed_onAZeroCount_addsBackNothing() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = ownReplies("mine"), deleteOutcome = ReplyDeleteOutcome.NetworkError)
            val viewModel = vm(fake, route = route(replyCount = 0))

            viewModel.onDeleteReplyClicked("mine")
            viewModel.onDeleteReplyConfirmed()

            assertEquals(0, viewModel.ui.replyCount, "the floor at 0 subtracted nothing, so nothing is added back")
        }

    @Test
    fun deleteDialogDismissed_issuesNoRequest() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = ownReplies("mine"))
            val viewModel = vm(fake)

            viewModel.onDeleteReplyClicked("mine")
            viewModel.onDeleteReplyDialogDismissed()

            assertNull(viewModel.ui.deleteTarget)
            assertTrue(fake.deleteReplyCalls.isEmpty(), "Batal issues ZERO deletes")
            assertEquals(listOf("mine"), viewModel.replyIds())
        }

    @Test
    fun deleteOfAnotherUsersReply_isANoOp() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = ownReplies("a", "mine"))
            val viewModel = vm(fake)

            viewModel.onDeleteReplyClicked("a")
            viewModel.onDeleteReplyConfirmed()

            assertTrue(fake.deleteReplyCalls.isEmpty(), "a stranger's reply is never deleted (the 204 would hide it locally)")
            assertEquals(listOf("a", "mine"), viewModel.replyIds())
        }

    @Test
    fun deleteWithAnUnresolvedSessionId_isANoOp() =
        runTest {
            val fake = FakePostDetailFlow(repliesOutcome = ownReplies("mine"))
            val viewModel = vm(fake, self = null)

            viewModel.onDeleteReplyClicked("mine")
            viewModel.onDeleteReplyConfirmed()

            assertTrue(fake.deleteReplyCalls.isEmpty(), "fail closed while the session id is unknown")
        }

    @Test
    fun deleteReply_completesAfterTheEntryIsPopped() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fake = FakePostDetailFlow(repliesOutcome = ownReplies("mine"), deleteGate = gate)
            val viewModel = vm(fake)

            viewModel.onDeleteReplyClicked("mine")
            viewModel.onDeleteReplyConfirmed()
            assertEquals(1, fake.deleteReplyCalls.size, "the DELETE is parked on the gate")
            assertEquals(0, fake.deleteReplyCompleted)
            viewModel.popEntry()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fake.deleteReplyCompleted, "the issued DELETE ran to completion despite the cleared VM")
        }

    // ---- mobile-content-report: the report dialog target + outcome→message mapping ----

    @Test
    fun onReportPostClicked_setsPostTarget_andSubmitsThePostId() =
        runTest {
            val submitter = FakeReportSubmitter(ReportOutcome.Submitted)
            val viewModel = vm(reportSubmitter = submitter)
            viewModel.onReportPostClicked()
            assertEquals(ReportTarget.Post, viewModel.ui.reportTarget, "the post report dialog targets the post")

            viewModel.onReportSubmitted(ReportReasonCategory.SPAM, note = null)

            assertNull(viewModel.ui.reportTarget, "submitting dismisses the dialog")
            assertEquals(ReportTargetType.POST, submitter.lastTarget)
            assertEquals("p1", submitter.lastTargetId, "the post report target_id is the post id")
            assertEquals(ReportReasonCategory.SPAM, submitter.lastCategory)
        }

    @Test
    fun onReportReplyClicked_setsReplyTarget_andSubmitsTheReplyIdOnly() =
        runTest {
            val submitter = FakeReportSubmitter(ReportOutcome.Submitted)
            val viewModel = vm(reportSubmitter = submitter)
            viewModel.onReportReplyClicked("r1")
            assertEquals(ReportTarget.Reply("r1"), viewModel.ui.reportTarget)

            viewModel.onReportSubmitted(ReportReasonCategory.HARASSMENT, note = "spam")

            assertEquals(ReportTargetType.REPLY, submitter.lastTarget)
            assertEquals("r1", submitter.lastTargetId, "the reply report target_id is the reply id")
            assertEquals("spam", submitter.lastNote)
        }

    @Test
    fun onReportDialogDismissed_clearsTheTarget_withoutSubmitting() =
        runTest {
            val submitter = FakeReportSubmitter()
            val viewModel = vm(reportSubmitter = submitter)
            viewModel.onReportReplyClicked("r1")

            viewModel.onReportDialogDismissed()

            assertNull(viewModel.ui.reportTarget)
            assertEquals(0, submitter.submitCount, "dismiss issues no submission")
        }

    @Test
    fun reportSubmitted_maps_andTheMessageClearsAfterShown() =
        runTest {
            val viewModel = vm(reportSubmitter = FakeReportSubmitter(ReportOutcome.Submitted))
            viewModel.onReportPostClicked()
            viewModel.onReportSubmitted(ReportReasonCategory.SPAM, note = null)
            assertEquals(PostDetailReportMessage.SUCCESS, viewModel.ui.reportMessage)

            viewModel.onReportMessageShown()
            assertNull(viewModel.ui.reportMessage, "the one-shot message clears after being shown")
        }

    @Test
    fun reportDuplicate_mapsToTheSameSuccessMessage_asSubmitted() =
        runTest {
            // Anti-enumeration (design D3): Duplicate is INDISTINGUISHABLE from Submitted on this surface.
            val submitter = FakeReportSubmitter(ReportOutcome.Duplicate)
            val viewModel = vm(reportSubmitter = submitter)
            viewModel.onReportPostClicked()

            viewModel.onReportSubmitted(ReportReasonCategory.SPAM, note = null)

            assertEquals(PostDetailReportMessage.SUCCESS, viewModel.ui.reportMessage, "Duplicate maps to the SAME success message")
            assertEquals(1, submitter.submitCount, "the Duplicate path fires exactly one submission (no retry)")
        }

    @Test
    fun reportRateLimited_mapsToRateLimitMessage() =
        runTest {
            val viewModel = vm(reportSubmitter = FakeReportSubmitter(ReportOutcome.RateLimited(retryAfterSeconds = 60)))
            viewModel.onReportPostClicked()

            viewModel.onReportSubmitted(ReportReasonCategory.OTHER, note = null)

            assertEquals(PostDetailReportMessage.RATE_LIMITED, viewModel.ui.reportMessage)
        }

    @Test
    fun reportNetworkError_mapsToFailedMessage() =
        runTest {
            val viewModel = vm(reportSubmitter = FakeReportSubmitter(ReportOutcome.NetworkError))
            viewModel.onReportReplyClicked("r1")

            viewModel.onReportSubmitted(ReportReasonCategory.OTHER, note = null)

            assertEquals(PostDetailReportMessage.FAILED, viewModel.ui.reportMessage)
        }

    // ---- mobile-block-from-content: the block dialog target + outcome mapping + nav/removal effects ----

    /** A VM whose freshness read resolved the post author "author-A" (a non-authored post). */
    private fun TestScope.blockVm(
        blockSubmitter: FakeBlockSubmitter,
        isAuthor: Boolean = false,
        authorUserId: String? = "author-A",
    ): PostDetailViewModel {
        val edit =
            FakePostEditFlow(
                refreshOutcome = PostRefreshOutcome.Loaded("halo", editedAt = null, isAuthor = isAuthor, authorUserId = authorUserId),
            )
        return vm(FakePostDetailFlow(repliesOutcome = loaded(null, "r1", "r2")), editFlow = edit, blockSubmitter = blockSubmitter)
            .also { it.refreshPost() }
    }

    @Test
    fun postBlock_confirmed_submitsTheAuthorUuid_popsBack_andShowsSuccess() =
        runTest {
            val submitter = FakeBlockSubmitter(BlockOutcome.Blocked)
            val viewModel = blockVm(submitter)
            viewModel.onBlockPostClicked()
            assertEquals(BlockTarget.Post("author-A", "raka.jkt"), viewModel.ui.blockTarget)

            viewModel.onBlockConfirmed()

            assertNull(viewModel.ui.blockTarget, "confirming dismisses the dialog")
            assertEquals("author-A", submitter.lastUserId, "the post block targets the freshness-read author UUID")
            assertEquals(PostDetailBlockMessage.SUCCESS, viewModel.ui.blockMessage)
            assertTrue(viewModel.ui.blockPopBack, "a confirmed post block pops the screen")
            assertEquals(listOf("r1", "r2"), viewModel.replyIds(), "a post block removes no reply row")

            viewModel.onBlockPoppedBack()
            assertFalse(viewModel.ui.blockPopBack, "the pop one-shot clears after the pop")
            viewModel.onBlockMessageShown()
            assertNull(viewModel.ui.blockMessage, "the one-shot message clears after being shown")
        }

    @Test
    fun postBlockClicked_isANoOp_withoutAnAuthorUserId_orOnAnOwnPost() =
        runTest {
            val unresolved = blockVm(FakeBlockSubmitter(), authorUserId = null)
            unresolved.onBlockPostClicked()
            assertNull(unresolved.ui.blockTarget, "no freshness-read author → no block target")

            val own = blockVm(FakeBlockSubmitter(), isAuthor = true, authorUserId = "self")
            own.onBlockPostClicked()
            assertNull(own.ui.blockTarget, "the viewer's own post is never a block target")
        }

    @Test
    fun replyBlock_confirmed_submitsTheReplyAuthor_removesTheRow_withoutPopOrCountChange() =
        runTest {
            val submitter = FakeBlockSubmitter(BlockOutcome.Blocked)
            val viewModel = blockVm(submitter)
            viewModel.onBlockReplyClicked(replyId = "r1", authorId = "author-B", username = "sinta.mhr")
            assertEquals(BlockTarget.Reply("r1", "author-B", "sinta.mhr"), viewModel.ui.blockTarget)

            viewModel.onBlockConfirmed()

            assertEquals("author-B", submitter.lastUserId, "the reply block targets the reply author_id")
            assertEquals(listOf("r2"), viewModel.replyIds(), "the blocked reply row is removed locally")
            assertFalse(viewModel.ui.blockPopBack, "a reply block never pops the screen")
            assertEquals(PostDetailBlockMessage.SUCCESS, viewModel.ui.blockMessage)
            assertEquals(2, viewModel.ui.replyCount, "the public viewer-independent counter is NOT decremented")
        }

    @Test
    fun blockRateLimited_showsRateLimitMessage_withNoPopAndNoRemoval() =
        runTest {
            val viewModel = blockVm(FakeBlockSubmitter(BlockOutcome.RateLimited(retryAfterSeconds = 60)))
            viewModel.onBlockReplyClicked(replyId = "r1", authorId = "author-B", username = "sinta.mhr")

            viewModel.onBlockConfirmed()

            assertEquals(PostDetailBlockMessage.RATE_LIMITED, viewModel.ui.blockMessage)
            assertEquals(listOf("r1", "r2"), viewModel.replyIds(), "a rate-limited block removes nothing")
            assertFalse(viewModel.ui.blockPopBack, "a rate-limited block never navigates")
        }

    @Test
    fun blockNetworkError_showsFailedMessage_withNoPopAndNoRemoval() =
        runTest {
            val viewModel = blockVm(FakeBlockSubmitter(BlockOutcome.NetworkError))
            viewModel.onBlockPostClicked()

            viewModel.onBlockConfirmed()

            assertEquals(PostDetailBlockMessage.FAILED, viewModel.ui.blockMessage)
            assertEquals(listOf("r1", "r2"), viewModel.replyIds())
            assertFalse(viewModel.ui.blockPopBack, "a failed block never navigates")
        }

    @Test
    fun onBlockDialogDismissed_clearsTheTarget_withZeroSubmissions() =
        runTest {
            val submitter = FakeBlockSubmitter()
            val viewModel = blockVm(submitter)
            viewModel.onBlockReplyClicked(replyId = "r1", authorId = "author-B", username = "sinta.mhr")

            viewModel.onBlockDialogDismissed()

            assertNull(viewModel.ui.blockTarget)
            assertEquals(0, submitter.submitCount, "Batal issues ZERO block submissions")
            assertEquals(listOf("r1", "r2"), viewModel.replyIds())
        }
}
