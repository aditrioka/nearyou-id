package id.nearyou.app.data.block

import kotlinx.coroutines.awaitCancellation

/**
 * Test double for [BlockedUsersFlow] — returns a fixed [fetchOutcome] / [unblockOutcome] and records the
 * unblock calls (count + the last userId) so the screen / ViewModel tests can drive a specific outcome
 * and assert (non-)invocation without a backend (mirrors `FakeConsentFlow`).
 *
 * Load-more paging (mirrors `FakeNotificationsFlow`): a `fetchBlocks(cursor != null)` dequeues from
 * [loadMorePages] in order (empty queue → [BlockedUsersOutcome.RetryableError], so a test that programs
 * no pages still terminates) and records the cursor in [loadMoreCalls]; the first page (`cursor == null`)
 * keeps returning [fetchOutcome].
 */
class FakeBlockedUsersFlow(
    private val fetchOutcome: BlockedUsersOutcome = BlockedUsersOutcome.Loaded(emptyList(), nextCursor = null),
    private val unblockOutcome: UnblockOutcome = UnblockOutcome.Success,
    /** With N: the Nth (1-based) FIRST-PAGE fetch suspends forever — pins the refresh-in-flight gate. */
    private val suspendFromCall: Int = Int.MAX_VALUE,
    loadMorePages: List<BlockedUsersOutcome> = emptyList(),
) : BlockedUsersFlow {
    var fetchInvocationCount = 0
        private set
    var unblockInvocationCount = 0
        private set
    var lastUnblockedUserId: String? = null
        private set

    private val pages = ArrayDeque(loadMorePages)

    /** Each load-more (non-null) cursor, in call order — asserts the `cursor` param threads through. */
    val loadMoreCalls: MutableList<String> = mutableListOf()

    override suspend fun fetchBlocks(cursor: String?): BlockedUsersOutcome {
        if (cursor != null) {
            loadMoreCalls += cursor
            return pages.removeFirstOrNull() ?: BlockedUsersOutcome.RetryableError
        }
        fetchInvocationCount++
        if (fetchInvocationCount >= suspendFromCall) awaitCancellation()
        return fetchOutcome
    }

    override suspend fun unblock(userId: String): UnblockOutcome {
        unblockInvocationCount++
        lastUnblockedUserId = userId
        return unblockOutcome
    }
}
