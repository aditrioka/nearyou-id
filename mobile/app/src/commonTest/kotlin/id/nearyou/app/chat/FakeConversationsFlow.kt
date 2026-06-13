package id.nearyou.app.chat

import kotlinx.coroutines.CompletableDeferred

/**
 * A [ConversationsFlow] test double mirroring `FakeGlobalTimelineFlow`: returns a configurable
 * [ConversationListOutcome], counts invocations, and (optionally) suspends from a given call so the
 * screen test can observe the initial-load skeleton / refresh.
 */
class FakeConversationsFlow(
    var outcome: ConversationListOutcome = ConversationListOutcome.Loaded(emptyList(), null),
    private val suspendForever: Boolean = false,
    private val suspendFromCall: Int = Int.MAX_VALUE,
) : ConversationsFlow {
    var loadInvocationCount: Int = 0
        private set

    override suspend fun loadFirstPage(): ConversationListOutcome {
        loadInvocationCount++
        if (suspendForever || loadInvocationCount >= suspendFromCall) CompletableDeferred<Unit>().await()
        return outcome
    }
}
