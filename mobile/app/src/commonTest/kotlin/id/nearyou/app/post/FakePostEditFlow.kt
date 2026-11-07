package id.nearyou.app.post

import kotlinx.coroutines.CompletableDeferred

/**
 * Test-only [PostEditFlow] for the `EditPostViewModel` + post-detail screen tests. Returns pre-programmed
 * per-operation outcomes and records invocation counts + last args so a test can drive a specific edit /
 * history / refresh path and assert the wiring (e.g. "the editor attempted the PATCH with no premium
 * pre-check" → `editCount == 1`). Mirrors `FakePostDetailFlow` / `FakeSearchFlow`. [refreshOutcomesByPost] overrides
 * [refreshOutcome] per post id, [refreshedPostIds] records each refresh, and [refreshGate], when set, suspends
 * [refreshPost] until completed (the in-flight window) — the chat thread's per-card live edit read uses all three.
 */
class FakePostEditFlow(
    private val editOutcome: PostEditOutcome = PostEditOutcome.Success("isi baru"),
    private val historyOutcome: EditHistoryOutcome = EditHistoryOutcome.Loaded(emptyList()),
    private val refreshOutcome: PostRefreshOutcome = PostRefreshOutcome.Unavailable,
    private val refreshOutcomesByPost: Map<String, PostRefreshOutcome> = emptyMap(),
    var refreshGate: CompletableDeferred<Unit>? = null,
) : PostEditFlow {
    var editCount: Int = 0
        private set

    var lastEditContent: String? = null
        private set

    var loadHistoryCount: Int = 0
        private set

    var refreshCount: Int = 0
        private set

    val refreshedPostIds: MutableList<String> = mutableListOf()

    override suspend fun editPost(
        postId: String,
        content: String,
    ): PostEditOutcome {
        editCount++
        lastEditContent = content
        return editOutcome
    }

    override suspend fun loadHistory(postId: String): EditHistoryOutcome {
        loadHistoryCount++
        return historyOutcome
    }

    override suspend fun refreshPost(postId: String): PostRefreshOutcome {
        refreshCount++
        refreshedPostIds += postId
        refreshGate?.await()
        return refreshOutcomesByPost[postId] ?: refreshOutcome
    }
}
