package id.nearyou.app.screens.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.infra.supabaserealtime.EmbeddedPostSnapshot
import id.nearyou.app.theme.NearYouTheme
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Render coverage of [EmbeddedPostCard] via the Robolectric CMP runner (tasks 8.2 + 8.4): a live card
 * shows the snapshot's author/content/city (NO coordinate) and is tappable; a hard-deleted-source card
 * shows the "Postingan telah dihapus" state and is NOT tappable. The pure projection/banner logic is
 * covered by `EmbeddedPostProjectionTest`.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class EmbeddedPostCardTest {
    private val snapshot =
        EmbeddedPostSnapshot(
            authorUsername = "raka",
            authorDisplayName = "Raka Pratama",
            content = "halo dunia ini postingan",
            cityName = "Jakarta",
            createdAt = "2026-06-01T10:00:00Z",
            editedAt = null,
        )

    @Test
    fun liveCardShowsSnapshotFieldsAndNavigatesOnTap() =
        runComposeUiTest {
            var tapped = false
            setContent {
                NearYouTheme {
                    EmbeddedPostCard(
                        snapshot = snapshot,
                        isDeleted = false,
                        editedSinceShared = false,
                        onTap = { tapped = true },
                    )
                }
            }
            onNodeWithText("Raka Pratama").assertExists()
            onNodeWithText("@raka").assertExists()
            onNodeWithText("halo dunia ini postingan").assertExists()
            onNodeWithText("Jakarta").assertExists()
            // Tapping a live card navigates (onTap fires).
            onNodeWithTag(EMBEDDED_POST_CARD_TAG).performClick()
            assertTrue(tapped, "tapping a live context card invokes onTap")
        }

    @Test
    fun editedBannerShownWhenEditedSinceShared() =
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    EmbeddedPostCard(snapshot = snapshot, isDeleted = false, editedSinceShared = true, onTap = {})
                }
            }
            onNodeWithTag(EMBEDDED_POST_EDITED_BANNER_TAG, useUnmergedTree = true).assertExists()
            onNodeWithText("Diedit sejak dibagikan").assertExists()
        }

    @Test
    fun deletedCardShowsDeletedStateAndIsNotTappable() =
        runComposeUiTest {
            var tapped = false
            setContent {
                NearYouTheme {
                    EmbeddedPostCard(
                        snapshot = snapshot,
                        isDeleted = true,
                        editedSinceShared = false,
                        onTap = { tapped = true },
                    )
                }
            }
            onNodeWithTag(EMBEDDED_POST_DELETED_TAG).assertExists()
            onNodeWithText("Postingan telah dihapus").assertExists()
            // The post content is NOT shown in the deleted state.
            onNodeWithText("halo dunia ini postingan").assertDoesNotExist()
            // Tapping a deleted card does NOT navigate.
            onNodeWithTag(EMBEDDED_POST_CARD_TAG).performClick()
            assertFalse(tapped, "a deleted-source card is not tappable")
        }
}
