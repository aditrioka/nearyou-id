package id.nearyou.app.screens.search

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.screens.startFlowTestKoin
import id.nearyou.app.screens.stopFlowTestKoin
import id.nearyou.app.search.FakeSearchFlow
import id.nearyou.app.search.SearchFlow
import id.nearyou.app.search.SearchOutcome
import id.nearyou.app.search.fakeSearchHit
import id.nearyou.app.theme.NearYouTheme
import org.koin.compose.KoinContext
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val IDLE_PROMPT = "Cari postingan atau pengguna."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…"
private const val RETRY = "Coba lagi"

/**
 * iOS counterpart to the Robolectric [SearchScreenTest] — the Cari surface run natively on the iOS
 * simulator (`:mobile:app:iosSimulatorArm64Test`), proving the new `SearchRoute` + data seam compile +
 * run on Kotlin/Native (the universal per-screen `*FlowIosTest` convention). Focused on the states this
 * change adds: the Idle prompt, a content render, and the terminal-401 redirect placeholder (NOT the
 * connectivity copy). Reuses the commonTest [FakeSearchFlow]; the fetch is driven by the keyboard submit
 * ([submitQuery]), not the 500 ms debounce.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class SearchFlowIosTest {
    private lateinit var fake: FakeSearchFlow

    private fun installKoin(firstOutcome: SearchOutcome) {
        fake = FakeSearchFlow(firstOutcome = firstOutcome)
        startFlowTestKoin(module { single<SearchFlow> { fake } })
    }

    // Submit (ime action) fires the fetch with no delay — the 500 ms debounce's `delay` runs on
    // `viewModelScope`, which the compose clock does not advance (parity with SearchScreenTest).
    private fun ComposeUiTest.submitQuery(query: String = "jakarta") {
        onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(query)
        onNodeWithTag(SEARCH_FIELD_TAG).performImeAction()
        waitForIdle()
    }

    @AfterTest
    fun tearDown() = stopFlowTestKoin()

    @Test
    fun idle_showsPromptBeforeTyping() {
        installKoin(SearchOutcome.Results(emptyList(), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            onNodeWithText(IDLE_PROMPT).assertExists()
        }
    }

    @Test
    fun content_showsHit() {
        installKoin(SearchOutcome.Results(listOf(fakeSearchHit(content = "HALO_IOS")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText("HALO_IOS").assertExists()
        }
    }

    @Test
    fun sessionExpired_showsRedirect_noRetry_notNetworkCopy() {
        installKoin(SearchOutcome.SessionExpired)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { SearchScreen(onBack = {}) } } }
            submitQuery()
            onNodeWithText(SESSION_REDIRECT).assertExists()
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
            onNodeWithText(RETRY).assertDoesNotExist()
        }
    }
}
