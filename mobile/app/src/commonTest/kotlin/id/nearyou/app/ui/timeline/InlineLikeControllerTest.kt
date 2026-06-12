package id.nearyou.app.ui.timeline

import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.post.LikeOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage of [InlineLikeController] — the ONE shared inline-like lifecycle both feed ViewModels
 * delegate to (`mobile-inline-post-actions` design D2). Pins, per the nearby/global spec deltas:
 * the optimistic flip in BOTH directions (the direction argument comes from the tapped post's CURRENT
 * state — never hardcoded), keep-on-success, revert + one-shot cap state on `RateLimited` (cleared via
 * the dismiss callback — nullable state, no Channel/SharedFlow), revert + reload on `PostGone`, the
 * silent revert on `NetworkError` (declared v1), the per-post in-flight guard, and the
 * interleaved-refresh posture (an outcome swap mid-toggle resolves against the FRESH list; a vanished
 * post is a no-op; the cap state still raises).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InlineLikeControllerTest {
    private data class TestPost(
        val id: String,
        val liked: Boolean,
    )

    private class Host {
        var posts: List<TestPost>? = null
        var reloads = 0
    }

    private fun kotlinx.coroutines.CoroutineScope.controller(
        likeFlow: FakeLikeFlow,
        host: Host,
    ): InlineLikeController<TestPost> =
        InlineLikeController(
            likeFlow = likeFlow,
            scope = this,
            idOf = { it.id },
            setLiked = { post, liked -> post.copy(liked = liked) },
            readPosts = { host.posts },
            writePosts = { host.posts = it },
            onPostGone = { host.reloads++ },
        )

    @Test
    fun likeDirection_flipsOptimistically_andKeepsOnSuccess() =
        runTest {
            val likeFlow = FakeLikeFlow(LikeOutcome.Liked).apply { gate = CompletableDeferred() }
            val host = Host().apply { posts = listOf(TestPost("p1", liked = false)) }
            val controller = controller(likeFlow, host)

            controller.toggle("p1", currentlyLiked = false)

            assertTrue(host.posts!!.single().liked, "the flip is optimistic (before the outcome resolves)")
            likeFlow.gate!!.complete(Unit)
            advanceUntilIdle()
            assertTrue(host.posts!!.single().liked, "the liked state stands after LikeOutcome.Liked")
            assertEquals(listOf("p1" to false), likeFlow.invocations, "the like direction passes currentlyLiked = false")
        }

    @Test
    fun unlikeDirection_passesCurrentlyLikedTrue_andStands() =
        runTest {
            val likeFlow = FakeLikeFlow(LikeOutcome.Unliked)
            val host = Host().apply { posts = listOf(TestPost("p1", liked = true)) }
            val controller = controller(likeFlow, host)

            controller.toggle("p1", currentlyLiked = true)

            assertFalse(host.posts!!.single().liked, "the unlike flip is optimistic")
            advanceUntilIdle()
            assertFalse(host.posts!!.single().liked, "the not-liked state stands after LikeOutcome.Unliked")
            assertEquals(
                listOf("p1" to true),
                likeFlow.invocations,
                "the direction derives from the post's CURRENT state — currentlyLiked = true → DELETE; never hardcoded",
            )
        }

    @Test
    fun rateLimited_reverts_andRaisesOneShotCapState_clearedByDismiss() =
        runTest {
            val likeFlow = FakeLikeFlow(LikeOutcome.RateLimited(retryAfterSeconds = 51540))
            val host = Host().apply { posts = listOf(TestPost("p1", liked = false)) }
            val controller = controller(likeFlow, host)

            controller.toggle("p1", currentlyLiked = false)
            advanceUntilIdle()

            assertFalse(host.posts!!.single().liked, "the optimistic flip is reverted on a 429")
            assertEquals(51540L, controller.capRetryAfterSeconds.value, "the one-shot cap state carries Retry-After")
            controller.onCapDialogDismissed()
            assertNull(controller.capRetryAfterSeconds.value, "the dismiss callback clears the nullable state")
        }

    @Test
    fun postGone_reverts_andTriggersReload() =
        runTest {
            val likeFlow = FakeLikeFlow(LikeOutcome.PostGone)
            val host = Host().apply { posts = listOf(TestPost("p1", liked = false)) }
            val controller = controller(likeFlow, host)

            controller.toggle("p1", currentlyLiked = false)
            advanceUntilIdle()

            assertFalse(host.posts!!.single().liked, "the flip is reverted on PostGone")
            assertEquals(1, host.reloads, "PostGone self-heals via the host's reload")
            assertNull(controller.capRetryAfterSeconds.value, "no cap state on PostGone")
        }

    @Test
    fun networkError_revertsSilently() =
        runTest {
            val likeFlow = FakeLikeFlow(LikeOutcome.NetworkError)
            val host = Host().apply { posts = listOf(TestPost("p1", liked = false)) }
            val controller = controller(likeFlow, host)

            controller.toggle("p1", currentlyLiked = false)
            advanceUntilIdle()

            assertFalse(host.posts!!.single().liked, "the flip is reverted on NetworkError")
            assertNull(controller.capRetryAfterSeconds.value, "no cap state, no error surface — the declared v1 posture")
            assertEquals(0, host.reloads, "NetworkError does not trigger a reload")
        }

    @Test
    fun inFlightReTaps_areIgnored() =
        runTest {
            val likeFlow = FakeLikeFlow(LikeOutcome.Liked).apply { gate = CompletableDeferred() }
            val host = Host().apply { posts = listOf(TestPost("p1", liked = false)) }
            val controller = controller(likeFlow, host)

            controller.toggle("p1", currentlyLiked = false)
            controller.toggle("p1", currentlyLiked = true)

            likeFlow.gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, likeFlow.invocationCount, "the per-post in-flight guard ignores the re-tap (no double POST)")
        }

    @Test
    fun interleavedRefresh_appliesAgainstTheFreshList() =
        runTest {
            val likeFlow = FakeLikeFlow(LikeOutcome.Liked).apply { gate = CompletableDeferred() }
            val host = Host().apply { posts = listOf(TestPost("p1", liked = false)) }
            val controller = controller(likeFlow, host)

            controller.toggle("p1", currentlyLiked = false)
            // A refresh completes mid-toggle and swaps the outcome with FRESH (pre-toggle) server data.
            host.posts = listOf(TestPost("p1", liked = false), TestPost("p2", liked = false))
            likeFlow.gate!!.complete(Unit)
            advanceUntilIdle()

            val fresh = host.posts!!
            assertTrue(fresh.first { it.id == "p1" }.liked, "the success re-asserts the toggle result on the fresh list")
            assertFalse(fresh.first { it.id == "p2" }.liked, "other posts are untouched")
        }

    @Test
    fun interleavedRefresh_vanishedPost_isANoOp_butCapStillRaises() =
        runTest {
            val likeFlow = FakeLikeFlow(LikeOutcome.RateLimited(retryAfterSeconds = 1140)).apply { gate = CompletableDeferred() }
            val host = Host().apply { posts = listOf(TestPost("p1", liked = false)) }
            val controller = controller(likeFlow, host)

            controller.toggle("p1", currentlyLiked = false)
            // The refreshed page no longer contains p1 (deleted/hidden server-side).
            val refreshed = listOf(TestPost("p2", liked = true))
            host.posts = refreshed
            likeFlow.gate!!.complete(Unit)
            advanceUntilIdle()

            assertEquals(refreshed, host.posts, "the completion is a no-op on a list that no longer holds the post")
            assertEquals(1140L, controller.capRetryAfterSeconds.value, "a RateLimited completion still raises the cap state")
        }
}
