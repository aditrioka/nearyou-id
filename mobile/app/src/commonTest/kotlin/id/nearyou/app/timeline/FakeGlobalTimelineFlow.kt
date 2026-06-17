package id.nearyou.app.timeline

import kotlinx.coroutines.awaitCancellation

/**
 * Test-only [GlobalTimelineFlow] for screen tests (mirrors `FakeNearbyTimelineFlow`). Returns a
 * pre-programmed [GlobalTimelineOutcome] and counts invocations so a test can assert pull-to-refresh /
 * retry / tab-switch re-invokes (or does NOT re-invoke) the fetch. With [suspendForever] = true,
 * `loadFirstPage` never returns — the screen stays in-flight, so the Loading state can be asserted.
 * With [suspendFromCall] = N, only the Nth (and later) call suspends forever — so the FIRST load can
 * complete and a subsequent `reload()` can be observed mid-flight (`isRefreshing = true`, prior outcome
 * retained). With [failWith] set, `loadFirstPage` throws it after counting the invocation — the
 * ViewModel maps that to the existing retryable error state.
 */
class FakeGlobalTimelineFlow(
    private val outcome: GlobalTimelineOutcome = GlobalTimelineOutcome.Loaded(emptyList(), null, null),
    private val suspendForever: Boolean = false,
    private val suspendFromCall: Int = Int.MAX_VALUE,
    private val failWith: Throwable? = null,
    loadMorePages: List<GlobalTimelineOutcome> = emptyList(),
) : GlobalTimelineFlow {
    var loadInvocationCount: Int = 0
        private set

    private val pages = ArrayDeque(loadMorePages)

    /** Records the cursor of each [loadMore] call (cursor-only — Global has no anchor). */
    val loadMoreCalls: MutableList<String> = mutableListOf()

    override suspend fun loadFirstPage(): GlobalTimelineOutcome {
        loadInvocationCount++
        if (suspendForever || loadInvocationCount >= suspendFromCall) awaitCancellation()
        failWith?.let { throw it }
        return outcome
    }

    override suspend fun loadMore(cursor: String): GlobalTimelineOutcome {
        loadMoreCalls += cursor
        // Default to an end page (empty + null cursor) so a test that programs no pages still terminates.
        return if (pages.isEmpty()) GlobalTimelineOutcome.Loaded(emptyList(), null, null) else pages.removeFirst()
    }
}

/** Shared fixture: a fully-populated Global post (incl. PII fields, no distance) for projection / parsing / render tests. */
fun fakeGlobalPost(
    id: String = "p1",
    authorUserId: String = "11111111-1111-1111-1111-111111111111",
    authorUsername: String = "dewi.kuliner",
    authorDisplayName: String = "Dewi Lestari",
    content: String = "Halo dari seluruh Indonesia",
    latitude: Double = -6.21,
    longitude: Double = 106.85,
    cityName: String = "Jakarta",
    createdAt: String = "2026-05-31T10:00:00Z",
    likedByViewer: Boolean = false,
    replyCount: Int = 2,
): GlobalPostDto =
    GlobalPostDto(
        id = id,
        authorUserId = authorUserId,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        latitude = latitude,
        longitude = longitude,
        cityName = cityName,
        createdAt = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
    )
