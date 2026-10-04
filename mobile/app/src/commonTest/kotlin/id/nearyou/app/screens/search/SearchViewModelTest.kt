package id.nearyou.app.screens.search

import id.nearyou.app.post.PostTargetResolution
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.screens.home.PostDetailTarget
import id.nearyou.app.screens.username.FakeSelfUserIdProvider
import id.nearyou.app.screens.username.selfProfile
import id.nearyou.app.search.FakeSearchFlow
import id.nearyou.app.search.SearchFlow
import id.nearyou.app.search.SearchOutcome
import id.nearyou.app.search.SearchResultDto
import id.nearyou.app.search.fakeSearchHit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage of [SearchViewModel]: submit issues `search(query, 0)`; a below-2 query issues nothing
 * and stays Idle; the 500 ms debounce fires after the window; a load-more issues `search(query,
 * nextOffset)` and APPENDS; an empty load-more page is terminal; each outcome is surfaced.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] over an explicit
 * [TestCoroutineScheduler] is installed as Main so non-delayed launches run eagerly and the debounce's
 * `delay(500)` can be advanced deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    // uiState is a WhileSubscribed stateIn: every VM built here gets a live collector so `uiState.value` tracks.
    private val collectors = mutableListOf<Job>()

    @AfterTest
    fun resetMainDispatcher() {
        collectors.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    /** A collected [SearchViewModel] over [flow]; the self read defaults to a Premium viewer (the pre-#253
     *  behavior every existing test assumes). */
    private fun searchVm(
        flow: SearchFlow,
        confirmed: StateFlow<Boolean> = MutableStateFlow(false),
        profile: FakeProfileFlow = FakeProfileFlow(selfProfile(isPremium = true)),
        selfId: String? = "self-id",
    ): SearchViewModel =
        SearchViewModel(flow, profile, FakeSelfUserIdProvider(selfId), confirmed).also { vm ->
            collectors += CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch { vm.uiState.collect {} }
        }

    private fun freeProfile() = FakeProfileFlow(selfProfile(isPremium = false))

    private fun SearchViewModel.surface(): SearchUiState = uiState.value.surface

    @Test
    fun submit_issuesSearchAtOffsetZero() {
        val fake = FakeSearchFlow(SearchOutcome.Results(listOf(fakeSearchHit()), 20))
        val vm = searchVm(fake)
        vm.onQueryChange("jakarta")
        vm.onSubmit()
        scheduler.advanceUntilIdle()

        assertTrue(fake.calls.any { it == FakeSearchFlow.Call("jakarta", 0) }, "submit issues search(query, 0): ${fake.calls}")
        assertTrue(vm.outcome is SearchOutcome.Results)
    }

    // ---- premium-entitlement-lifecycle: a confirmed purchase re-runs the gated query once ----

    @Test
    fun purchaseConfirmedWhileGated_reRunsTheQueryOnce() {
        val calls = mutableListOf<String>()
        val flow =
            object : SearchFlow {
                override suspend fun search(
                    query: String,
                    offset: Int,
                ): SearchOutcome {
                    calls += query
                    // The first call is the 403 gate; after the purchase the server lets the query through.
                    return if (calls.size == 1) SearchOutcome.PremiumGate else SearchOutcome.Results(listOf(fakeSearchHit()), null)
                }

                override suspend fun resolvePostTarget(postId: String): PostTargetResolution = PostTargetResolution.Unavailable
            }
        val confirmed = MutableStateFlow(false)
        val vm = searchVm(flow, confirmed)
        vm.onQueryChange("kopi")
        vm.onSubmit()
        scheduler.advanceUntilIdle()
        assertEquals(SearchOutcome.PremiumGate, vm.outcome)

        confirmed.value = true
        scheduler.advanceUntilIdle()

        assertEquals(listOf("kopi", "kopi"), calls, "exactly one re-query after the purchase")
        assertTrue(vm.outcome is SearchOutcome.Results)
    }

    @Test
    fun purchaseConfirmedOutsideTheGate_issuesNoRequest() {
        val fake = FakeSearchFlow(SearchOutcome.Results(listOf(fakeSearchHit()), null))
        val confirmed = MutableStateFlow(false)
        val vm = searchVm(fake, confirmed)
        vm.onQueryChange("kopi")
        vm.onSubmit()
        scheduler.advanceUntilIdle()
        val before = fake.invocationCount

        confirmed.value = true
        scheduler.advanceUntilIdle()

        assertEquals(before, fake.invocationCount, "no re-query when the outcome is not the Premium gate")
    }

    @Test
    fun belowTwoCharQuery_issuesNothing_andClearsOutcome() {
        val fake = FakeSearchFlow()
        val vm = searchVm(fake)
        vm.onQueryChange("a")
        scheduler.advanceUntilIdle()

        assertEquals(0, fake.invocationCount, "a below-2 query issues no request")
        assertEquals(null, vm.outcome, "outcome stays null (Idle)")
        assertEquals(SearchUiState.Idle, vm.surface())
        assertEquals("a", vm.uiState.value.query)
    }

    @Test
    fun debounce_firesAfterTheWindow_notBefore() {
        val fake = FakeSearchFlow(SearchOutcome.Results(emptyList(), null))
        val vm = searchVm(fake)
        vm.onQueryChange("jakarta")

        // The launched debounce enters and suspends at delay(500) — no fetch yet.
        scheduler.runCurrent()
        assertEquals(0, fake.invocationCount, "no fetch before the debounce window elapses")

        scheduler.advanceTimeBy(500)
        scheduler.runCurrent()
        assertEquals(1, fake.invocationCount, "the debounced fetch fires once after 500 ms")
        assertEquals(FakeSearchFlow.Call("jakarta", 0), fake.calls.last())
    }

    @Test
    fun rapidTyping_debounces_toASingleFetch() {
        val fake = FakeSearchFlow(SearchOutcome.Results(emptyList(), null))
        val vm = searchVm(fake)
        vm.onQueryChange("ja")
        scheduler.advanceTimeBy(200)
        vm.onQueryChange("jak")
        scheduler.advanceTimeBy(200)
        vm.onQueryChange("jakarta")
        scheduler.advanceUntilIdle()

        assertEquals(1, fake.invocationCount, "intermediate keystrokes are debounced away: ${fake.calls}")
        assertEquals(FakeSearchFlow.Call("jakarta", 0), fake.calls.single())
    }

    @Test
    fun loadMore_issuesNextOffset_andAppends() {
        val fake =
            FakeSearchFlow(
                firstOutcome = SearchOutcome.Results(listOf(fakeSearchHit(postId = "p1")), nextOffset = 20),
                loadMoreOutcome = SearchOutcome.Results(listOf(fakeSearchHit(postId = "p2")), nextOffset = null),
            )
        val vm = searchVm(fake)
        vm.onQueryChange("jakarta")
        vm.onSubmit()
        scheduler.advanceUntilIdle()

        vm.loadMore()
        scheduler.advanceUntilIdle()

        assertTrue(fake.calls.any { it == FakeSearchFlow.Call("jakarta", 20) }, "load-more uses the retained offset: ${fake.calls}")
        val outcome = vm.outcome
        assertTrue(outcome is SearchOutcome.Results)
        assertEquals(listOf("p1", "p2"), outcome.hits.map { it.postId }, "the next page is APPENDED, not replaced")
        assertEquals(null, outcome.nextOffset, "nextOffset advances to the new page's (null → terminal)")
    }

    @Test
    fun loadMore_emptyPage_isTerminal_keepsExistingHits() {
        val fake =
            FakeSearchFlow(
                firstOutcome = SearchOutcome.Results(listOf(fakeSearchHit(postId = "p1")), nextOffset = 20),
                loadMoreOutcome = SearchOutcome.Results(emptyList(), nextOffset = 40),
            )
        val vm = searchVm(fake)
        vm.onQueryChange("jakarta")
        vm.onSubmit()
        scheduler.advanceUntilIdle()
        vm.loadMore()
        scheduler.advanceUntilIdle()

        val outcome = vm.outcome
        assertTrue(outcome is SearchOutcome.Results)
        assertEquals(listOf("p1"), outcome.hits.map { it.postId }, "an empty page keeps the existing hits")
        assertEquals(null, outcome.nextOffset, "an empty page is terminal even if nextOffset != null")
    }

    @Test
    fun newQuery_cancelsTheInFlightFetch_soStaleResultsDoNotWin() {
        // A gated flow: the FIRST fetch (the older query) blocks on a gate and returns a distinguishable
        // PremiumGate; the SECOND fetch (the newer query) returns Results immediately. Without the
        // searchJob cancellation, the released stale fetch-1 would overwrite fetch-2's Results.
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val flow =
            object : SearchFlow {
                override suspend fun search(
                    query: String,
                    offset: Int,
                ): SearchOutcome {
                    calls += 1
                    return if (calls == 1) {
                        gate.await()
                        SearchOutcome.PremiumGate
                    } else {
                        SearchOutcome.Results(listOf(fakeSearchHit()), null)
                    }
                }

                override suspend fun resolvePostTarget(postId: String): PostTargetResolution = PostTargetResolution.Unavailable
            }
        val vm = searchVm(flow)

        vm.onQueryChange("aa")
        scheduler.advanceUntilIdle() // fetch-1 starts and blocks on the gate
        vm.onQueryChange("bb")
        scheduler.advanceUntilIdle() // cancels fetch-1, fetch-2 returns Results
        gate.complete(Unit) // release the (already-cancelled) fetch-1
        scheduler.advanceUntilIdle()

        assertTrue(
            vm.outcome is SearchOutcome.Results,
            "the newer query's result wins; the cancelled stale fetch must NOT overwrite it (was ${vm.outcome})",
        )
    }

    @Test
    fun eachOutcome_isSurfaced() {
        fun outcomeFor(outcome: SearchOutcome): SearchOutcome? {
            val vm = searchVm(FakeSearchFlow(outcome))
            vm.onQueryChange("jakarta")
            vm.onSubmit()
            scheduler.advanceUntilIdle()
            return vm.outcome
        }
        assertEquals(SearchOutcome.PremiumGate, outcomeFor(SearchOutcome.PremiumGate))
        assertEquals(SearchOutcome.RateLimited(1740L), outcomeFor(SearchOutcome.RateLimited(1740L)))
        assertEquals(SearchOutcome.Disabled, outcomeFor(SearchOutcome.Disabled))
        assertEquals(SearchOutcome.SessionExpired, outcomeFor(SearchOutcome.SessionExpired))
    }

    // ---- #253: the on-entry Premium gate ----

    @Test
    fun freeRead_onEntry_showsTheGate_withNoSearch() {
        val fake = FakeSearchFlow()
        val profile = freeProfile()
        val vm = searchVm(fake, profile = profile)
        scheduler.advanceUntilIdle()

        assertEquals(1, profile.loadCalls, "the self profile is read once on entry")
        assertEquals(SearchUiState.PremiumGate, vm.surface(), "a known-Free viewer sees the upsell before typing")
        assertEquals(0, fake.invocationCount, "the on-entry gate issues no search")
    }

    @Test
    fun premiumRead_onEntry_isIdle() {
        val profile = FakeProfileFlow(selfProfile(isPremium = true))
        val vm = searchVm(FakeSearchFlow(), profile = profile)
        scheduler.advanceUntilIdle()

        assertEquals(1, profile.loadCalls)
        assertEquals(SearchUiState.Idle, vm.surface())
    }

    @Test
    fun pendingRead_isIdle_untilAFreeReadLands() {
        val profile = freeProfile().apply { loadGate = CompletableDeferred() }
        val vm = searchVm(FakeSearchFlow(), profile = profile)
        scheduler.advanceUntilIdle()
        assertEquals(1, profile.loadCalls, "the read is in flight")
        assertEquals(SearchUiState.Idle, vm.surface(), "the resolving window is the optimistic Idle prompt")

        // Positive control: the same VM gates once the Free read lands.
        profile.loadGate?.complete(Unit)
        scheduler.advanceUntilIdle()
        assertEquals(SearchUiState.PremiumGate, vm.surface())
    }

    @Test
    fun failedRead_degradesToIdle() {
        val profile = FakeProfileFlow(ProfileOutcome.NetworkError)
        val vm = searchVm(FakeSearchFlow(), profile = profile)
        scheduler.advanceUntilIdle()

        assertEquals(1, profile.loadCalls, "the read ran and failed")
        assertEquals(SearchUiState.Idle, vm.surface(), "a failed read is never a gate or an error wall")
    }

    @Test
    fun missingSelfId_skipsTheRead_andIsIdle() {
        val profile = freeProfile()
        val vm = searchVm(FakeSearchFlow(), profile = profile, selfId = null)
        scheduler.advanceUntilIdle()

        assertEquals(0, profile.loadCalls, "no self id → no read")
        assertEquals(SearchUiState.Idle, vm.surface())
    }

    @Test
    fun freeViewer_resultsAnswer_provesPremium_soClearingIsIdle() {
        val fake = FakeSearchFlow(SearchOutcome.Results(listOf(fakeSearchHit()), null))
        val vm = searchVm(fake, profile = freeProfile())
        scheduler.advanceUntilIdle()
        assertEquals(SearchUiState.PremiumGate, vm.surface())

        vm.onQueryChange("kopi")
        vm.onSubmit()
        scheduler.advanceUntilIdle()
        assertEquals(FakeSearchFlow.Call("kopi", 0), fake.calls.last(), "the server is still asked")
        assertTrue(vm.surface() is SearchUiState.Results, "the server's answer governs an issued query")

        vm.onQueryChange("")
        assertEquals(SearchUiState.Idle, vm.surface(), "Results proved Premium — clearing is the Idle prompt")
    }

    @Test
    fun freeViewer_rateLimitedAnswer_provesPremium_soClearingIsIdle() {
        val profile = freeProfile()
        val vm = searchVm(FakeSearchFlow(SearchOutcome.RateLimited(60)), profile = profile)
        scheduler.advanceUntilIdle()
        assertEquals(1, profile.loadCalls)
        assertEquals(SearchUiState.PremiumGate, vm.surface(), "positive control: gated before the query")

        vm.onQueryChange("kopi")
        vm.onSubmit()
        scheduler.advanceUntilIdle()

        vm.onQueryChange("")
        assertEquals(SearchUiState.Idle, vm.surface(), "a 429 is a Premium-tier limit")
    }

    @Test
    fun freeViewer_403_keepsTheGate_afterClearing() {
        val vm = searchVm(FakeSearchFlow(SearchOutcome.PremiumGate), profile = freeProfile())
        vm.onQueryChange("kopi")
        vm.onSubmit()
        scheduler.advanceUntilIdle()
        assertEquals(SearchUiState.PremiumGate, vm.surface())

        vm.onQueryChange("")
        assertEquals(SearchUiState.PremiumGate, vm.surface(), "a 403 never clears the on-entry gate")
    }

    @Test
    fun resultsBeforeALateFreeRead_isNeverDowngraded() {
        val profile = freeProfile().apply { loadGate = CompletableDeferred() }
        val vm = searchVm(FakeSearchFlow(SearchOutcome.Results(listOf(fakeSearchHit()), null)), profile = profile)
        vm.onQueryChange("kopi")
        vm.onSubmit()
        scheduler.advanceUntilIdle()

        profile.loadGate?.complete(Unit) // the Free read lands AFTER the server proved Premium
        scheduler.advanceUntilIdle()
        vm.onQueryChange("")
        assertEquals(SearchUiState.Idle, vm.surface(), "known Premium is sticky — a late Free read never re-gates")
    }

    @Test
    fun purchaseConfirmed_whileOnEntryGated_liftsTheGate_withNoRequest() {
        val fake = FakeSearchFlow()
        val confirmed = MutableStateFlow(false)
        val vm = searchVm(fake, confirmed = confirmed, profile = freeProfile())
        scheduler.advanceUntilIdle()
        assertEquals(SearchUiState.PremiumGate, vm.surface())

        confirmed.value = true
        scheduler.advanceUntilIdle()
        assertEquals(SearchUiState.Idle, vm.surface())
        assertEquals(0, fake.invocationCount, "lifting the gate issues no search")
    }

    @Test
    fun purchaseConfirmedBeforeEntry_freeRead_isNeverGated() {
        val vm = searchVm(FakeSearchFlow(), confirmed = MutableStateFlow(true), profile = freeProfile())
        scheduler.advanceUntilIdle()
        assertEquals(SearchUiState.Idle, vm.surface(), "a read lagging the webhook never gates a buyer")
    }

    @Test
    fun purchaseConfirmed_whileTheReadIsInFlight_isNotOverwritten() {
        val profile = freeProfile().apply { loadGate = CompletableDeferred() }
        val confirmed = MutableStateFlow(false)
        val vm = searchVm(FakeSearchFlow(), confirmed = confirmed, profile = profile)
        scheduler.advanceUntilIdle()

        confirmed.value = true
        scheduler.advanceUntilIdle()
        profile.loadGate?.complete(Unit)
        scheduler.advanceUntilIdle()
        assertEquals(SearchUiState.Idle, vm.surface())
    }

    // ---- #255: a result tap hydrates the detail from the by-id read ----

    private val hitP1 = fakeSearchHit(postId = "p1", content = "Kopi enak", createdAt = "2026-10-03T09:00:00Z")

    private fun resolved(postId: String) =
        PostTargetResolution.Resolved(
            postId = postId,
            authorUsername = "dewi.kuliner",
            authorDisplayName = "Dewi Lestari",
            content = "Kopi enak",
            cityName = "Jakarta Selatan",
            createdAtIso = "2026-10-03T09:00:00Z",
            likedByViewer = true,
            replyCount = 4,
            imageUrl = "https://img.example/$postId.jpg",
        )

    /** Submits a query so the fake's programmed results are on screen. */
    private fun SearchViewModel.loaded(): SearchViewModel {
        onQueryChange("kopi")
        onSubmit()
        scheduler.advanceUntilIdle()
        return this
    }

    /** The display hit a rendered card hands to [SearchViewModel.onResultTap]. */
    private fun SearchResultDto.asHit() = SearchHit(postId, authorUsername, authorDisplayName, content, createdAt)

    @Test
    fun tap_resolved_deliversTheHydratedTarget() {
        val fake = FakeSearchFlow(SearchOutcome.Results(listOf(hitP1), null)).apply { resolutions["p1"] = resolved("p1") }
        val vm = searchVm(fake).loaded()

        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()

        assertEquals(listOf("p1"), fake.resolvedIds)
        assertEquals(
            PostDetailTarget(
                postId = "p1",
                content = "Kopi enak",
                cityName = "Jakarta Selatan",
                distanceM = null,
                createdAtIso = "2026-10-03T09:00:00Z",
                likedByViewer = true,
                replyCount = 4,
                authorUsername = "dewi.kuliner",
                authorDisplayName = "Dewi Lestari",
                imageUrl = "https://img.example/p1.jpg",
            ),
            vm.uiState.value.pendingNavTarget,
        )
        assertNull(vm.uiState.value.resolvingPostId, "the spinner clears once the target is ready")
    }

    @Test
    fun tap_unavailable_fallsBackToTheHitWithDocumentedDefaults() {
        val fake = FakeSearchFlow(SearchOutcome.Results(listOf(hitP1), null)) // no resolution → Unavailable
        val vm = searchVm(fake).loaded()

        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()

        assertEquals(
            PostDetailTarget(
                postId = "p1",
                content = "Kopi enak",
                cityName = "",
                distanceM = null,
                createdAtIso = "2026-10-03T09:00:00Z",
                likedByViewer = false,
                replyCount = 0,
                authorUsername = hitP1.authorUsername,
                authorDisplayName = hitP1.authorDisplayName,
                imageUrl = null,
            ),
            vm.uiState.value.pendingNavTarget,
        )
    }

    @Test
    fun tap_thrownRead_fallsBack_withoutCrashing() {
        val fake =
            FakeSearchFlow(SearchOutcome.Results(listOf(hitP1), null)).apply { resolveThrows = IllegalStateException("boom") }
        val vm = searchVm(fake).loaded()

        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()

        val target = vm.uiState.value.pendingNavTarget
        assertEquals("p1", target?.postId)
        assertEquals("", target?.cityName, "a thrown read is treated like Unavailable")
    }

    @Test
    fun tap_inFlight_exposesTheResolvingCard() {
        val fake = FakeSearchFlow(SearchOutcome.Results(listOf(hitP1), null)).apply { resolveGates["p1"] = CompletableDeferred() }
        val vm = searchVm(fake).loaded()

        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()

        assertEquals("p1", vm.uiState.value.resolvingPostId)
        assertNull(vm.uiState.value.pendingNavTarget, "no navigation while the read is in flight")
    }

    @Test
    fun newerTap_supersedesAnInFlightRead_andOnlyItIsDelivered() {
        val hitP2 = fakeSearchHit(postId = "p2")
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val fake =
            FakeSearchFlow(SearchOutcome.Results(listOf(hitP1, hitP2), null)).apply {
                resolutions["p1"] = resolved("p1")
                resolutions["p2"] = resolved("p2")
                resolveGates["p1"] = gate1
                resolveGates["p2"] = gate2
            }
        val vm = searchVm(fake).loaded()
        val delivered = mutableListOf<String>()
        collectors +=
            CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
                vm.uiState.collect { state -> state.pendingNavTarget?.let { delivered += it.postId } }
            }

        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()
        vm.onResultTap(hitP2.asHit())
        scheduler.advanceUntilIdle()
        gate1.complete(Unit) // the superseded read is released FIRST
        scheduler.advanceUntilIdle()
        gate2.complete(Unit)
        scheduler.advanceUntilIdle()

        assertEquals(listOf("p2"), delivered, "the cancelled p1 read is discarded; only the latest tap navigates")
    }

    @Test
    fun queryChange_cancelsAnInFlightRead() {
        val gate = CompletableDeferred<Unit>()
        val fake =
            FakeSearchFlow(SearchOutcome.Results(listOf(hitP1), null)).apply {
                resolutions["p1"] = resolved("p1")
                resolveGates["p1"] = gate
            }
        val vm = searchVm(fake).loaded()
        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()

        vm.onQueryChange("") // the viewer clears the field mid-read
        assertNull(vm.uiState.value.resolvingPostId, "the card spinner clears")
        gate.complete(Unit)
        scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingNavTarget, "a post no longer on screen is never opened")
    }

    @Test
    fun tapWhileATargetIsPending_isIgnored() {
        val hitP2 = fakeSearchHit(postId = "p2")
        val fake = FakeSearchFlow(SearchOutcome.Results(listOf(hitP1, hitP2), null)).apply { resolutions["p1"] = resolved("p1") }
        val vm = searchVm(fake).loaded()
        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()

        vm.onResultTap(hitP2.asHit())
        scheduler.advanceUntilIdle()
        assertEquals(listOf("p1"), fake.resolvedIds, "the pending p1 is about to navigate — p2 is not read")
        assertEquals("p1", vm.uiState.value.pendingNavTarget?.postId)
    }

    @Test
    fun onNavConsumed_clearsTheTarget_andCancelsAnInFlightRead() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeSearchFlow(SearchOutcome.Results(listOf(hitP1), null)).apply { resolutions["p1"] = resolved("p1") }
        val vm = searchVm(fake).loaded()
        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()
        assertEquals("p1", vm.uiState.value.pendingNavTarget?.postId)

        vm.onNavConsumed()
        assertNull(vm.uiState.value.pendingNavTarget, "the one-shot target is cleared")

        // A read still in flight when the screen is left (e.g. a tap mid-transition) is cancelled too.
        fake.resolveGates["p1"] = gate
        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()
        vm.onNavConsumed()
        gate.complete(Unit)
        scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingNavTarget, "a cancelled read never re-delivers a target")
        assertNull(vm.uiState.value.resolvingPostId)
    }

    @Test
    fun doubleTapOnTheResolvingCard_doesNotRestartTheRead() {
        val fake = FakeSearchFlow(SearchOutcome.Results(listOf(hitP1), null)).apply { resolveGates["p1"] = CompletableDeferred() }
        val vm = searchVm(fake).loaded()

        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()
        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()

        assertEquals(listOf("p1"), fake.resolvedIds, "the in-flight read for the same card is kept, not restarted")
        assertEquals("p1", vm.uiState.value.resolvingPostId)
    }

    @Test
    fun loadMore_dropsAHitThePreviousPageAlreadyHolds() {
        // OFFSET paging over rank ties can repeat a hit when the result set shifts between pages; the list keys
        // on postId, so a duplicate would crash the LazyColumn.
        val fake =
            FakeSearchFlow(
                firstOutcome = SearchOutcome.Results(listOf(fakeSearchHit(postId = "p1"), fakeSearchHit(postId = "p2")), nextOffset = 2),
                loadMoreOutcome =
                    SearchOutcome.Results(
                        listOf(fakeSearchHit(postId = "p2"), fakeSearchHit(postId = "p3")),
                        nextOffset = null,
                    ),
            )
        val vm = searchVm(fake).loaded()

        vm.loadMore()
        scheduler.advanceUntilIdle()

        val outcome = vm.outcome
        assertTrue(outcome is SearchOutcome.Results)
        assertEquals(listOf("p1", "p2", "p3"), outcome.hits.map { it.postId }, "the repeated p2 is not appended twice")
    }

    @Test
    fun queryEdit_alsoCancelsAnInFlightRead() {
        val gate = CompletableDeferred<Unit>()
        val fake =
            FakeSearchFlow(SearchOutcome.Results(listOf(hitP1), null)).apply {
                resolutions["p1"] = resolved("p1")
                resolveGates["p1"] = gate
            }
        val vm = searchVm(fake).loaded()
        vm.onResultTap(hitP1.asHit())
        scheduler.advanceUntilIdle()
        assertEquals("p1", vm.uiState.value.resolvingPostId)

        vm.onQueryChange("kopi tubruk") // an eligible edit starts a new search
        assertNull(vm.uiState.value.resolvingPostId, "the card spinner clears on a new search")
        gate.complete(Unit)
        scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingNavTarget, "the superseded results' post is never opened")
    }
}
