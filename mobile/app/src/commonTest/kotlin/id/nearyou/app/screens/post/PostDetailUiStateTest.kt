package id.nearyou.app.screens.post

import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.ReplyPostOutcome
import id.nearyou.app.post.fakeReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure coverage of the post-detail projections (task 9.1) — the replies-list state mapping, the reply
 * composer's code-point gate, the like/reply banner mappers, and the reset-hours countdown — exercised
 * without a Compose UI runner (mirroring `PostCreationUiStateTest` / `NearbyTimelineUiStateTest`).
 * Exhaustive over the outcomes; also asserts the projected state carries no PII (no `author_id`).
 */
class PostDetailUiStateTest {
    // A non-BMP emoji: one Unicode code point, TWO UTF-16 units (length 2). The gate counts it as 1.
    private val emoji = "😀" // U+1F600 😀

    // ---- replies list state ----

    @Test
    fun `replies state is Loading while in-flight or not-yet-loaded`() {
        assertEquals(RepliesUiState.Loading, repliesUiState(outcome = null, inFlight = true))
        assertEquals(RepliesUiState.Loading, repliesUiState(outcome = null, inFlight = false))
        // in-flight supersedes a prior outcome
        assertEquals(
            RepliesUiState.Loading,
            repliesUiState(RepliesOutcome.Loaded(listOf(fakeReply()), nextCursor = null), inFlight = true),
        )
    }

    @Test
    fun `replies state maps Loaded-non-empty to Content and Loaded-empty to Empty and NetworkError to Error`() {
        val content = repliesUiState(RepliesOutcome.Loaded(listOf(fakeReply(content = "hi")), nextCursor = "tok"), inFlight = false)
        assertTrue(content is RepliesUiState.Content)
        assertEquals(1, content.replies.size)
        assertEquals("hi", content.replies.first().content)

        assertEquals(RepliesUiState.Empty, repliesUiState(RepliesOutcome.Loaded(emptyList(), nextCursor = null), inFlight = false))
        assertEquals(RepliesUiState.Error, repliesUiState(RepliesOutcome.NetworkError, inFlight = false))
    }

    @Test
    fun `projected reply carries author_id for the self-block gate plus the display identity`() {
        // mobile-block-from-content (the MODIFIED PII-projection requirement): ReplyUi CARRIES the reply
        // author_id — solely for the SelfUserIdProvider self-block gate + the block path param — plus the
        // renderable display identity. The never-RENDERED guarantee is the screen test's assertion
        // (the UUID never appears in the rendered tree), not a projection-level drop anymore.
        val state =
            repliesUiState(
                RepliesOutcome.Loaded(
                    listOf(
                        fakeReply(
                            authorId = "33333333-3333-3333-3333-333333333333",
                            authorUsername = "sinta.mhr",
                            authorDisplayName = "Sinta Maharani",
                            content = "halo",
                        ),
                    ),
                    nextCursor = null,
                ),
                inFlight = false,
            )
        assertTrue(state is RepliesUiState.Content)
        val reply = state.replies.first()
        assertEquals("33333333-3333-3333-3333-333333333333", reply.authorId)
        assertEquals("sinta.mhr", reply.authorUsername)
        assertEquals("Sinta Maharani", reply.authorDisplayName)
    }

    @Test
    fun `an identity-less reply still projects - older-backend guard`() {
        val state =
            repliesUiState(
                RepliesOutcome.Loaded(
                    listOf(fakeReply(authorUsername = null, authorDisplayName = null, content = "halo")),
                    nextCursor = null,
                ),
                inFlight = false,
            )
        assertTrue(state is RepliesUiState.Content)
        val reply = state.replies.first()
        assertEquals(null, reply.authorUsername)
        assertEquals(null, reply.authorDisplayName)
        assertEquals("halo", reply.content)
    }

    // ---- reply composer code-point gate ----

    @Test
    fun `empty or blank content disables submit and is not over-limit`() {
        val empty = replyComposerUiState(content = "", inFlight = false)
        assertFalse(empty.submitEnabled)
        assertFalse(empty.overLimit)
        assertEquals(0, empty.charCount)

        val blank = replyComposerUiState(content = "   ", inFlight = false)
        assertFalse(blank.submitEnabled, "whitespace-only is blank → cannot submit")
        assertEquals(3, blank.charCount)
    }

