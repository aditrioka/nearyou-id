package id.nearyou.app.timeline

import kotlinx.coroutines.awaitCancellation

/**
 * Test-only [NearbyTimelineFlow] for screen tests. Returns a pre-programmed [NearbyTimelineOutcome]
 * and counts invocations so a test can assert pull-to-refresh / retry re-invokes the fetch (mirrors
 * `FakeAuthFlow`). With [suspendForever] = true, `loadFirstPage` never returns — the screen stays
 * in-flight, so the Loading state can be asserted. With [suspendFromCall] = N, only the Nth (and later)
 * call suspends forever — so the FIRST load can complete (a `Loaded` outcome + `isInitialLoad = false`)
 * and a subsequent `reload()` can be observed mid-flight (`isRefreshing = true`, the prior outcome
 * retained). With [failWith] set, `loadFirstPage` throws it after counting the invocation — used to
 * simulate the granted-but-no-fix path (the real `LocationProvider` throwing
 * `LocationUnavailableException`), which the screen maps to the existing retryable error state.
 */
class FakeNearbyTimelineFlow(
    private val outcome: NearbyTimelineOutcome = NearbyTimelineOutcome.Loaded(emptyList(), null, null),
    private val suspendForever: Boolean = false,
    private val suspendFromCall: Int = Int.MAX_VALUE,
    private val failWith: Throwable? = null,
) : NearbyTimelineFlow {
    var loadInvocationCount: Int = 0
        private set

    override suspend fun loadFirstPage(): NearbyTimelineOutcome {
        loadInvocationCount++
        if (suspendForever || loadInvocationCount >= suspendFromCall) awaitCancellation()
        failWith?.let { throw it }
        return outcome
    }
}

/** Shared fixture: a fully-populated post (incl. PII fields) for projection / parsing / render tests. */
fun fakeNearbyPost(
    id: String = "p1",
    authorUserId: String = "11111111-1111-1111-1111-111111111111",
    content: String = "Halo dari sekitar sini",
    latitude: Double = -6.21,
    longitude: Double = 106.85,
    distanceM: Double = 1234.5,
    cityName: String = "Jakarta",
    createdAt: String = "2026-05-31T10:00:00Z",
    likedByViewer: Boolean = false,
    replyCount: Int = 2,
): NearbyPostDto =
    NearbyPostDto(
        id = id,
        authorUserId = authorUserId,
        content = content,
        latitude = latitude,
        longitude = longitude,
        distanceM = distanceM,
        cityName = cityName,
        createdAt = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
    )
