package id.nearyou.app.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.theme.NearYouTheme
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Render coverage of the shared `ui/components` post card (`mobile-post-card` capability) — the
 * ONE card implementation Nearby + Global consume (audit 05-#11 post-card half). Covers: the
 * identity header (display name + @handle + time-as-TEXT), the structural no-PII guarantee, the
 * liked-state icon variants, the single-tap contract (the whole card is the only click target;
 * the avatar region is NOT a separate target — no profile screen yet, issue #196), the
 * Nearby-vs-Global distance split via the shared `DistanceRenderer`, the empty-meta-row omission,
 * the maximal-length (V2 50/60) single-line ellipsis treatment, and light+dark token rendering.
 *
 * `@Suppress("DEPRECATION")`: the suite keeps the v1 `runComposeUiTest` API the sibling screen
 * tests use — migrating to v2 is the tracked follow-up per docs/11 § 2.7, not drive-by churn.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class PostCardTest {
    private fun model(
        authorUsername: String = "raka.jkt",
        authorDisplayName: String = "Raka Pratama",
        content: String = "Ada yang tau kenapa Jalan Senopati ditutup?",
        cityName: String = "Jakarta Selatan",
        distanceM: Double? = 5400.0,
        createdAt: String = "2026-05-31T10:00:00Z",
        likedByViewer: Boolean = false,
        replyCount: Int = 4,
    ) = PostCardModel(
        id = "p1",
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        cityName = cityName,
        distanceM = distanceM,
        createdAt = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
    )

    @Test
    fun identityHeaderRendersDisplayNameHandleAndTimeAsText() =
        runComposeUiTest {
            setContent { NearYouTheme(darkTheme = false) { PostCard(model = model(), onOpen = {}) } }
            onNodeWithText("Raka Pratama").assertIsDisplayed()
            onNodeWithText("@raka.jkt").assertIsDisplayed()
            // The time label is plain text in the header (the clock glyph is gone — the
            // mobile-design-system delta); relative formatting stays deferred, so the value is
            // the ISO date portion.
            onNodeWithText("2026-05-31").assertIsDisplayed()
        }

    @Test
    fun noAuthorUuidOrRawCoordinateAppearsInTheTree() =
        runComposeUiTest {
            // PostCardModel structurally carries no UUID/coordinates; this pins the rendered tree.
            setContent { NearYouTheme(darkTheme = false) { PostCard(model = model(), onOpen = {}) } }
            onAllNodesWithText("11111111", substring = true).assertCountEquals(0)
            onAllNodesWithText("-6.21", substring = true).assertCountEquals(0)
            onAllNodesWithText("106.85", substring = true).assertCountEquals(0)
        }

    @Test
    fun likedStateSwitchesTheLikeIconVariant() =
        runComposeUiTest {
            setContent {
                NearYouTheme(darkTheme = false) {
                    PostCard(model = model(likedByViewer = true), onOpen = {})
                }
            }
            onNodeWithTag(POST_CARD_LIKE_FILLED_TAG, useUnmergedTree = true).assertExists()
            onAllNodes(hasClickAction()).assertCountEquals(1)
        }

    @Test
    fun unlikedStateRendersTheOutlinedVariant() =
        runComposeUiTest {
            setContent {
                NearYouTheme(darkTheme = false) {
                    PostCard(model = model(likedByViewer = false), onOpen = {})
                }
            }
            onNodeWithTag(POST_CARD_LIKE_OUTLINED_TAG, useUnmergedTree = true).assertExists()
        }

    @Test
    fun theOnlyClickableNodeIsTheCard_andAvatarTapFiresTheWholeCardOpenOnce() =
        runComposeUiTest {
            var opened = 0
            setContent {
                NearYouTheme(darkTheme = false) {
                    PostCard(model = model(), onOpen = { opened++ })
                }
            }
            // Exactly ONE click target in the whole tree — the card itself. The counts row and
            // the identity region expose no click action (no dead controls; inline actions are
            // the deferred #201 change).
            onAllNodes(hasClickAction()).assertCountEquals(1)
            // Tapping over the avatar region dispatches to the card's clickable — same action.
            onNodeWithTag(POST_CARD_AVATAR_TAG, useUnmergedTree = true).performClick()
            assertEquals(1, opened)
        }

    @Test
    fun nearbyVariantRendersSharedRendererDistance_globalVariantRendersNone() =
        runComposeUiTest {
            setContent {
                NearYouTheme(darkTheme = false) {
                    androidx.compose.foundation.layout.Column {
                        PostCard(model = model(distanceM = 1234.5), onOpen = {})
                        PostCard(model = model(content = "kedua", distanceM = null), onOpen = {})
                    }
                }
            }
            // DistanceRenderer.render(1234.5) == "5km" (the ≥5km floor) — asserted at the
            // rendered-card level so the card provably consumes the shared renderer.
            onAllNodesWithText("5km").assertCountEquals(1)
            // The null-distance card renders the pin + city but NO distance string — exactly one
            // "5km" node exists in the two-card tree, and both cities render.
            onAllNodesWithText("Jakarta Selatan").assertCountEquals(2)
        }

    @Test
    fun emptyCityAndNullDistanceHideTheLocationMetaRow() =
        runComposeUiTest {
            setContent {
                NearYouTheme(darkTheme = false) {
                    PostCard(model = model(cityName = "", distanceM = null), onOpen = {})
                }
            }
            onNodeWithTag(POST_CARD_PIN_TAG, useUnmergedTree = true).assertDoesNotExist()
            onAllNodesWithText("\"\"").assertCountEquals(0)
        }

    @Test
    fun maximalLengthIdentityStaysSingleLineAndTimeRemainsVisible() =
        runComposeUiTest {
            // V2 maxima: display_name VARCHAR(50), username VARCHAR(60).
            val longName = "N".repeat(50)
            val longHandle = "u".repeat(60)
            setContent {
                NearYouTheme(darkTheme = false) {
                    PostCard(
                        model = model(authorDisplayName = longName, authorUsername = longHandle),
                        onOpen = {},
                    )
                }
            }
            onNodeWithText(longName).assertIsDisplayed()
            // The handle ellipsizes (weight(fill = false)) instead of pushing the time label out.
            onNodeWithText("2026-05-31").assertIsDisplayed()
        }

    @Test
    fun rendersUnderTheDarkScheme() =
        runComposeUiTest {
            setContent { NearYouTheme(darkTheme = true) { PostCard(model = model(), onOpen = {}) } }
            onNodeWithText("Raka Pratama").assertIsDisplayed()
            onNodeWithText("@raka.jkt").assertIsDisplayed()
        }
}
