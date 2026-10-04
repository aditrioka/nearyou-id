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
import id.nearyou.app.timeline.fakeNearbyPost
import id.nearyou.distance.LatLng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        premiumConfirmed: StateFlow<Boolean> = MutableStateFlow(false),
    ) = NearbyTimelineViewModel(
        flow,
        likeFlow,
        profileFlow,
        FakeSelfUserId("self"),
        FakeReportSubmitter(),
        FakeBlockSubmitter(),
        premiumConfirmed,
    )

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
    fun selectRadius_premiumViewer_adoptsRadius_andReloadsPage1AtIt() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        assertEquals(true, viewModel.isPremiumKnown.value, "the Premium self-read resolves the gate")
        viewModel.selectRadius(50_000)
        assertEquals(50_000, viewModel.selectedRadiusM.value, "a Premium selection adopts the new radius")
        assertEquals(listOf(20_000, 50_000), fake.loadFirstPageRadii, "the change is a page-1 load at 50 km")
        assertFalse(viewModel.radiusUpsell.value, "no upsell for a permitted Premium selection")
        // A subsequent refresh reuses the selected radius (stable across the load path).
        viewModel.reload()
        assertEquals(listOf(20_000, 50_000, 50_000), fake.loadFirstPageRadii, "refresh reuses the selected 50 km")
    }

    @Test
    fun selectRadius_freeViewer_snapsBackTo20km_andRaisesUpsell_noFetch() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = viewModelWith(fake)
        assertEquals(false, viewModel.isPremiumKnown.value, "the Free self-read resolves the gate to Free")
        viewModel.selectRadius(50_000)
        assertEquals(20_000, viewModel.selectedRadiusM.value, "a Free non-20km selection snaps back to 20 km")
        assertTrue(viewModel.radiusUpsell.value, "a Free selection raises the upsell one-shot")
        assertEquals(listOf(20_000), fake.loadFirstPageRadii, "no fetch is issued for a snapped-back Free selection")
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
        // The stale-tier backstop: a Premium-believed viewer whose radius selection 403s (radius_premium_only).
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        fake.gatedRadii = setOf(50_000)
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        viewModel.selectRadius(50_000)
        assertEquals(20_000, viewModel.selectedRadiusM.value, "a radius_premium_only 403 reverts to 20 km")
        assertTrue(viewModel.radiusUpsell.value, "and raises the same upsell as the client snap-back")
        assertEquals(listOf(20_000, 50_000, 20_000), fake.loadFirstPageRadii, "the gated 50 km is re-fetched at 20 km")
        // The 403 is interpreted in the VM; the outcome stays a normal Loaded (the 20 km re-fetch).
        assertTrue(viewModel.outcome.value is NearbyTimelineOutcome.Loaded, "no new NearbyTimelineOutcome member; the 20 km re-fetch lands")
    }

    @Test
    fun loadMore_reusesTheSelectedNonDefaultRadius() {
        // A Premium selection at 50 km whose first page carries a cursor, so a load-more is possible.
        val fake = FakeNearbyTimelineFlow(loadedPage1("c1", fakeNearbyPost(id = "p1")))
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        viewModel.selectRadius(50_000)
        assertEquals(50_000, viewModel.selectedRadiusM.value, "the Premium 50 km selection is adopted")
        viewModel.onLoadMore()
        assertEquals(listOf(50_000), fake.loadMoreRadii, "load-more reuses the selected 50 km radius, not the 20 km default")
    }

    // ---- premium-entitlement-lifecycle: re-evaluate after a confirmed purchase ----

    @Test
    fun purchaseConfirmedWhileAlive_unlocksThePremiumRadii() {
        // A Free viewer (the HomeRoute VM survives the paywall push) buys → returns → selects 50 km.
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val confirmed = MutableStateFlow(false)
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile(isPremium = false), premiumConfirmed = confirmed)
        assertEquals(false, viewModel.isPremiumKnown.value, "the Free self-read gates first")

        confirmed.value = true
        viewModel.selectRadius(50_000)

        assertEquals(true, viewModel.isPremiumKnown.value, "the confirmed purchase flips the gate without re-entry")
        assertEquals(50_000, viewModel.selectedRadiusM.value, "the Premium radius is applied")
        assertEquals(listOf(20_000, 50_000), fake.loadFirstPageRadii, "a page-1 fetch at 50 km is issued")
        assertFalse(viewModel.radiusUpsell.value, "no upsell for a confirmed buyer")
    }

    @Test
    fun laggingFreeSelfRead_doesNotOverrideAConfirmedPurchase() {
        // The webhook has not landed: the self read still says Free, but the purchase is already confirmed.
        // The read is gated so it lands AFTER the confirmation collector (the real network ordering).
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val gate = CompletableDeferred<Unit>()
        val profile = premiumProfile(isPremium = false).apply { loadGate = gate }
        val viewModel = viewModelWith(fake, profileFlow = profile, premiumConfirmed = MutableStateFlow(true))
        assertEquals(true, viewModel.isPremiumKnown.value, "the confirmed purchase resolves first")

        gate.complete(Unit) // the lagging Free read lands

        assertEquals(true, viewModel.isPremiumKnown.value, "a lagging Free read must not re-lock the buyer")
    }

    // ---- #518: the radius_premium_only backstop on EVERY Nearby fetch path ----

    @Test
    fun errorRetryAt50km_radiusPremiumOnly403_showsTheUpsell_notTheNetworkError() {
        // The #518 repro: the self read degraded optimistically to Premium-known, the 50 km selection's fetch
        // failed before any HTTP call (no location fix → NetworkError), and "Coba lagi" re-issues 50 km → 403.
        val fake = FakeNearbyTimelineFlow(loadedPage1(null, fakeNearbyPost(id = "p1")))
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        fake.firstPageOutcome = NearbyTimelineOutcome.NetworkError
        viewModel.selectRadius(50_000)
        assertEquals(NearbyTimelineOutcome.NetworkError, viewModel.outcome.value, "the 50 km fetch failed pre-HTTP")
        assertEquals(50_000, viewModel.selectedRadiusM.value, "the control is still on 50 km")

        fake.gatedRadii = setOf(50_000)
        fake.firstPageOutcome = loadedPage1(null, fakeNearbyPost(id = "p1"))
        viewModel.reload() // the error-state retry ("Coba lagi")

        assertTrue(viewModel.radiusUpsell.value, "the retry's 403 raises the radius upsell")
        assertEquals(20_000, viewModel.selectedRadiusM.value, "the control reverts to 20 km")
        assertEquals(listOf(20_000, 50_000, 50_000, 20_000), fake.loadFirstPageRadii, "the gated retry re-fetches at 20 km")
        assertTrue(viewModel.outcome.value is NearbyTimelineOutcome.Loaded, "the 403 is NOT rendered as the connectivity error")
    }

    @Test
    fun pullToRefreshAt50km_radiusPremiumOnly403_revertsTo20km_andRaisesUpsell() {
        val fake = FakeNearbyTimelineFlow(loadedPage1(null, fakeNearbyPost(id = "p1")))
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        viewModel.selectRadius(50_000)
        assertFalse(viewModel.radiusUpsell.value)

        fake.gatedRadii = setOf(50_000) // the tier went stale mid-session
        viewModel.reload() // pull-to-refresh

        assertTrue(viewModel.radiusUpsell.value, "the refresh's 403 raises the radius upsell")
        assertEquals(20_000, viewModel.selectedRadiusM.value, "the control reverts to 20 km")
        assertEquals(listOf(20_000, 50_000, 50_000, 20_000), fake.loadFirstPageRadii, "page 1 is re-fetched at 20 km")
        assertTrue(viewModel.outcome.value is NearbyTimelineOutcome.Loaded)
        assertFalse(viewModel.isRefreshing.value, "the refresh completes")
    }

    @Test
    fun gatedLoadMore_dropsTheStalePage_revertsTo20km_andReloadsPage1_withNoErrorFooter() {
        val fake = FakeNearbyTimelineFlow(loadedPage1("c1", fakeNearbyPost(id = "p1")))
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        viewModel.selectRadius(50_000)

        fake.gatedRadii = setOf(50_000)
        fake.firstPageOutcome = loadedPage1(null, fakeNearbyPost(id = "p20")) // the distinct 20 km page
        viewModel.onLoadMore()

        assertEquals(listOf(50_000), fake.loadMoreRadii, "the load-more went out at 50 km and was gated")
        assertTrue(viewModel.radiusUpsell.value, "a gated load-more raises the radius upsell")
        assertEquals(20_000, viewModel.selectedRadiusM.value, "the control reverts to 20 km")
        assertEquals(listOf(20_000, 50_000, 20_000), fake.loadFirstPageRadii, "page 1 is reloaded at 20 km")
        assertFalse(viewModel.loadMoreError.value, "the stale gated page is dropped — no retry footer")
        assertEquals(
            listOf("p20"),
            (viewModel.outcome.value as NearbyTimelineOutcome.Loaded).posts.map { it.id },
            "nothing is appended — the list is the fresh 20 km page 1",
        )
        assertFalse(viewModel.isLoadingMore.value, "no load-more spinner is left behind")
        assertFalse(viewModel.isRefreshing.value, "the 20 km reload completes")
    }

    @Test
    fun aStaleGatedLoadMore_afterTheRefreshAlreadyReverted_appliesNothingTwice() {
        // F1: a 50 km load-more is in flight when a pull-to-refresh hits the gate (→ 20 km + upsell, dismissed);
        // the stale load-more's 403 then lands. It must not re-raise the upsell or spend another 20 km read.
        val fake = FakeNearbyTimelineFlow(loadedPage1("c1", fakeNearbyPost(id = "p1")))
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        viewModel.selectRadius(50_000)
        val loadMoreGate = CompletableDeferred<Unit>()
        fake.loadMoreGate = loadMoreGate
        viewModel.onLoadMore() // held in flight at 50 km

        fake.gatedRadii = setOf(50_000)
        viewModel.reload() // the refresh's 403 → revert + upsell + one 20 km re-fetch
        assertEquals(20_000, viewModel.selectedRadiusM.value)
        viewModel.onRadiusUpsellShown()
        val radiiBefore = fake.loadFirstPageRadii.toList()

        loadMoreGate.complete(Unit) // the stale 50 km load-more's 403 lands

        assertFalse(viewModel.radiusUpsell.value, "a stale gated load-more must not re-raise the upsell")
        assertEquals(radiiBefore, fake.loadFirstPageRadii, "and must not spend another page-1 read")
        assertFalse(viewModel.loadMoreError.value, "its stale Failure is dropped — no retry footer")
    }

    @Test
    fun aGated20kmLoadMore_isThePlainRetryFooter_withNoUpsell_andNoReload() {
        // D4 on the load-more path: a gated 20 km page is a server fault, not a Premium gate.
        val fake = FakeNearbyTimelineFlow(loadedPage1("c1", fakeNearbyPost(id = "p1")))
        val viewModel = viewModelWith(fake)
        fake.gatedRadii = setOf(20_000)

        viewModel.onLoadMore()

        assertEquals(listOf(20_000), fake.loadMoreRadii, "the load-more went out at 20 km")
        assertTrue(viewModel.loadMoreError.value, "→ the ordinary non-destructive retry footer")
        assertFalse(viewModel.radiusUpsell.value, "no Premium upsell for a server fault at the Free anchor")
        assertEquals(listOf(20_000), fake.loadFirstPageRadii, "no page-1 reload")
        assertEquals(listOf("p1"), (viewModel.outcome.value as NearbyTimelineOutcome.Loaded).posts.map { it.id })
    }

    @Test
    fun aGated20kmRefetch_mapsToTheRetryableError_withoutLooping() {
        // A server fault: even the Free 20 km anchor answers radius_premium_only — exactly ONE re-fetch, no loop.
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        fake.gatedRadii = setOf(50_000, 20_000)
        viewModel.selectRadius(50_000)

        assertEquals(listOf(20_000, 50_000, 20_000), fake.loadFirstPageRadii, "one 20 km re-fetch only")
        assertEquals(NearbyTimelineOutcome.NetworkError, viewModel.outcome.value, "→ the retryable error")
        assertEquals(20_000, viewModel.selectedRadiusM.value)
    }

    @Test
    fun aGated20kmRefresh_isTheRetryableError_withNoUpsell_andNoRefetch() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = viewModelWith(fake, profileFlow = premiumProfile())
        fake.gatedRadii = setOf(20_000)

        viewModel.reload() // a pull-to-refresh at the 20 km default

        assertEquals(listOf(20_000, 20_000), fake.loadFirstPageRadii, "no re-fetch after a gated 20 km fetch")
        assertEquals(NearbyTimelineOutcome.NetworkError, viewModel.outcome.value, "→ the retryable error")
        assertFalse(viewModel.radiusUpsell.value, "a server fault at the Free anchor raises no Premium upsell")
    }

    // ---- #173: the HomeRoute feed reload key (a successful post re-fetches page 1) ----

    @Test
    fun feedReloadKey_firstObservationAndRepeats_doNotFetch_aChangeReloadsOnce() {
        val fake = FakeNearbyTimelineFlow(loadedPage1(null, fakeNearbyPost(id = "p1")))
        val viewModel = viewModelWith(fake)
        assertEquals(1, fake.loadInvocationCount)

        // A NON-zero first key (a VM created after posts were made / restored after process death): it is
        // only recorded — a zero-initialised "last key" would wrongly re-fetch here.
        viewModel.onFeedReloadKey(3)
        viewModel.onFeedReloadKey(3)
        assertEquals(1, fake.loadInvocationCount, "the first key is only recorded; a repeat is a no-op")

        viewModel.onFeedReloadKey(4)
        assertEquals(2, fake.loadInvocationCount, "a changed key re-fetches page 1 once")
        viewModel.onFeedReloadKey(4)
        assertEquals(2, fake.loadInvocationCount, "re-observing the same key does not fetch again")
    }

    @Test
    fun feedReloadKey_changeDuringAnInFlightRefresh_reFetchesOnceMoreWhenItLands() {
        // F3: a post made while a (slow) refresh is in flight — that refresh predates the post, so when it lands
        // the VM re-fetches once more instead of silently dropping the key change.
        val fake = FakeNearbyTimelineFlow(loadedPage1(null, fakeNearbyPost(id = "p1")))
        val viewModel = viewModelWith(fake)
        viewModel.onFeedReloadKey(3)
        val gate = CompletableDeferred<Unit>()
        fake.firstPageGate = gate
        viewModel.reload() // pull-to-refresh, held in flight
        assertEquals(2, fake.loadInvocationCount)

        viewModel.onFeedReloadKey(4) // the post lands while the refresh is in flight → suppressed for now
        assertEquals(2, fake.loadInvocationCount, "one fetch at a time")

        gate.complete(Unit)
        assertEquals(3, fake.loadInvocationCount, "the stale refresh landed → one follow-up page-1 fetch")
        assertFalse(viewModel.isRefreshing.value)
    }

    @Test
    fun feedReloadKey_change_keepsThePriorOutcome_whileTheRefreshIsInFlight() {
        // suspendFromCall = 2 → the key-driven reload suspends, so the in-flight refresh is observable.
        val fake = FakeNearbyTimelineFlow(loadedPage1(null, fakeNearbyPost(id = "p1")), suspendFromCall = 2)
        val viewModel = viewModelWith(fake)
        viewModel.onFeedReloadKey(0)

        viewModel.onFeedReloadKey(1)

        assertTrue(viewModel.isRefreshing.value, "the key-driven reload is a refresh (isRefreshing), not a re-skeleton")
        assertTrue(viewModel.outcome.value is NearbyTimelineOutcome.Loaded, "the prior list stays mounted")
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
