package id.nearyou.app.post

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/**
 * Test-only [PostDetailFlow] for screen tests. Returns pre-programmed per-operation outcomes and records
 * invocation counts + last args so a test can drive a specific like/reply/replies/count path and assert
 * the wiring (e.g. "the 201 reply appended without a list re-fetch" → `loadRepliesCount` stays 1, or
 * "the like toggle invoked toggleLike with the route's current state") — mirrors `FakeCreatePostFlow` /
 * `FakeNearbyTimelineFlow`. With [suspendRepliesForever] = true, `loadReplies` never returns, so the
 * replies loading state can be asserted.
 *
 * `toggleLike` returns the single [toggleOutcome] regardless of `currentlyLiked` (the test programs the
 * direction it wants: `Liked` / `Unliked` for the happy path, `RateLimited`/`PostGone`/`NetworkError`
 * for the revert paths), recording the `currentlyLiked` arg for assertions.
 *
 * The optional `*Gate`s suspend the matching write until the test completes them, and the `*Completed`
 * counters tick only when the call returns normally — the cancel-safety tests clear the ViewModel while a
 * write is parked on its gate, release it, and assert the write still ran to completion.
 */
class FakePostDetailFlow(
    private val repliesOutcome: RepliesOutcome = RepliesOutcome.Loaded(emptyList(), nextCursor = null),
    private val toggleOutcome: LikeOutcome = LikeOutcome.Liked,
    private val replyOutcome: ReplyPostOutcome = ReplyPostOutcome.Success(fakeReply()),
    private val likeCountOutcome: LikeCountOutcome = LikeCountOutcome.Unavailable,
    private val suspendRepliesForever: Boolean = false,
    // When set, the SECOND+ `loadReplies` call returns this instead of [repliesOutcome] — lets a test
    // drive "error → retry → recovered" (the first load fails, the retry succeeds).
    private val secondRepliesOutcome: RepliesOutcome? = null,
    // Replies load-more pages, consumed in order (default: an end page — empty + null cursor).
    loadMoreRepliesPages: List<RepliesOutcome> = emptyList(),
    private val deleteOutcome: ReplyDeleteOutcome = ReplyDeleteOutcome.Deleted,
    private val toggleGate: CompletableDeferred<Unit>? = null,
    private val replyGate: CompletableDeferred<Unit>? = null,
    private val deleteGate: CompletableDeferred<Unit>? = null,
) : PostDetailFlow {
    private val loadMorePages = ArrayDeque(loadMoreRepliesPages)

    /** Records the cursor of each [loadMoreReplies] call so a test can assert the follow-up cursor. */
    val loadMoreRepliesCalls: MutableList<String> = mutableListOf()

    var loadRepliesCount: Int = 0
        private set

    var toggleLikeCount: Int = 0
        private set

    var postReplyCount: Int = 0
        private set

    var likeCountCount: Int = 0
        private set

    var lastToggleCurrentlyLiked: Boolean? = null
        private set

    var lastReplyContent: String? = null
        private set

    var toggleLikeCompleted: Int = 0
        private set

    var postReplyCompleted: Int = 0
        private set

    var deleteReplyCompleted: Int = 0
        private set

    /** Records the `(postId, replyId)` of each [deleteReply] call. */
    val deleteReplyCalls: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun loadReplies(postId: String): RepliesOutcome {
        loadRepliesCount++
        if (suspendRepliesForever) awaitCancellation()
        return if (loadRepliesCount >= 2 && secondRepliesOutcome != null) secondRepliesOutcome else repliesOutcome
    }

    override suspend fun loadMoreReplies(
        postId: String,
        cursor: String,
    ): RepliesOutcome {
        loadMoreRepliesCalls += cursor
        return if (loadMorePages.isEmpty()) RepliesOutcome.Loaded(emptyList(), nextCursor = null) else loadMorePages.removeFirst()
    }

    override suspend fun toggleLike(
        postId: String,
        currentlyLiked: Boolean,
    ): LikeOutcome {
        toggleLikeCount++
        lastToggleCurrentlyLiked = currentlyLiked
        toggleGate?.await()
        toggleLikeCompleted++
        return toggleOutcome
    }

    override suspend fun postReply(
        postId: String,
        content: String,
    ): ReplyPostOutcome {
        postReplyCount++
        lastReplyContent = content
        replyGate?.await()
        postReplyCompleted++
        return replyOutcome
    }

    override suspend fun likeCount(postId: String): LikeCountOutcome {
        likeCountCount++
        return likeCountOutcome
    }

    override suspend fun deleteReply(
        postId: String,
        replyId: String,
    ): ReplyDeleteOutcome {
        deleteReplyCalls += postId to replyId
        deleteGate?.await()
        deleteReplyCompleted++
        return deleteOutcome
    }
}

/** Shared fixture: a fully-populated reply (incl. the `author_id` PII + the near-dead `is_auto_hidden` /
 *  `deleted_at` wire fields) for projection / parsing / render tests. */
fun fakeReply(
    id: String = "r1",
    postId: String = "p1",
    authorId: String = "11111111-1111-1111-1111-111111111111",
    authorUsername: String? = "sinta.mhr",
    authorDisplayName: String? = "Sinta Maharani",
    content: String = "Halo balasan",
    isAutoHidden: Boolean = false,
    createdAt: String = "2026-06-06T10:00:00Z",
    updatedAt: String? = null,
    deletedAt: String? = null,
): ReplyDto =
    ReplyDto(
        id = id,
        postId = postId,
        authorId = authorId,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        isAutoHidden = isAutoHidden,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
