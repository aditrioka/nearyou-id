package id.nearyou.app.screens.post

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.post.FakePostEditFlow
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.PostEditOutcome
import id.nearyou.app.screens.routing.EditPostRoute
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

/**
 * Render + interaction coverage of `EditPostScreen` (task 7.3), driven by `FakePostEditFlow`: the editor
 * is prefilled with the current content + the Save action gates on non-empty content; a success invokes
 * `onPostEdited` with the updated content (the caller pops); a `403` raises the "Aktifkan Premium" upsell
 * (the reactive gate, NOT an error); a non-premium failure renders the inline error banner. The
 * outcome→UiState projection is covered purely by `EditPostViewModelTest`. In the Release-variant exclude.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class EditPostScreenTest {
    private fun installKoin(editFlow: PostEditFlow) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module { single { editFlow } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private fun route(initialContent: String = "isi awal") = EditPostRoute(postId = "p1", initialContent = initialContent)

    @Test
    fun editor_isPrefilled_andSaveEnabledForNonEmptyContent() {
        installKoin(FakePostEditFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { EditPostScreen(route = route(), onBack = {}, onPostEdited = {}) } } }
            onNodeWithTag(EDIT_POST_FIELD_TAG).assertTextEquals("isi awal")
            onNodeWithTag(EDIT_POST_SAVE_TAG).assertIsEnabled()
        }
    }

    @Test
    fun emptyInitialContent_disablesSave() {
        installKoin(FakePostEditFlow())
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        EditPostScreen(
                            route = route(initialContent = ""),
                            onBack = {},
                            onPostEdited = {},
                        )
                    }
                }
            }
            onNodeWithTag(EDIT_POST_SAVE_TAG).assertIsNotEnabled()
        }
    }

    @Test
    fun submit_success_invokesOnPostEditedWithUpdatedContent() {
        var edited: String? = null
        installKoin(FakePostEditFlow(editOutcome = PostEditOutcome.Success("isi baru")))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { EditPostScreen(route = route(), onBack = {}, onPostEdited = { edited = it }) } } }
            onNodeWithTag(EDIT_POST_SAVE_TAG).performClick()
            waitUntil(timeoutMillis = 2_000) { edited != null }
            assertEquals("isi baru", edited)
        }
    }

    @Test
    fun submit_premiumRequired_showsActivatePremiumUpsell() {
        installKoin(FakePostEditFlow(editOutcome = PostEditOutcome.PremiumRequired))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { EditPostScreen(route = route(), onBack = {}, onPostEdited = {}) } } }
            onNodeWithTag(EDIT_POST_SAVE_TAG).performClick()
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(EDIT_POST_PREMIUM_CTA_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(EDIT_POST_PREMIUM_CTA_TAG).assertExists()
        }
    }

    @Test
    fun submit_windowExpired_showsInlineError() {
        installKoin(FakePostEditFlow(editOutcome = PostEditOutcome.WindowExpired))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { EditPostScreen(route = route(), onBack = {}, onPostEdited = {}) } } }
            onNodeWithTag(EDIT_POST_SAVE_TAG).performClick()
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(EDIT_POST_ERROR_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(EDIT_POST_ERROR_TAG).assertExists()
        }
    }
}
