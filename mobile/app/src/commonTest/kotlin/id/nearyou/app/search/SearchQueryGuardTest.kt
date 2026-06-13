package id.nearyou.app.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Coverage of the pure [SearchQueryGuard]: trim, the 2..100 boundaries, below-2 ineligibility, and the
 *  100-code-point input cap (surrogate-pair-safe). Mirrors the backend `premium-search` length guard. */
class SearchQueryGuardTest {
    @Test
    fun `below 2 code points is ineligible`() {
        assertFalse(SearchQueryGuard.isEligible(""))
        assertFalse(SearchQueryGuard.isEligible("a"))
        assertFalse(SearchQueryGuard.isEligible("   "), "whitespace-only trims to empty")
        assertFalse(SearchQueryGuard.isEligible(" a "), "trims to 1 code point")
    }

    @Test
    fun `2 and 100 code-point boundaries are eligible`() {
        assertTrue(SearchQueryGuard.isEligible("ab"))
        assertTrue(SearchQueryGuard.isEligible("a".repeat(100)))
        assertTrue(SearchQueryGuard.isEligible("  ab  "), "trims to 2 code points")
    }

    @Test
    fun `above 100 code points is ineligible`() {
        assertFalse(SearchQueryGuard.isEligible("a".repeat(101)))
    }

    @Test
    fun `cap truncates to 100 code points`() {
        assertEquals(100, SearchQueryGuard.codePointLength(SearchQueryGuard.cap("a".repeat(150))))
        assertEquals("abc", SearchQueryGuard.cap("abc"), "under the cap is unchanged")
    }

    @Test
    fun `cap counts a surrogate pair as one code point`() {
        // "😀" is a surrogate pair (2 UTF-16 chars, 1 code point). 100 of them is exactly the cap.
        val emoji = "😀"
        assertEquals(100, SearchQueryGuard.codePointLength(emoji.repeat(100)))
        assertTrue(SearchQueryGuard.isEligible(emoji.repeat(100)))
        assertEquals(100, SearchQueryGuard.codePointLength(SearchQueryGuard.cap(emoji.repeat(120))))
    }

    @Test
    fun `normalize trims surrounding whitespace`() {
        assertEquals("jakarta", SearchQueryGuard.normalize("  jakarta  "))
    }
}
