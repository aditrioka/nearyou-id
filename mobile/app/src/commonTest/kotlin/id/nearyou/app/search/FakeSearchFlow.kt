package id.nearyou.app.search

import kotlinx.coroutines.awaitCancellation

/**
 * Test-only [SearchFlow] for the ViewModel + screen tests (mirrors `FakeGlobalTimelineFlow`). Returns a
 * pre-programmed [SearchOutcome] per call — [firstOutcome] for an `offset == 0` fetch and [loadMoreOutcome]
 * (when set) for any `offset > 0` — and records the (query, offset) of every call so a test can assert
 * the debounce/submit/load-more wiring. With [suspendForever] = true, `search` never returns (the screen
 * stays Loading). [calls] exposes the captured arguments; [invocationCount] is their size.
 */
class FakeSearchFlow(
    private val firstOutcome: SearchOutcome = SearchOutcome.Results(emptyList(), null),
    private val loadMoreOutcome: SearchOutcome? = null,
    private val suspendForever: Boolean = false,
) : SearchFlow {
    data class Call(val query: String, val offset: Int)

    val calls: MutableList<Call> = mutableListOf()
    val invocationCount: Int get() = calls.size

    override suspend fun search(
        query: String,
        offset: Int,
    ): SearchOutcome {
        calls += Call(query, offset)
        if (suspendForever) awaitCancellation()
        return if (offset > 0 && loadMoreOutcome != null) loadMoreOutcome else firstOutcome
    }
}

/** Shared fixture: a fully-populated search hit (incl. the PII `authorId` + `rank`) for parsing /
 *  projection / render tests. */
fun fakeSearchHit(
    postId: String = "p1",
    authorId: String = "11111111-1111-1111-1111-111111111111",
    authorUsername: String = "dewi.kuliner",
    authorDisplayName: String = "Dewi Lestari",
    content: String = "Halo dari Jakarta",
    createdAt: String = "2026-05-31T10:00:00Z",
    rank: Float = 0.83f,
): SearchResultDto =
    SearchResultDto(
        postId = postId,
        authorId = authorId,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        createdAt = createdAt,
        rank = rank,
    )
