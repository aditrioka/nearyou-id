package id.nearyou.app.screens.post

import id.nearyou.app.post.PostCreationOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure coverage of the [postCreationUiState] projection + the [codePointLength] helper (design D5) —
 * the content-length gate and outcome→state mapping the screen renders, exercised without a Compose
 * UI runner (mirroring `AgeGateUiStateTest` / `NearbyTimelineUiStateTest`). Exhaustive over
 * [PostCreationOutcome] + the in-flight flag; also asserts the projected state carries no PII.
 */
class PostCreationUiStateTest {
    // A non-BMP emoji: one Unicode code point, TWO UTF-16 units (length 2). The gate counts it as 1.
    private val emoji = "😀" // U+1F600 😀

    @Test
    fun `empty or whitespace-only content disables submit and is not over-limit`() {
        val empty = postCreationUiState(content = "", outcome = null, inFlight = false)
        assertFalse(empty.submitEnabled, "empty content cannot submit")
        assertFalse(empty.overLimit)
        assertEquals(0, empty.charCount)

        val blank = postCreationUiState(content = "   ", outcome = null, inFlight = false)
        assertFalse(blank.submitEnabled, "whitespace-only content is blank → cannot submit")
        assertFalse(blank.overLimit)
        assertEquals(3, blank.charCount, "the counter still reflects the raw code-point count")
    }

    @Test
    fun `a single non-blank char enables submit`() {
        val state = postCreationUiState(content = "halo", outcome = null, inFlight = false)
        assertTrue(state.submitEnabled)
        assertFalse(state.overLimit)
        assertEquals(4, state.charCount)
    }

    @Test
    fun `280 code points enabled and not over-limit, 281 over-limit and disabled`() {
        val at = postCreationUiState(content = "a".repeat(280), outcome = null, inFlight = false)
        assertEquals(280, at.charCount)
        assertTrue(at.submitEnabled, "exactly 280 is submittable")
        assertFalse(at.overLimit)

        val over = postCreationUiState(content = "a".repeat(281), outcome = null, inFlight = false)
        assertEquals(281, over.charCount)
        assertFalse(over.submitEnabled, "281 exceeds the limit → disabled")
        assertTrue(over.overLimit)
    }

    @Test
    fun `multi-byte emoji counts as one code point`() {
        val at = postCreationUiState(content = emoji.repeat(280), outcome = null, inFlight = false)
        assertEquals(280, at.charCount, "280 non-BMP emoji = 280 code points, NOT 560 UTF-16 units")
        assertTrue(at.submitEnabled)
        assertFalse(at.overLimit)

        val over = postCreationUiState(content = emoji.repeat(281), outcome = null, inFlight = false)
        assertEquals(281, over.charCount)
        assertTrue(over.overLimit)
        assertFalse(over.submitEnabled)
    }

    @Test
    fun `codePointLength counts surrogate pairs and lone surrogates as one`() {
        assertEquals(0, "".codePointLength())
        assertEquals(3, "abc".codePointLength())
        assertEquals(1, emoji.codePointLength(), "a surrogate pair is one code point")
        assertEquals(3, ("a" + emoji + "b").codePointLength())
        assertEquals(1, "\uD83D".codePointLength(), "a lone high surrogate counts as one")
    }

    @Test
    fun `in-flight yields Loading with submit disabled and wins over any prior outcome`() {
        val loading = postCreationUiState(content = "halo", outcome = null, inFlight = true)
        assertTrue(loading.loading)
        assertFalse(loading.submitEnabled)
        assertNull(loading.banner)
        assertFalse(loading.success)

        val loadingOverOutcome = postCreationUiState(content = "halo", outcome = PostCreationOutcome.NetworkError, inFlight = true)
        assertTrue(loadingOverOutcome.loading, "in-flight supersedes a prior outcome")
        assertNull(loadingOverOutcome.banner)
    }

    @Test
    fun `each outcome maps to its success or error-banner state`() {
        assertTrue(postCreationUiState("halo", PostCreationOutcome.Success("p1"), false).success)
        assertNull(postCreationUiState("halo", PostCreationOutcome.Success("p1"), false).banner, "success shows no banner")

        assertEquals(PostCreationBanner.CONTENT_EMPTY, postCreationUiState("", PostCreationOutcome.ContentEmpty, false).banner)
        assertEquals(PostCreationBanner.CONTENT_TOO_LONG, postCreationUiState("halo", PostCreationOutcome.ContentTooLong, false).banner)
        assertEquals(
            PostCreationBanner.LOCATION_OUT_OF_BOUNDS,
            postCreationUiState("halo", PostCreationOutcome.LocationOutOfBounds, false).banner,
        )
        assertEquals(PostCreationBanner.CONTENT_REJECTED, postCreationUiState("halo", PostCreationOutcome.ContentRejected, false).banner)
        assertEquals(
            PostCreationBanner.LOCATION_UNAVAILABLE,
            postCreationUiState("halo", PostCreationOutcome.LocationUnavailable, false).banner,
        )
        assertEquals(PostCreationBanner.NETWORK, postCreationUiState("halo", PostCreationOutcome.NetworkError, false).banner)
        assertEquals(
            PostCreationBanner.NETWORK,
            postCreationUiState("halo", PostCreationOutcome.Error, false).banner,
            "Error reuses the network banner",
        )

        // No outcome (fresh) → no banner, not success.
        val fresh = postCreationUiState("halo", outcome = null, inFlight = false)
        assertNull(fresh.banner)
        assertFalse(fresh.success)
    }

    @Test
    fun `projected state carries no PII (success id is dropped)`() {
        val rendered = postCreationUiState("halo", PostCreationOutcome.Success("SECRET-POST-ID-0193"), false).toString()
        assertFalse(rendered.contains("SECRET-POST-ID-0193"), "the success post id must not reach the UI state: $rendered")
    }
}
