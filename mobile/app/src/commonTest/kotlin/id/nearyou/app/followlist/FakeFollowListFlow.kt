package id.nearyou.app.followlist

/**
 * Test double for [FollowListFlow] (mirrors `FakeProfileFlow`). [responder] returns the outcome for a given
 * `(tab, cursor)` — tests configure it for pagination (switch on `cursor`), load-more failures, and the
 * constant 404. [calls] records every `(tab, cursor)` invocation for the call-count / no-duplicate-in-flight
 * assertions. The responder is `suspend`, so a test can hold a call open (e.g. via a `CompletableDeferred`)
 * to observe a mid-refresh `isRefreshing` transition.
 */
class FakeFollowListFlow : FollowListFlow {
    val calls = mutableListOf<Pair<FollowListTab, String?>>()

    var responder: suspend (tab: FollowListTab, cursor: String?) -> FollowListOutcome =
        { _, _ -> FollowListOutcome.Loaded(FollowListPage(emptyList(), null)) }

    override suspend fun load(
        userId: String,
        tab: FollowListTab,
        cursor: String?,
    ): FollowListOutcome {
        calls += tab to cursor
        return responder(tab, cursor)
    }

    fun callCount(tab: FollowListTab): Int = calls.count { it.first == tab }

    companion object {
        fun user(
            id: String,
            premium: Boolean = false,
        ): FollowListUser = FollowListUser(userId = id, username = "user_$id", displayName = "User $id", isPremium = premium)

        fun page(
            ids: List<String>,
            nextCursor: String?,
        ): FollowListOutcome = FollowListOutcome.Loaded(FollowListPage(ids.map { user(it) }, nextCursor))
    }
}
