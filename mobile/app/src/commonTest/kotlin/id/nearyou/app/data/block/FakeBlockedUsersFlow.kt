package id.nearyou.app.data.block

/**
 * Test double for [BlockedUsersFlow] — returns a fixed [fetchOutcome] / [unblockOutcome] and records the
 * unblock calls (count + the last userId) so the screen / ViewModel tests can drive a specific outcome
 * and assert (non-)invocation without a backend (mirrors `FakeConsentFlow`).
 */
class FakeBlockedUsersFlow(
    private val fetchOutcome: BlockedUsersOutcome = BlockedUsersOutcome.Loaded(emptyList(), nextCursor = null),
    private val unblockOutcome: UnblockOutcome = UnblockOutcome.Success,
) : BlockedUsersFlow {
    var fetchInvocationCount = 0
        private set
    var unblockInvocationCount = 0
        private set
    var lastUnblockedUserId: String? = null
        private set

    override suspend fun fetchBlocks(cursor: String?): BlockedUsersOutcome {
        fetchInvocationCount++
        return fetchOutcome
    }

    override suspend fun unblock(userId: String): UnblockOutcome {
        unblockInvocationCount++
        lastUnblockedUserId = userId
        return unblockOutcome
    }
}
