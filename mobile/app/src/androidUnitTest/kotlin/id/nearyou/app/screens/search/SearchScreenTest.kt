package id.nearyou.app.screens.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.billing.PremiumEntitlementSession
import id.nearyou.app.billing.confirmedPremiumSession
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.block.FakeBlockSubmitter
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.post.FakePostDetailFlow
import id.nearyou.app.post.FakePostEditFlow
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.PostTargetResolution
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.screens.home.PostDetailTarget
import id.nearyou.app.screens.home.toPostDetailTarget
import id.nearyou.app.screens.paywall.FakePurchaseController
import id.nearyou.app.screens.routing.PaywallEntry
import id.nearyou.app.screens.routing.PaywallRoute
import id.nearyou.app.screens.routing.PostDetailRoute
import id.nearyou.app.screens.routing.SearchRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.screens.username.FakeSelfUserIdProvider
import id.nearyou.app.screens.username.selfProfile
import id.nearyou.app.search.FakeSearchFlow
import id.nearyou.app.search.SearchFlow
import id.nearyou.app.search.SearchOutcome
import id.nearyou.app.search.fakeSearchHit
import id.nearyou.app.theme.NearYouTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val IDLE_PROMPT = "Cari postingan atau pengguna."
private const val LOADING = "Sedang memuat postingan…" // timeline_loading
private const val EMPTY_RESULTS = "Tidak ada hasil untuk 'jakarta'. Coba kata kunci lain."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val RETRY = "Coba lagi"
private const val GATE_BODY =
    "Pencarian hanya untuk pengguna Premium. Aktifkan Premium untuk mencari postingan dan pengguna di seluruh Indonesia."
private const val GATE_CTA = "Aktifkan Premium"
private const val ACTIVATING = "Pembelianmu berhasil dan Premium sedang diaktifkan. Coba lagi sebentar lagi ya." // premium_activating_body
private const val RATE_LIMITED_29 = "Kamu sudah mencapai batas pencarian. Reset dalam 29 menit."
private const val RATE_LIMITED_1 = "Kamu sudah mencapai batas pencarian. Reset dalam 1 menit."
private const val RATE_LIMIT_RESET = "Batas pencarian sudah direset. Coba cari lagi."
private const val DISABLED = "Pencarian sedang tidak tersedia. Coba lagi nanti."
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…"
private const val LOAD_MORE = "Lihat lebih banyak"