    @Test
    fun `a single non-blank char enables submit`() {
        val state = replyComposerUiState(content = "halo", inFlight = false)
        assertTrue(state.submitEnabled)
        assertFalse(state.overLimit)
        assertEquals(4, state.charCount)
    }

    @Test
    fun `280 code points enabled and not over-limit then 281 over-limit and disabled`() {
        val at = replyComposerUiState(content = "a".repeat(280), inFlight = false)
        assertEquals(280, at.charCount)
        assertTrue(at.submitEnabled, "exactly 280 is submittable")
        assertFalse(at.overLimit)

        val over = replyComposerUiState(content = "a".repeat(281), inFlight = false)
        assertEquals(281, over.charCount)
        assertFalse(over.submitEnabled, "281 exceeds the limit → disabled")
        assertTrue(over.overLimit)
    }

    @Test
    fun `multi-byte emoji counts as one code point not two UTF-16 units`() {
        val at = emoji.repeat(280)
        val state = replyComposerUiState(content = at, inFlight = false)
        assertEquals(280, state.charCount, "280 non-BMP emoji = 280 code points, NOT 560")
        assertTrue(state.submitEnabled)
        assertFalse(state.overLimit)
        // Guard against a regression to UTF-16 `.length`: the same string's .length is 560 (2 units each).
        assertEquals(560, at.length, "sanity: the emoji string is 560 UTF-16 units")
        assertTrue(at.length != state.charCount, "code-point count (280) MUST differ from UTF-16 .length (560)")

        val over = replyComposerUiState(content = emoji.repeat(281), inFlight = false)
        assertEquals(281, over.charCount)
        assertTrue(over.overLimit)
        assertFalse(over.submitEnabled)
    }

    @Test
    fun `in-flight disables submit even for valid content`() {
        val state = replyComposerUiState(content = "halo", inFlight = true)
        assertFalse(state.submitEnabled, "no double-submit while a reply is in flight")
        assertEquals(4, state.charCount)
    }

    // ---- banners (exhaustive over the outcomes) ----

    @Test
    fun `like banner maps RateLimited to LikeCap and gone-or-network to Network and happy-or-null to none`() {
        assertEquals(PostDetailBanner.LikeCap(resetHours = 1), likeBanner(LikeOutcome.RateLimited(retryAfterSeconds = 3600)))
        assertEquals(PostDetailBanner.PostGone, likeBanner(LikeOutcome.PostGone))
        assertEquals(PostDetailBanner.Network, likeBanner(LikeOutcome.NetworkError))
        assertNull(likeBanner(LikeOutcome.Liked))
        assertNull(likeBanner(LikeOutcome.Unliked))
        assertNull(likeBanner(null))
    }

    @Test
    fun `reply banner maps RateLimited to ReplyCap and gone-invalid-network to Network and success-or-null to none`() {
        assertEquals(PostDetailBanner.ReplyCap(resetHours = 1), replyBanner(ReplyPostOutcome.RateLimited(retryAfterSeconds = 3600)))
        assertEquals(PostDetailBanner.PostGone, replyBanner(ReplyPostOutcome.PostGone))
        assertEquals(PostDetailBanner.Network, replyBanner(ReplyPostOutcome.InvalidContent))
        assertEquals(PostDetailBanner.Network, replyBanner(ReplyPostOutcome.NetworkError))
        assertNull(replyBanner(ReplyPostOutcome.Success(fakeReply())))
        assertNull(replyBanner(null))
    }

    @Test
    fun `reset hours rounds up with a floor of one`() {
        assertEquals(1, resetHours(3600), "exactly 1h → 1")
        assertEquals(2, resetHours(7200), "exactly 2h → 2")
        assertEquals(1, resetHours(1800), "30m rounds up to 1")
        assertEquals(1, resetHours(0), "0/absent → floor of 1, never 0 jam")
        assertEquals(24, resetHours(86_400), "a full WIB-day cap → 24")
    }
}
