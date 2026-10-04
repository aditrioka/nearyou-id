package id.nearyou.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.theme.NearYouTheme
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private const val LIST_TAG = "feedList"

private data class Item(val id: String, val content: String)

private fun cardModel(item: Item) =
    PostCardModel(
        id = item.id,
        authorUsername = "raka.jkt",
        authorDisplayName = "Raka",
        content = item.content,
        cityName = "Jakarta",
        distanceM = 120.0,
        createdAt = "2026-10-04T10:00:00Z",
        likedByViewer = false,
        replyCount = 0,
    )

/**
 * mobile-design-system § "A feed list stays pinned to the top when posts are prepended" (#173): the shared
 * [PostFeedList] keeps a viewer AT the top pinned to the top when a refresh prepends posts (so their own new
 * post is visible on return), while a scrolled-down viewer keeps their position. Robolectric; in the
 * Release-variant `*Test` exclude block (the `ui-test-manifest` host activity is debug-only).
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h891dp")
@OptIn(ExperimentalTestApi::class)
class PostFeedListTest {
    private val initial = (1..8).map { Item("p$it", "POST_$it") }

    @Test
    fun aViewerAtTheTop_seesAPrependedPost() {
        runComposeUiTest {
            var posts by mutableStateOf(initial)
            setContent { NearYouTheme { feed(posts) } }
            waitForIdle()
            onNodeWithText("POST_1").assertIsDisplayed()

            runOnIdle { posts = listOf(Item("new", "NEW_POST")) + posts }
            waitForIdle()

            // Fully inside the viewport, not just a sliver: without the pin, LazyColumn keeps POST_1's key in
            // view and the new card sits ABOVE the list top (only its bottom edge peeks in). positionInRoot is
            // UNCLIPPED (boundsInRoot clamps an off-screen node to the root edge and would hide the failure).
            val listTop = onNodeWithTag(LIST_TAG).fetchSemanticsNode().positionInRoot.y
            val newCardTop = newCard().fetchSemanticsNode().positionInRoot.y
            assertTrue(newCardTop >= listTop, "the prepended post's card is fully visible at the top ($newCardTop < $listTop)")
        }
    }

    @Test
    fun aViewerSlightlyScrolledAtIndex0_isNotJumped_whenAPostIsPrepended() {
        // Index 0 but offset > 0 is NOT "at the top": the viewer started scrolling, so the list must not jump
        // (pins the offset half of the at-top check — an index-only check would wrongly pin here).
        runComposeUiTest {
            var posts by mutableStateOf(initial)
            setContent { NearYouTheme { feed(posts) } }
            waitForIdle()
            onNodeWithTag(LIST_TAG).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 40f) }
            waitForIdle()
            val post1Before = onNodeWithText("POST_1").fetchSemanticsNode().positionInRoot.y

            runOnIdle { posts = listOf(Item("new", "NEW_POST")) + posts }
            waitForIdle()

            val post1After = onNodeWithText("POST_1").fetchSemanticsNode().positionInRoot.y
            assertTrue(abs(post1After - post1Before) < 1f, "a viewer who already scrolled keeps their place ($post1Before → $post1After)")
        }
    }

    @Test
    fun aScrolledDownViewer_keepsTheirPosition_whenAPostIsPrepended() {
        runComposeUiTest {
            var posts by mutableStateOf(initial)
            setContent { NearYouTheme { feed(posts) } }
            waitForIdle()
            onNodeWithTag(LIST_TAG).performScrollToIndex(5)
            waitForIdle()
            onNodeWithText("POST_6").assertIsDisplayed()

            runOnIdle { posts = listOf(Item("new", "NEW_POST")) + posts }
            waitForIdle()

            onNodeWithText("POST_6").assertIsDisplayed()
            onNodeWithText("NEW_POST").assertIsNotDisplayed()
        }
    }

    private fun ComposeUiTest.newCard() =
        onNode(
            hasTestTag("card") and (hasText("NEW_POST", substring = true) or hasAnyDescendant(hasText("NEW_POST"))),
        )

    @androidx.compose.runtime.Composable
    private fun feed(posts: List<Item>) =
        PostFeedList(
            posts = posts,
            keyOf = { it.id },
            cardModelOf = ::cardModel,
            isLoadingMore = false,
            loadMoreError = false,
            onLoadMore = {},
            onRetryLoadMore = {},
            onOpenPost = {},
            onToggleLike = {},
            onReplyShortcut = {},
            onOpenProfile = {},
            listTag = LIST_TAG,
            cardTag = "card",
        )
}
