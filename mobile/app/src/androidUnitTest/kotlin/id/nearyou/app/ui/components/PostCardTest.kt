package id.nearyou.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
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
 * liked-state icon variants + the accessible toggled state, the interactive ACTION ROW contract
 * (`mobile-inline-post-actions` + `mobile-profile`: four click targets — the card + the identity
 * header (→ author profile) + the like + the reply affordances; the affordances + identity route to
 * their own callbacks WITHOUT firing the whole-card open; no send affordance, no numeric like count),
 * the Nearby-vs-Global distance split via the shared
 * `DistanceRenderer`, the empty-meta-row omission, the maximal-length (V2 50/60) single-line
 * ellipsis treatment, and light+dark token rendering.
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
        imageUrl: String? = null,
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
        imageUrl = imageUrl,
    )

    /** Composes the card under [NearYouTheme] with recording-or-no-op callbacks. */
    @Composable
    private fun Card(
        model: PostCardModel,
        onOpen: () -> Unit = {},
        onToggleLike: () -> Unit = {},
        onReplyShortcut: () -> Unit = {},
        onOpenProfile: () -> Unit = {},
        darkTheme: Boolean = false,
    ) {
        NearYouTheme(darkTheme = darkTheme) {
            PostCard(
                model = model,
                onOpen = onOpen,
                onToggleLike = onToggleLike,
                onReplyShortcut = onReplyShortcut,
                onOpenProfile = onOpenProfile,
            )
        }
    }

    @Test
    fun identityHeaderRendersDisplayNameHandleAndTimeAsText() =
        runComposeUiTest {
            setContent { Card(model = model()) }
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
            setContent { Card(model = model()) }
            onAllNodesWithText("11111111", substring = true).assertCountEquals(0)
            onAllNodesWithText("-6.21", substring = true).assertCountEquals(0)
            onAllNodesWithText("106.85", substring = true).assertCountEquals(0)
        }

    @Test
    fun likedStateSwitchesTheLikeIconVariant() =
        runComposeUiTest {
            setContent { Card(model = model(likedByViewer = true)) }
            onNodeWithTag(POST_CARD_LIKE_FILLED_TAG, useUnmergedTree = true).assertExists()
        }

    @Test
    fun unlikedStateRendersTheOutlinedVariant() =
        runComposeUiTest {
            setContent { Card(model = model(likedByViewer = false)) }
            onNodeWithTag(POST_CARD_LIKE_OUTLINED_TAG, useUnmergedTree = true).assertExists()
        }

    @Test
    fun fourClickTargets_andIdentityTapFiresOnOpenProfileNotTheWholeCardOpen() =
        runComposeUiTest {
            var opened = 0
            var profile = 0
            setContent { Card(model = model(), onOpen = { opened++ }, onOpenProfile = { profile++ }) }
            // FOUR click targets in the whole tree — the card itself, the identity header (→ author
            // profile, mobile-profile), plus the action row's like + reply affordances. There is NO
            // send affordance / extra action slot (the deferred chat hook, issue #238).
            onAllNodes(hasClickAction()).assertCountEquals(4)
            // Tapping the identity header (over the avatar) fires onOpenProfile, NOT the whole-card open.
            onNodeWithTag(POST_CARD_IDENTITY_TAG, useUnmergedTree = true).performClick()
            assertEquals(1, profile, "the identity tap fires onOpenProfile exactly once")
            assertEquals(0, opened, "the identity tap does NOT fire the whole-card onOpen")
        }

    @Test
    fun actionAffordancesRouteToTheirCallbacks_withoutFiringTheWholeCardOpen() =
        runComposeUiTest {
            var opened = 0
            var liked = 0
            var replied = 0
            setContent {
                Card(
                    model = model(),
                    onOpen = { opened++ },
                    onToggleLike = { liked++ },
                    onReplyShortcut = { replied++ },
                )
            }
            onNodeWithTag(POST_CARD_LIKE_ACTION_TAG).performClick()
            assertEquals(1, liked, "the like affordance fires onToggleLike exactly once")
            assertEquals(0, opened, "the like tap does NOT fire the whole-card onOpen")
            onNodeWithTag(POST_CARD_REPLY_ACTION_TAG).performClick()
            assertEquals(1, replied, "the reply affordance fires onReplyShortcut exactly once")
            assertEquals(0, opened, "the reply tap does NOT fire the whole-card onOpen")
            assertEquals(1, liked, "the reply tap does not re-fire the like callback")
        }

    @Test
    fun replyAffordanceShowsTheCount_andNoNumericLikeCountIsRendered() =
        runComposeUiTest {
            setContent { Card(model = model(replyCount = 7)) }
            // "7" appears exactly once in the whole tree — the reply affordance's count. No other
            // numeric count exists: the timeline wire carries NO like count (the known frame-1
            // divergence, spec-declared).
            onAllNodesWithText("7").assertCountEquals(1)
            onNodeWithTag(POST_CARD_REPLY_ACTION_TAG).assertExists()
        }

    @Test
    fun affordancesCarryContentDescriptions_andTheLikeStateIsAnnounced() =
        runComposeUiTest {
            setContent {
                Column {
                    Card(model = model(likedByViewer = true))
                    Card(model = model(content = "kedua", likedByViewer = false))
                }
            }
            // Both affordances are labeled via stringResource (post_card_action_*), and the like
            // affordance announces its toggled state via stateDescription (post_card_like_state_*)
            // — the liked state is never visual-only. The reply description folds in the count
            // (model() default replyCount = 4) so TalkBack announces it, not just "Balas".
            onAllNodes(hasTestTag(POST_CARD_REPLY_ACTION_TAG))[0].assertContentDescriptionEquals("Balas, 4 balasan")
            val likeNodes = onAllNodes(hasTestTag(POST_CARD_LIKE_ACTION_TAG))
            likeNodes[0].assertContentDescriptionEquals("Suka")
            likeNodes[0].assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Disukai"))
            likeNodes[1].assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Belum disukai"))
        }

    @Test
    fun nearbyVariantRendersSharedRendererDistance_globalVariantRendersNone() =
        runComposeUiTest {
            setContent {
                Column {
                    Card(model = model(distanceM = 1234.5))
                    Card(model = model(content = "kedua", distanceM = null))
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
            setContent { Card(model = model(cityName = "", distanceM = null)) }
            onNodeWithTag(POST_CARD_PIN_TAG, useUnmergedTree = true).assertDoesNotExist()
            onAllNodesWithText("\"\"").assertCountEquals(0)
        }

    @Test
    fun maximalLengthIdentityStaysSingleLineAndTimeRemainsVisible() =
        runComposeUiTest {
            // V2 maxima: display_name VARCHAR(50), username VARCHAR(60).
            val longName = "N".repeat(50)
            val longHandle = "u".repeat(60)
            setContent { Card(model = model(authorDisplayName = longName, authorUsername = longHandle)) }
            onNodeWithText(longName).assertIsDisplayed()
            // The handle ellipsizes (weight(fill = false)) instead of pushing the time label out.
            onNodeWithText("2026-05-31").assertIsDisplayed()
        }

    @Test
    fun rendersUnderTheDarkScheme() =
        runComposeUiTest {
            setContent { Card(model = model(), darkTheme = true) }
            onNodeWithText("Raka Pratama").assertIsDisplayed()
            onNodeWithText("@raka.jkt").assertIsDisplayed()
        }

    // image-attached-posts — the attached image renders below the content when imageUrl is non-null and is
    // ABSENT (the card byte-identical to the pre-image baseline) when null (spec § "Card renders the
    // attached image when imageUrl is present, and nothing when absent"). The actual bytes never decode in
    // Robolectric (no network/GPU) — this asserts the AsyncImage NODE is composed/omitted by the imageUrl
    // guard, which is the spec's behavioral contract; real decode is an emulator/device verify.

    @Test
    fun attachedImageRendersWhenImageUrlPresent_withResourceAltText() =
        runComposeUiTest {
            setContent { Card(model = model(imageUrl = "https://img.example/abc/p1/public")) }
            // The image node exists below the content and carries the resource-backed alt text
            // (post_image_alt) as a non-empty contentDescription (never a literal/null).
            onNodeWithTag(POST_CARD_IMAGE_TAG, useUnmergedTree = true)
                .assertExists()
                .assertContentDescriptionEquals("Gambar pada postingan")
        }

    @Test
    fun noImageElementWhenImageUrlNull() =
        runComposeUiTest {
            setContent { Card(model = model(imageUrl = null)) }
            // The no-image card renders NO image element — unchanged from the pre-image baseline.
            onNodeWithTag(POST_CARD_IMAGE_TAG, useUnmergedTree = true).assertDoesNotExist()
        }
}
