package id.nearyou.app.screens.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.infra.supabaserealtime.EmbeddedPostSnapshot
import id.nearyou.app.theme.NearYouTheme
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
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
    // Two pieces of shared-JVM hygiene so this composable renders reliably inside the full
    // dev-release CI run (1000+ tests), not just in isolation:
    //  1. Register the host ComponentActivity that `runComposeUiTest` launches via ActivityScenario.
    //     A prior test in the shared Robolectric sandbox can leave the package manager without it →
    //     "Unable to resolve activity for ... ComponentActivity" (Robolectric#4736). addActivityIfNotPresent
    //     is order-independent and a no-op when it is already registered.
    //  2. Start a clean (empty) Koin context + wrap renders in KoinContext, matching the CI-stable
    //     Compose-test harness (ChatThreadScreenTest). EmbeddedPostCard injects nothing — this is
    //     purely environment parity.
    @BeforeTest
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app.packageManager)
            .addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module {}) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

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
                KoinContext {
                    NearYouTheme {
                        EmbeddedPostCard(
                            snapshot = snapshot,
                            isDeleted = false,
                            editedSinceShared = false,
                            onTap = { tapped = true },
                        )
                    }
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
                KoinContext {
                    NearYouTheme {
                        EmbeddedPostCard(snapshot = snapshot, isDeleted = false, editedSinceShared = true, onTap = {})
                    }
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
                KoinContext {
                    NearYouTheme {
                        EmbeddedPostCard(
                            snapshot = snapshot,
                            isDeleted = true,
                            editedSinceShared = false,
                            onTap = { tapped = true },
                        )
                    }
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
