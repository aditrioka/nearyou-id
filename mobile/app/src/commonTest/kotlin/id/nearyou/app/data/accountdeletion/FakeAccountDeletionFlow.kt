package id.nearyou.app.data.accountdeletion

/**
 * Test double for [AccountDeletionFlow] — drives a specific outcome per call and counts invocations so
 * the ViewModel/screen tests can assert behaviour + (non-)invocation without a backend (mirrors
 * `FakeBlockedUsersFlow`).
 */
class FakeAccountDeletionFlow(
    var requestOutcome: AccountDeletionOutcome = AccountDeletionOutcome.Scheduled("2026-07-17T00:00:00Z"),
    var statusOutcome: DeletionStatusOutcome = DeletionStatusOutcome.NotPending,
    var cancelOutcome: DeletionCancelOutcome = DeletionCancelOutcome.Success,
) : AccountDeletionFlow {
    var requestCount: Int = 0
        private set
    var statusCount: Int = 0
        private set
    var cancelCount: Int = 0
        private set

    override suspend fun requestDeletion(): AccountDeletionOutcome {
        requestCount++
        return requestOutcome
    }

    override suspend fun fetchStatus(): DeletionStatusOutcome {
        statusCount++
        return statusOutcome
    }

    override suspend fun cancelDeletion(): DeletionCancelOutcome {
        cancelCount++
        return cancelOutcome
    }
}
