package id.nearyou.app.screens.search

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.search.FakeSearchFlow
import id.nearyou.app.search.SearchFlow
import id.nearyou.app.search.SearchOutcome
import id.nearyou.app.search.fakeSearchHit
import id.nearyou.app.theme.NearYouTheme
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

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val IDLE_PROMPT = "Cari postingan atau pengguna."
private const val LOADING = "Sedang memuat postingan…" // timeline_loading
private const val EMPTY_RESULTS = "Tidak ada hasil untuk 'jakarta'. Coba kata kunci lain."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val RETRY = "Coba lagi"
private const val GATE_BODY =
    "Pencarian hanya untuk pengguna Premium. Aktifkan Premium untuk mencari postingan dan pengguna di seluruh Indonesia."
private const val GATE_CTA = "Aktifkan Premium"
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
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeSearchFlow(firstOutcome = firstOutcome, loadMoreOutcome = loadMoreOutcome, suspendForever = suspendForever)
        startKoin { modules(module { single<SearchFlow> { fake } }) }
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
            assertEquals(0, fake.invocationCount, "an empty query issues no fetch")
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
    fun premiumGate_showsPanelAndCta_ctaPerformsNoNavigation() {
        installKoin(SearchOutcome.PremiumGate)
        runComposeUiTest {
            var opened = 0
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}, onOpenPost = { opened++ }) } } }
            submitQuery()
            onNodeWithText(GATE_BODY).assertExists()
            onNodeWithText(GATE_CTA).assertExists()
            onNodeWithTag(SEARCH_PREMIUM_CTA_TAG).performClick()
            waitForIdle()
            // The v1 CTA is a placeholder: it pushes no route (no onOpenPost) and the gate stays shown.
            assertEquals(0, opened, "the v1 Premium CTA performs no navigation")
            onNodeWithText(GATE_BODY).assertExists()
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

    @Test
    fun resultTap_invokesOnOpenPostWithTheFullHitPayload() {
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
            var tapped: SearchHit? = null
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}, onOpenPost = { tapped = it }) } } }
            submitQuery()
            onNodeWithTag(SEARCH_RESULT_CARD_TAG).performClick()
            waitForIdle()
            // The hit carries the non-PII display fields the appEntryProvider call site turns into a
            // PostDetailRoute (with documented defaults for the wire-absent city/distance/like/reply).
            assertEquals("p1", tapped?.postId)
            assertEquals("dewi.kuliner", tapped?.authorUsername)
            assertEquals("Dewi Lestari", tapped?.authorDisplayName)
            assertEquals("HALO_CARI", tapped?.content)
            assertEquals("2026-05-31T10:00:00Z", tapped?.createdAt)
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
}
