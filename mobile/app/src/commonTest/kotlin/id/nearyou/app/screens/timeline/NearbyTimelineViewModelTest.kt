package id.nearyou.app.screens.timeline

import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.block.FakeBlockSubmitter
import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyPostDto
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.RadiusChangeResult
import id.nearyou.app.timeline.fakeNearbyPost
import id.nearyou.distance.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage of [NearbyTimelineViewModel] — the `HomeRoute`-scoped holder for the Nearby feed's
 * load state (the reload-on-return fix, `mobile-nav-swap-to-navigation3` Decision 5). Pins: the first
 * page loads exactly once on construction, [NearbyTimelineViewModel.reload] re-fetches (pull-to-refresh
 * + error retry), a load failure maps to the EXISTING retryable [NearbyTimelineOutcome.NetworkError]
 * (no new outcome member), and the split-loading contract (design D3): a reload toggles `isRefreshing`
 * (NOT `isInitialLoad`) and RETAINS the prior outcome so the screen keeps rendering `Content`.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] is installed as Main
 * so the init/reload coroutines run eagerly and synchronously against the (non-suspending) fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyTimelineViewModelTest {
    /** Ctor helper (timeline-card-block-kebab): the self/report/block seams default to shared fakes. */
    private fun viewModelWith(
        flow: FakeNearbyTimelineFlow,
        likeFlow: FakeLikeFlow = FakeLikeFlow(),
        profileFlow: FakeProfileFlow = FakeProfileFlow(),
    ) = NearbyTimelineViewModel(flow, likeFlow, profileFlow, FakeSelfUserId("self"), FakeReportSubmitter(), FakeBlockSubmitter())

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_loadsFirstPageExactlyOnce_andExposesTheOutcome() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "X")), null, null))
        val viewModel = viewModelWith(fake)
        assertEquals(1, fake.loadInvocationCount, "the first page loads exactly once on construction")
        assertTrue(viewModel.outcome.value is NearbyTimelineOutcome.Loaded, "the loaded outcome is exposed")
        assertFalse(viewModel.isRefreshing.value, "isRefreshing is false after the initial load completes")
    }

    @Test
    fun reload_reFetchesPageOne() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = viewModelWith(fake)
        assertEquals(1, fake.loadInvocationCount)
        viewModel.reload()
        assertEquals(2, fake.loadInvocationCount, "reload re-fetches page 1 (pull-to-refresh / error retry)")
    }

    @Test
    fun reload_keepsPriorOutcome_andTogglesIsRefreshing_uiStateStaysContent() {
        // The first load completes (a Loaded outcome → uiState Content); the SECOND call (reload) suspends,
        // so we observe the in-flight refresh: isRefreshing = true, uiState stays Content (NOT Loading), and
        // the prior outcome is retained (not nulled) so the screen keeps rendering Content (design D3).
        val loaded = NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "RETAINED")), null, null)
        val fake = FakeNearbyTimelineFlow(loaded, suspendFromCall = 2)
        val viewModel = viewModelWith(fake)
        viewModel.activateUiState()
        assertTrue(viewModel.uiState.value is NearbyTimelineUiState.Content, "after the first load uiState is Content")
        assertFalse(viewModel.isRefreshing.value, "not refreshing before reload")
        val priorOutcome = viewModel.outcome.value

        viewModel.reload()

        assertTrue(viewModel.isRefreshing.value, "a reload-in-flight sets isRefreshing")
        assertTrue(
            viewModel.uiState.value is NearbyTimelineUiState.Content,
            "a reload does NOT re-enter the Loading skeleton (uiState stays Content)",
        )
        assertEquals(priorOutcome, viewModel.outcome.value, "the prior outcome is retained during the refresh")
    }

    @Test
    fun loadFailure_mapsToExistingNetworkError() {
        val fake = FakeNearbyTimelineFlow(failWith = IllegalStateException("granted but no fix"))
        val viewModel = viewModelWith(fake)
        viewModel.activateUiState()
        assertEquals(
            NearbyTimelineOutcome.NetworkError,
            viewModel.outcome.value,
            "a coordinate/network failure maps to the existing retryable NetworkError (no new outcome member)",
        )
        assertTrue(viewModel.uiState.value is NearbyTimelineUiState.Error, "and uiState projects to Error")
    }

    @Test
    fun uiState_delegatesToTheProjection() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "X")), null, null))
        val viewModel = viewModelWith(fake)
        viewModel.activateUiState()
        // The single uiState equals the pure projection for the held outcome (reused, not reimplemented);
        // after the first outcome arrives the initial-load flag is false.
        assertEquals(
            nearbyTimelineUiState(viewModel.outcome.value, isInitialLoad = false),
            viewModel.uiState.value,
            "uiState delegates to nearbyTimelineUiState(outcome, isInitialLoad)",
        )
        assertTrue(viewModel.uiState.value is NearbyTimelineUiState.Content, "a loaded outcome projects to Content")
    }

    @Test
    fun uiState_retainsContentAcrossAFreshCollector() {
        // The config-change proxy: the entry-scoped VM retains the resolved outcome, so a FRESH uiState
        // collector (the recomposed screen) still sees Content — not a reset to Loading.
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "X")), null, null))
        val viewModel = viewModelWith(fake)
        viewModel.activateUiState()
        assertTrue(viewModel.uiState.value is NearbyTimelineUiState.Content, "loaded → Content")
        viewModel.activateUiState() // a second, fresh collector (the config-change case)
        assertTrue(
            viewModel.uiState.value is NearbyTimelineUiState.Content,
            "a fresh collector still sees the retained Content (not reset to Loading)",
        )
    }

    // ---- mobile-inline-post-actions: the inline-like delegation to the shared controller ----

    private fun loadedWith(vararg posts: NearbyPostDto) = NearbyTimelineOutcome.Loaded(posts.toList(), null, null)

    private fun NearbyTimelineViewModel.likedOf(postId: String): Boolean =
        (outcome.value as NearbyTimelineOutcome.Loaded).posts.first { it.id == postId }.likedByViewer

    @Test
    fun toggleLike_flipsThePostInTheRetainedLoadedOutcome_bothDirections() {
        val likeFlow = FakeLikeFlow(LikeOutcome.Liked)
        val viewModel =
            NearbyTimelineViewModel(
                FakeNearbyTimelineFlow(loadedWith(fakeNearbyPost(id = "p1", likedByViewer = false))),
                likeFlow,
                FakeProfileFlow(),
                FakeSelfUserId("self"),
                FakeReportSubmitter(),
                FakeBlockSubmitter(),
            )

        viewModel.toggleLike("p1", currentlyLiked = false)
        assertTrue(viewModel.likedOf("p1"), "the like flip lands in the retained Loaded outcome")
        assertEquals("p1" to false, likeFlow.invocations.single(), "the like direction is currentlyLiked = false")

        likeFlow.outcome = LikeOutcome.Unliked
        viewModel.toggleLike("p1", currentlyLiked = true)
        assertFalse(viewModel.likedOf("p1"), "the unlike flip lands too (direction from the CURRENT state)")
        assertEquals("p1" to true, likeFlow.invocations.last(), "the unlike direction is currentlyLiked = true")
    }

    @Test
    fun rateLimitedLike_reverts_andRaisesTheOneShotCapState_dismissClears() {
        val likeFlow = FakeLikeFlow(LikeOutcome.RateLimited(retryAfterSeconds = 51540))
        val viewModel =
            NearbyTimelineViewModel(
                FakeNearbyTimelineFlow(loadedWith(fakeNearbyPost(id = "p1", likedByViewer = false))),
                likeFlow,
                FakeProfileFlow(),
                FakeSelfUserId("self"),
                FakeReportSubmitter(),
                FakeBlockSubmitter(),
            )

        viewModel.toggleLike("p1", currentlyLiked = false)

        assertFalse(viewModel.likedOf("p1"), "the optimistic flip is reverted on the 429")
        assertEquals(51540L, viewModel.likeCapRetryAfterSeconds.value, "the one-shot cap state carries Retry-After")
        viewModel.onLikeCapDialogDismissed()
        assertNull(viewModel.likeCapRetryAfterSeconds.value, "dismiss clears the one-shot state")
    }

    @Test
    fun postGoneLike_reverts_andSelfHealsViaReload() {
        val fake = FakeNearbyTimelineFlow(loadedWith(fakeNearbyPost(id = "p1", likedByViewer = false)))
        val viewModel =
            NearbyTimelineViewModel(
                fake,
                FakeLikeFlow(LikeOutcome.PostGone),
                FakeProfileFlow(),
                FakeSelfUserId("self"),
                FakeReportSubmitter(),
                FakeBlockSubmitter(),
            )

        viewModel.toggleLike("p1", currentlyLiked = false)

        assertFalse(viewModel.likedOf("p1"), "the flip is reverted on PostGone")
        assertEquals(2, fake.loadInvocationCount, "PostGone triggers the existing reload (self-heal)")
    }

    @Test
    fun networkErrorLike_revertsSilently() {
        val fake = FakeNearbyTimelineFlow(loadedWith(fakeNearbyPost(id = "p1", likedByViewer = false)))
        val viewModel =
            NearbyTimelineViewModel(
                fake,
                FakeLikeFlow(LikeOutcome.NetworkError),
                FakeProfileFlow(),
                FakeSelfUserId("self"),
                FakeReportSubmitter(),
                FakeBlockSubmitter(),
            )

        viewModel.toggleLike("p1", currentlyLiked = false)

        assertFalse(viewModel.likedOf("p1"), "the flip is reverted on NetworkError")
        assertNull(viewModel.likeCapRetryAfterSeconds.value, "no cap state — the declared silent v1 posture")
        assertEquals(1, fake.loadInvocationCount, "no reload on NetworkError")
    }

    @Test
    fun inFlightLikeReTaps_areIgnored() {
        val likeFlow = FakeLikeFlow().apply { suspendForever = true }
        val viewModel =
            NearbyTimelineViewModel(
                FakeNearbyTimelineFlow(loadedWith(fakeNearbyPost(id = "p1", likedByViewer = false))),
                likeFlow,
                FakeProfileFlow(),
                FakeSelfUserId("self"),
                FakeReportSubmitter(),
                FakeBlockSubmitter(),
            )

        viewModel.toggleLike("p1", currentlyLiked = false)
        viewModel.toggleLike("p1", currentlyLiked = true)

        assertEquals(1, likeFlow.invocationCount, "the per-post in-flight guard ignores the re-tap")
    }

    // ---- mobile-nearby-timeline-infinite-scroll: cursor load-more (anchor reuse) ----

    private val testAnchor = LatLng(lat = -6.2, lng = 106.8)

    private fun loadedPage1(
        cursor: String?,
        vararg posts: NearbyPostDto,
    ) = NearbyTimelineOutcome.Loaded(posts.toList(), cursor, null, anchor = testAnchor)

    @Test
    fun onLoadMore_appendsBelowPage1_advancesCursor_andReusesTheAnchor() {
        val fake =
            FakeNearbyTimelineFlow(
                outcome = loadedPage1("c1", fakeNearbyPost(id = "p1")),
                loadMorePages = listOf(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(id = "p2")), "c2", null)),
            )
        val viewModel = viewModelWith(fake)

        viewModel.onLoadMore()

        val loaded = viewModel.outcome.value as NearbyTimelineOutcome.Loaded
        assertEquals(listOf("p1", "p2"), loaded.posts.map { it.id }, "page 2 appends below page 1")
        assertEquals("c2", loaded.nextCursor, "the cursor advances to the new page's cursor")
        assertEquals(
            listOf("c1" to testAnchor),
            fake.loadMoreCalls,
            "load-more fetches the retained cursor c1 reusing the page-1 anchor (NOT a re-acquired fix)",
        )
    }

    @Test
    fun onLoadMore_whenEndReached_isNoOp() {
        // First page already end-reached (null cursor) → load-more must not fire.
        val fake = FakeNearbyTimelineFlow(outcome = loadedPage1(null, fakeNearbyPost(id = "p1")))
        val viewModel = viewModelWith(fake)

        viewModel.onLoadMore()

        assertTrue(fake.loadMoreCalls.isEmpty(), "no load-more request when the cursor is null (end-reached)")
    }

    @Test
    fun onLoadMore_failure_raisesErrorFooter_andKeepsLoadedPosts() {
        val fake =
            FakeNearbyTimelineFlow(
                outcome = loadedPage1("c1", fakeNearbyPost(id = "p1")),
                loadMorePages = listOf(NearbyTimelineOutcome.NetworkError),
            )
        val viewModel = viewModelWith(fake)

        viewModel.onLoadMore()

        assertTrue(viewModel.loadMoreError.value, "a failed load-more raises the non-destructive error footer")
        val loaded = viewModel.outcome.value as NearbyTimelineOutcome.Loaded
        assertEquals(listOf("p1"), loaded.posts.map { it.id }, "the loaded posts are retained on load-more failure")
    }

    @Test
    fun reload_clearsTheLoadMoreErrorFooter() {
        val fake =
            FakeNearbyTimelineFlow(
                outcome = loadedPage1("c1", fakeNearbyPost(id = "p1")),
                loadMorePages = listOf(NearbyTimelineOutcome.NetworkError),
            )
        val viewModel = viewModelWith(fake)
        viewModel.onLoadMore()
        assertTrue(viewModel.loadMoreError.value)

        viewModel.reload()

        assertFalse(viewModel.loadMoreError.value, "a refresh resets paging — the load-more footer state clears")
    }

    @Test
    fun onLoadMore_isSuppressedWhileARefreshIsInFlight() {
        // suspendFromCall = 2 → the reload's loadFirstPage suspends, so the refresh stays in flight.
        val fake =
            FakeNearbyTimelineFlow(
                outcome = loadedPage1("c1", fakeNearbyPost(id = "p1")),
                suspendFromCall = 2,
                loadMorePages = listOf(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(id = "p2")), "c2", null)),
            )
        val viewModel = viewModelWith(fake)

        viewModel.reload()
        assertTrue(viewModel.isRefreshing.value, "the reload is in flight")
        viewModel.onLoadMore()

        assertTrue(fake.loadMoreCalls.isEmpty(), "load-more is suppressed while a refresh is in flight (canLoadMore gate)")
    }

    // ---- mobile-nearby-radius-slider ----

    private fun premiumProfile(isPremium: Boolean = true) =
        FakeProfileFlow(profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(isPremium = isPremium)))

    @Test
    fun init_loadFirstPage_carriesThe20kmDefaultRadius() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        viewModelWith(fake)
        assertEquals(listOf(20_000), fake.loadFirstPageRadii, "the initial load uses the 20 km default radius")
    }

    @Test
    fun selectRadius_premiumViewer_adoptsRadius_andFetchesViaChangeRadius() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        assertEquals(true, viewModel.isPremiumKnown.value, "the Premium self-read resolves the gate")
        viewModel.selectRadius(50_000)
        assertEquals(50_000, viewModel.selectedRadiusM.value, "a Premium selection adopts the new radius")
        assertEquals(listOf(50_000), fake.changeRadiusCalls, "the change fetches via changeRadius at 50 km")
        assertFalse(viewModel.radiusUpsell.value, "no upsell for a permitted Premium selection")
        // A subsequent refresh reuses the selected radius (stable across the load path).
        viewModel.reload()
        assertEquals(listOf(20_000, 50_000), fake.loadFirstPageRadii, "refresh reuses the selected 50 km")
    }

    @Test
    fun selectRadius_freeViewer_snapsBackTo20km_andRaisesUpsell_noFetch() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = viewModelWith(fake)
        assertEquals(false, viewModel.isPremiumKnown.value, "the Free self-read resolves the gate to Free")
        viewModel.selectRadius(50_000)
        assertEquals(20_000, viewModel.selectedRadiusM.value, "a Free non-20km selection snaps back to 20 km")
        assertTrue(viewModel.radiusUpsell.value, "a Free selection raises the upsell one-shot")
        assertTrue(fake.changeRadiusCalls.isEmpty(), "no fetch is issued for a snapped-back Free selection")
        viewModel.onRadiusUpsellShown()
        assertFalse(viewModel.radiusUpsell.value, "the upsell one-shot clears")
    }

    @Test
    fun selectRadius_gracePeriodViewer_isTreatedAsFree_snapsBack() {
        // Decision 6: a premium_billing_retry viewer reads as is_premium=false → client-conservative.
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel =
            viewModelWith(fake, profileFlow = premiumProfile(isPremium = false))
        viewModel.selectRadius(50_000)
        assertEquals(20_000, viewModel.selectedRadiusM.value, "a grace-period (is_premium=false) viewer is snapped back")
        assertTrue(viewModel.radiusUpsell.value, "and shown the upsell, despite the server permitting a wider radius")
    }

    @Test
    fun radiusPremiumOnly403_revertsTo20km_raisesUpsell_andAddsNoNewOutcomeMember() {
        // The stale-tier backstop: a Premium-believed viewer whose changeRadius 403s (radius_premium_only).
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        fake.changeRadiusResult = RadiusChangeResult.PremiumGated
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        viewModel.selectRadius(50_000)
        assertEquals(20_000, viewModel.selectedRadiusM.value, "a radius_premium_only 403 reverts to 20 km")
        assertTrue(viewModel.radiusUpsell.value, "and raises the same upsell as the client snap-back")
        // The 403 is interpreted in the VM; the outcome stays a normal Loaded (the 20 km re-fetch).
        assertTrue(viewModel.outcome.value is NearbyTimelineOutcome.Loaded, "no new NearbyTimelineOutcome member; the 20 km re-fetch lands")
    }

    @Test
    fun loadMore_reusesTheSelectedNonDefaultRadius() {
        // A Premium selection at 50 km whose first page carries a cursor, so a load-more is possible.
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        fake.changeRadiusResult =
            RadiusChangeResult.Loaded(
                NearbyTimelineOutcome.Loaded(
                    posts = listOf(fakeNearbyPost(id = "p1")),
                    nextCursor = "c1",
                    upsell = null,
                    anchor = LatLng(-6.2, 106.8),
                ),
            )
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        viewModel.selectRadius(50_000)
        assertEquals(50_000, viewModel.selectedRadiusM.value, "the Premium 50 km selection is adopted")
        viewModel.onLoadMore()
        assertEquals(listOf(50_000), fake.loadMoreRadii, "load-more reuses the selected 50 km radius, not the 20 km default")
    }

    // Activates the WhileSubscribed(5000) uiState share (on the Unconfined Main) so uiState.value reflects
    // the projected state in these synchronous tests; the collector is abandoned at test end (no runTest).
    private fun NearbyTimelineViewModel.activateUiState() {
        CoroutineScope(Dispatchers.Main).launch { uiState.collect {} }
    }
}

/** Minimal shared commonTest [SelfUserIdProvider] (the `screens.username` fixture is package-private).
 *  Public + default id so the Robolectric `NearbyTimelineScreenTest` and the home/shell/router screen
 *  tests (which render `NearbyTimelineScreen`, now resolving the self-profile read) reuse it. */
class FakeSelfUserId(private val id: String? = "self") : SelfUserIdProvider {
    override suspend fun selfUserId(): String? = id
}
