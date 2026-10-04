package id.nearyou.app.timeline

import id.nearyou.distance.LatLng
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
 * `LocationUnavailableException`), which the screen maps to the existing retryable error state. Radii in
 * [gatedRadii] answer the server Premium gate ([NearbyFetchResult.PremiumGated]) on BOTH page-1 and
 * load-more — the fake "server" rejecting a Free viewer's non-20 km radius (`radius_premium_only`).
 */
class FakeNearbyTimelineFlow(
    private val outcome: NearbyTimelineOutcome = NearbyTimelineOutcome.Loaded(emptyList(), null, null),
    private val suspendForever: Boolean = false,
    private val suspendFromCall: Int = Int.MAX_VALUE,
    private val failWith: Throwable? = null,
    loadMorePages: List<NearbyTimelineOutcome> = emptyList(),
) : NearbyTimelineFlow {
    var loadInvocationCount: Int = 0
        private set

    private val pages = ArrayDeque(loadMorePages)

    /** Records the (cursor, anchor) of each [loadMore] call so a test can assert the anchor is reused. */
    val loadMoreCalls: MutableList<Pair<String, LatLng>> = mutableListOf()

    /** Records the radiusM of each [loadFirstPage] call (mobile-nearby-radius-slider). */
    val loadFirstPageRadii: MutableList<Int> = mutableListOf()

    /** Records the radiusM of each [loadMore] call. */
    val loadMoreRadii: MutableList<Int> = mutableListOf()

    /** Radii the fake server gates with `403 radius_premium_only` (page-1 AND load-more) — mutable so a test
     *  can gate a radius mid-session (a stale tier). Empty = nothing gated. */
    var gatedRadii: Set<Int> = emptySet()

    /** Programmable page-1 outcome for a non-gated radius; defaults to the constructor [outcome]. */
    var firstPageOutcome: NearbyTimelineOutcome = outcome

    override suspend fun loadFirstPage(radiusM: Int): NearbyFetchResult {
        loadInvocationCount++
        loadFirstPageRadii += radiusM
        if (suspendForever || loadInvocationCount >= suspendFromCall) awaitCancellation()
        failWith?.let { throw it }
        return if (radiusM in gatedRadii) NearbyFetchResult.PremiumGated else NearbyFetchResult.Loaded(firstPageOutcome)
    }

    override suspend fun loadMore(
        cursor: String,
        anchor: LatLng,
        radiusM: Int,
    ): NearbyFetchResult {
        loadMoreCalls += cursor to anchor
        loadMoreRadii += radiusM
        if (radiusM in gatedRadii) return NearbyFetchResult.PremiumGated
        // Default to an end page (empty + null cursor) so a test that programs no pages still terminates.
        return NearbyFetchResult.Loaded(
            if (pages.isEmpty()) NearbyTimelineOutcome.Loaded(emptyList(), null, null) else pages.removeFirst(),
        )
    }
}

/** Shared fixture: a fully-populated post (incl. PII fields) for projection / parsing / render tests. */
fun fakeNearbyPost(
    id: String = "p1",
    authorUserId: String = "11111111-1111-1111-1111-111111111111",
    authorUsername: String = "raka.jkt",
    authorDisplayName: String = "Raka Pratama",
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
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        latitude = latitude,
        longitude = longitude,
        distanceM = distanceM,
        cityName = cityName,
        createdAt = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
    )
