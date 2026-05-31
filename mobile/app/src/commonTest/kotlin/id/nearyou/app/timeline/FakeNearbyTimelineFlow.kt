package id.nearyou.app.timeline

import kotlinx.coroutines.awaitCancellation

/**
 * Test-only [NearbyTimelineFlow] for screen tests. Returns a pre-programmed [NearbyTimelineOutcome]
 * and counts invocations so a test can assert pull-to-refresh / retry re-invokes the fetch (mirrors
 * `FakeAuthFlow`). With [suspendForever] = true, `loadFirstPage` never returns — the screen stays
 * in-flight, so the Loading state can be asserted.
 */
class FakeNearbyTimelineFlow(
    private val outcome: NearbyTimelineOutcome = NearbyTimelineOutcome.Loaded(emptyList(), null, null),
    private val suspendForever: Boolean = false,
) : NearbyTimelineFlow {
    var loadInvocationCount: Int = 0
        private set

    override suspend fun loadFirstPage(): NearbyTimelineOutcome {
        loadInvocationCount++
        if (suspendForever) awaitCancellation()
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
