package id.nearyou.app.post

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
) : PostDetailFlow {
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

    override suspend fun loadReplies(postId: String): RepliesOutcome {
        loadRepliesCount++
        if (suspendRepliesForever) awaitCancellation()
        return if (loadRepliesCount >= 2 && secondRepliesOutcome != null) secondRepliesOutcome else repliesOutcome
    }

    override suspend fun toggleLike(
        postId: String,
        currentlyLiked: Boolean,
    ): LikeOutcome {
        toggleLikeCount++
        lastToggleCurrentlyLiked = currentlyLiked
        return toggleOutcome
    }

    override suspend fun postReply(
        postId: String,
        content: String,
    ): ReplyPostOutcome {
        postReplyCount++
        lastReplyContent = content
        return replyOutcome
    }

    override suspend fun likeCount(postId: String): LikeCountOutcome {
        likeCountCount++
        return likeCountOutcome
    }
}

/** Shared fixture: a fully-populated reply (incl. the `author_id` PII + the near-dead `is_auto_hidden` /
 *  `deleted_at` wire fields) for projection / parsing / render tests. */
fun fakeReply(
    id: String = "r1",
    postId: String = "p1",
    authorId: String = "11111111-1111-1111-1111-111111111111",
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
        content = content,
        isAutoHidden = isAutoHidden,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