/**
 * Render + behavior coverage of `SearchScreen` via the Robolectric-backed CMP UI runner. Drives each
 * visual state through a `FakeSearchFlow` (the outcome→state projection itself is covered purely by
 * `SearchUiStateTest`): the Idle prompt, Loading, Results + the result-tap `onOpenPost` payload, the
 * query-formatted EmptyResults, Error + retry, the PremiumGate panel (CTA performs no navigation), the
 * RateLimited countdown copy, Disabled (NOT the connectivity copy), the SessionRedirect (no retry/error),
 * the "Lihat lebih banyak" append, and clear → Idle.
 *
 * The fetch is driven by the keyboard **submit** action ([submitQuery]) rather than the 500 ms debounce:
 * the debounce's `delay` runs on `viewModelScope` (the Main dispatcher), which the compose `waitForIdle`
 * does not advance — submit fires `runSearch` with no delay, mirroring how the timeline VMs' init load
 * completes eagerly under `Dispatchers.Main.immediate`. The RateLimited test pins the compose clock so the
 * `RateLimitedState` per-minute tick loop never advances.
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `GlobalTimelineScreenTest` for the multi-test startKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class SearchScreenTest {
    private lateinit var fake: FakeSearchFlow

    private fun installKoin(
        firstOutcome: SearchOutcome = SearchOutcome.Results(emptyList(), null),
        loadMoreOutcome: SearchOutcome? = null,
        suspendForever: Boolean = false,
        // #517: bind a session whose purchase is confirmed (the webhook-lag window).
        confirmedPurchase: Boolean = false,
        // #253: the on-entry self read — Premium by default (the pre-#253 behavior the state tests assume).
        selfPremium: Boolean = true,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeSearchFlow(firstOutcome = firstOutcome, loadMoreOutcome = loadMoreOutcome, suspendForever = suspendForever)
        startKoin {
            modules(
                module {
                    single<SearchFlow> { fake }
                    single<ProfileFlow> { FakeProfileFlow(selfProfile(isPremium = selfPremium)) }
                    single<SelfUserIdProvider> { FakeSelfUserIdProvider("self-id") }
                    // A result tap under the host pushes PostDetailRoute, whose screen injects these seams.
                    single<PostDetailFlow> { FakePostDetailFlow() }
                    single<PostEditFlow> { FakePostEditFlow() }
                    single<ReportSubmitter> { FakeReportSubmitter() }
                    single<BlockSubmitter> { FakeBlockSubmitter() }
                    // The host-push test navigates onward to PaywallRoute, whose screen injects a
                    // PurchaseController (Unavailable → the fail-soft Unconfigured state; the test asserts the
                    // route push, not paywall content). Unused by the screen-level tests, which never push it.
                    single<PurchaseController> { FakePurchaseController(OfferingsResult.Unavailable) }
                    if (confirmedPurchase) single<PremiumEntitlementSession> { confirmedPremiumSession() }
                },
            )
        }
    }

    /** Type a query then trigger the ime Search action (onSubmit → no-delay fetch), then settle. */
    private fun ComposeUiTest.submitQuery(query: String = "jakarta") {
        onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(query)
        onNodeWithTag(SEARCH_FIELD_TAG).performImeAction()
        waitForIdle()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun idle_showsThePrompt_noFetch() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            onNodeWithText(IDLE_PROMPT).assertExists()
            // #253: a Premium self read opens to the prompt, never the on-entry upsell.
            onNodeWithText(GATE_BODY).assertDoesNotExist()
            assertEquals(0, fake.invocationCount, "an empty query issues no fetch")
        }
    }

    // #253: a known-Free viewer sees the upsell the moment Cari opens — before typing, with no search issued.
    @Test
    fun onEntry_freeRead_showsTheUpsellBeforeTyping_noFetch() {
        installKoin(selfPremium = false)
        runComposeUiTest {
            var activated = 0
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}, onActivatePremium = { activated++ }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(GATE_BODY).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(GATE_CTA).assertExists()
            onNodeWithText(IDLE_PROMPT).assertDoesNotExist()
            assertEquals(0, fake.invocationCount, "the on-entry gate issues no search")
            onNodeWithTag(SEARCH_PREMIUM_CTA_TAG).performClick()
            waitForIdle()
            assertEquals(1, activated, "the on-entry CTA opens the paywall like the 403 gate")
        }
    }

    @Test
    fun loading_showsTheSkeletonCopy() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText(LOADING).assertExists()
        }
    }

    @Test
    fun results_showHitsAndCard() {
        installKoin(SearchOutcome.Results(listOf(fakeSearchHit(content = "HALO_CARI")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText("HALO_CARI").assertExists()
            assertEquals(true, onAllNodesWithTag(SEARCH_RESULT_CARD_TAG).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun emptyResults_showsTheQueryFormattedCopy() {
        installKoin(SearchOutcome.Results(emptyList(), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText(EMPTY_RESULTS).assertExists()
        }
    }

    @Test
    fun error_showsConnectivityCopyAndRetry() {
        installKoin(SearchOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText(ERROR_NETWORK).assertExists()
            onNodeWithTag(SEARCH_RETRY_TAG).assertExists()
        }
    }

    @Test
    fun premiumGate_showsPanelAndCta_ctaInvokesOnActivatePremium() {
        installKoin(SearchOutcome.PremiumGate)
        runComposeUiTest {
            var activated = 0
            var opened = 0
            setContent {
                KoinContext {
                    NearYouTheme {
                        SearchScreen(onBack = {}, onOpenPost = { opened++ }, onActivatePremium = { activated++ })
                    }
                }
            }
            submitQuery()
            onNodeWithText(GATE_BODY).assertExists()
            onNodeWithText(GATE_CTA).assertExists()
            onNodeWithTag(SEARCH_PREMIUM_CTA_TAG).performClick()
            waitForIdle()
            // mobile-paywall-screen (#254): the CTA invokes the hoisted onActivatePremium (the host pushes
            // PaywallRoute(SEARCH_GATE)); the screen itself stays navigation-free (no onOpenPost / route).
            assertEquals(1, activated, "the Premium-gate CTA invokes the hoisted onActivatePremium exactly once")
            assertEquals(0, opened, "the gate CTA is not a result tap")
            onNodeWithText(GATE_BODY).assertExists()
        }
    }

    // #517: after a confirmed purchase a 403 can only be the server tier lagging the webhook — the gate
    // panel reads the activating notice, and its button re-runs the same query instead of opening the paywall.
    @Test
    fun confirmedPurchase_premiumGate_showsActivatingNotice_andRetryReRunsTheQuery() {
        installKoin(SearchOutcome.PremiumGate, confirmedPurchase = true)
        var activated = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}, onActivatePremium = { activated++ }) } } }
            submitQuery()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(ACTIVATING).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(GATE_BODY).assertDoesNotExist()
            onNodeWithTag(SEARCH_PREMIUM_CTA_TAG).assertDoesNotExist()
            onNodeWithText(GATE_CTA).assertDoesNotExist()
            val before = fake.invocationCount
            onNodeWithTag(SEARCH_RETRY_TAG).assertDoesNotExist() // the gate's retry, not the error state's
            onNodeWithTag(SEARCH_GATE_RETRY_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { fake.invocationCount == before + 1 }
            assertEquals("jakarta", fake.calls.last().query, "the retry re-runs the gated query")
            assertEquals(0, activated, "the activating notice never opens the paywall")
        }
    }

    // mobile-paywall-screen (#254) — the host-push half of the MODIFIED search-gate scenario: under the
    // REAL appEntryProvider (TestNavHost started at SearchRoute), the Premium-gate CTA pushes
    // PaywallRoute(entry = SEARCH_GATE) onto the root stack (NOT LIKE_CAP — the entry-context distinguishes
    // the two gated surfaces). The screen-level callback firing is covered by the test above.
    @Test
    fun premiumGateCta_underHost_pushesPaywallRouteSearchGateOntoRootStack() {
        installKoin(SearchOutcome.PremiumGate)
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(SearchRoute, onBackStack = { backStack = it }) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(SEARCH_FIELD_TAG).fetchSemanticsNodes().isNotEmpty() }
            submitQuery()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(SEARCH_PREMIUM_CTA_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(SEARCH_PREMIUM_CTA_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { backStack.last() is PaywallRoute }
            val top = backStack.last()
            assertTrue(top is PaywallRoute, "the search Premium-gate CTA pushes PaywallRoute (was: ${backStack.toList()})")
            assertEquals(PaywallEntry.SEARCH_GATE, top.entry, "the search-gate entry-context is SEARCH_GATE")
        }
    }

    @Test
    fun rateLimited_showsTheCountdownCopy() {
        installKoin(SearchOutcome.RateLimited(retryAfterSeconds = 1740))
        runComposeUiTest {
            // Pin the clock so the RateLimitedState per-minute tick loop never advances (a retry would
            // re-trigger RateLimited indefinitely). The fetch itself runs on viewModelScope (Main), not
            // the compose clock, so submit still completes.
            mainClock.autoAdvance = false
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("jakarta")
            onNodeWithTag(SEARCH_FIELD_TAG).performImeAction()
            // autoAdvance is off, so manually pump one frame to apply the RateLimited recomposition
            // (the per-minute tick's delay(60_000) stays pending — never advanced — so no retry loop).
            mainClock.advanceTimeByFrame()
            onNodeWithText(RATE_LIMITED_29).assertExists()
        }
    }

    @Test
    fun rateLimited_atZero_showsRetryControl_thatReIssuesTheQuery() {
        installKoin(SearchOutcome.RateLimited(retryAfterSeconds = 60)) // capCountdownMinutes(60) = 1 minute
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("jakarta")
            onNodeWithTag(SEARCH_FIELD_TAG).performImeAction()
            mainClock.advanceTimeByFrame()
            onNodeWithText(RATE_LIMITED_1).assertExists()
            val before = fake.invocationCount
            // Advance the one floored minute → the countdown reaches zero → the retry control appears.
            mainClock.advanceTimeBy(60_000)
            mainClock.advanceTimeByFrame()
            onNodeWithText(RATE_LIMIT_RESET).assertExists()
            onNodeWithTag(SEARCH_RETRY_TAG).assertExists()
            // Tapping it re-issues the query (a deliberate user action, not an auto-fetch).
            onNodeWithTag(SEARCH_RETRY_TAG).performClick()
            mainClock.advanceTimeByFrame()
            assertEquals(before + 1, fake.invocationCount, "the retry control re-issues the query")
        }
    }

    @Test
    fun resultCard_rendersNoAuthorIdOrRank_andNoEngagementRow() {
        // fakeSearchHit seeds the PII fields (authorId UUID + rank) that must NOT reach the rendered tree.
        installKoin(SearchOutcome.Results(listOf(fakeSearchHit(content = "HALO_CARI")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText("HALO_CARI").assertExists()
            // Display identity IS rendered…
            onNodeWithText("Dewi Lestari", substring = true).assertExists()
            // …but the author UUID and the FTS rank score are NEVER rendered (PII / internal-ranking).
            onNodeWithText("11111111-1111-1111-1111-111111111111", substring = true).assertDoesNotExist()
            onNodeWithText("0.83", substring = true).assertDoesNotExist()
            // The search card carries no like/reply action row and no city/distance (the wire has none).
            onNodeWithTag("postCardLikeAction").assertDoesNotExist()
            onNodeWithTag("postCardReplyAction").assertDoesNotExist()
        }
    }

    @Test
    fun disabled_showsKillSwitchCopy_notConnectivity() {
        installKoin(SearchOutcome.Disabled)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText(DISABLED).assertExists()
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
        }
    }

    @Test
    fun sessionExpired_showsRedirect_noRetry_notConnectivity() {
        installKoin(SearchOutcome.SessionExpired)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText(SESSION_REDIRECT).assertExists()
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
            onNodeWithText(RETRY).assertDoesNotExist()
        }
    }

    @Test
    fun loadMore_appendsTheNextPage() {
        installKoin(
            firstOutcome = SearchOutcome.Results(listOf(fakeSearchHit(postId = "p1", content = "PAGE_ONE")), nextOffset = 20),
            loadMoreOutcome = SearchOutcome.Results(listOf(fakeSearchHit(postId = "p2", content = "PAGE_TWO")), nextOffset = null),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText("PAGE_ONE").assertExists()
            onNodeWithTag(SEARCH_LOAD_MORE_TAG).assertExists()
            onNodeWithTag(SEARCH_LOAD_MORE_TAG).performClick()
            waitForIdle()
            onNodeWithText("PAGE_TWO").assertExists()
            onNodeWithText(LOAD_MORE).assertDoesNotExist() // nextOffset = null → load-more hidden
        }
    }

    // #255: an unavailable by-id read (the fake's default) opens the detail from the hit + documented defaults.
    @Test
    fun resultTap_unavailableRead_opensWithTheHitAndDocumentedDefaults() {
        installKoin(
            SearchOutcome.Results(
                listOf(
                    fakeSearchHit(
                        postId = "p1",
                        authorUsername = "dewi.kuliner",
                        authorDisplayName = "Dewi Lestari",
                        content = "HALO_CARI",
                        createdAt = "2026-05-31T10:00:00Z",
                    ),
                ),
                null,
            ),
        )
        runComposeUiTest {
            var tapped: PostDetailTarget? = null
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}, onOpenPost = { tapped = it }) } } }
            submitQuery()
            onNodeWithTag(SEARCH_RESULT_CARD_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { tapped != null }
            assertEquals(
                PostDetailTarget(
                    postId = "p1",
                    content = "HALO_CARI",
                    cityName = "",
                    distanceM = null,
                    createdAtIso = "2026-05-31T10:00:00Z",
                    likedByViewer = false,
                    replyCount = 0,
                    authorUsername = "dewi.kuliner",
                    authorDisplayName = "Dewi Lestari",
                    imageUrl = null,
                ),
                tapped,
            )
        }
    }

    // #255: a resolved by-id read hydrates the target — delivered exactly once, even across recomposition.
    @Test
    fun resultTap_resolvedRead_opensTheHydratedTarget_exactlyOnce() {
        installKoin(SearchOutcome.Results(listOf(fakeSearchHit(postId = "p1", content = "HALO_CARI")), null))
        fake.resolutions["p1"] = hydrated("p1")
        runComposeUiTest {
            val opened = mutableListOf<PostDetailTarget>()
            var tick by mutableStateOf(0)
            setContent {
                KoinContext {
                    NearYouTheme {
                        // Reading `tick` here lets the test force a recomposition of the screen's caller.
                        if (tick >= 0) SearchScreen(onBack = {}, onOpenPost = { opened += it })
                    }
                }
            }
            submitQuery()
            onNodeWithTag(SEARCH_RESULT_CARD_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { opened.isNotEmpty() }
            tick++
            waitForIdle()
            assertEquals(listOf(hydrated("p1").toPostDetailTarget()), opened, "one hydrated delivery, no re-fire")
        }
    }

    @Test
    fun resultTap_inFlightRead_showsTheCardSpinner() {
        installKoin(SearchOutcome.Results(listOf(fakeSearchHit(postId = "p1", content = "HALO_CARI")), null))
        fake.resolveGates["p1"] = CompletableDeferred()
        runComposeUiTest {
            var opened = 0
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}, onOpenPost = { opened++ }) } } }
            submitQuery()
            onNodeWithTag(SEARCH_RESULT_RESOLVING_TAG).assertDoesNotExist()
            onNodeWithTag(SEARCH_RESULT_CARD_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(SEARCH_RESULT_RESOLVING_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, opened, "no navigation while the by-id read is in flight")
        }
    }

    // #255 host half: under the REAL appEntryProvider the hydrated target becomes the pushed PostDetailRoute.
    @Test
    fun resultTap_underHost_pushesTheHydratedPostDetailRoute() {
        installKoin(SearchOutcome.Results(listOf(fakeSearchHit(postId = "p1", content = "HALO_CARI")), null))
        fake.resolutions["p1"] = hydrated("p1")
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(SearchRoute, onBackStack = { backStack = it }) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(SEARCH_FIELD_TAG).fetchSemanticsNodes().isNotEmpty() }
            submitQuery()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(SEARCH_RESULT_CARD_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(SEARCH_RESULT_CARD_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { backStack.last() is PostDetailRoute }
            assertEquals(
                PostDetailRoute(
                    postId = "p1",
                    content = "HALO_CARI",
                    cityName = "Jakarta Selatan",
                    distanceM = null,
                    createdAtIso = "2026-10-03T09:00:00Z",
                    likedByViewer = true,
                    replyCount = 4,
                    authorUsername = "dewi.kuliner",
                    authorDisplayName = "Dewi Lestari",
                    imageUrl = "https://img.example/p1.jpg",
                ),
                backStack.last(),
            )
            assertEquals(1, backStack.count { it is PostDetailRoute }, "exactly one detail pushed")
        }
    }

    // The username-autocomplete deferral (#252): typing renders only result cards — no typeahead, no extra fetch.
    @Test
    fun typedQuery_rendersNoTypeahead_andOnlySearches() {
        installKoin(SearchOutcome.Results(listOf(fakeSearchHit(postId = "p1"), fakeSearchHit(postId = "p2")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery("kopi")
            assertEquals(2, onAllNodesWithTag(SEARCH_RESULT_CARD_TAG).fetchSemanticsNodes().size, "only the hits render")
            assertTrue(fake.calls.all { it.query == "kopi" }, "every fetch is the search itself: ${fake.calls}")
            assertTrue(fake.resolvedIds.isEmpty(), "no other read is issued while typing")
        }
    }

    @Test
    fun clear_emptiesTheField_andReturnsToIdle() {
        installKoin(SearchOutcome.Results(listOf(fakeSearchHit(content = "HALO_CARI")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText("HALO_CARI").assertExists()
            onNodeWithTag(SEARCH_CLEAR_TAG).performClick()
            waitForIdle()
            onNodeWithText(IDLE_PROMPT).assertExists()
            onNodeWithText("HALO_CARI").assertDoesNotExist()
        }
    }

    private fun hydrated(postId: String) =
        PostTargetResolution.Resolved(
            postId = postId,
            authorUsername = "dewi.kuliner",
            authorDisplayName = "Dewi Lestari",
            content = "HALO_CARI",
            cityName = "Jakarta Selatan",
            createdAtIso = "2026-10-03T09:00:00Z",
            likedByViewer = true,
            replyCount = 4,
            imageUrl = "https://img.example/$postId.jpg",
        )
}
