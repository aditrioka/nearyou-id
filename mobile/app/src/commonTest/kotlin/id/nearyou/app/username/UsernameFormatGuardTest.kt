package id.nearyou.app.username

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage of the pure [UsernameFormatGuard] mirroring the backend `premium-username-customization`
 * § "Candidate format validation": the 3..30 length boundary (incl. the 30-accept / 31-reject edge),
 * the charset, the no-consecutive-dots guard, and the 30-code-point input cap (surrogate-pair-safe).
 */
class UsernameFormatGuardTest {
    @Test
    fun `well-formed candidates pass`() {
        assertTrue(UsernameFormatGuard.isValid("abc"))
        assertTrue(UsernameFormatGuard.isValid("a_b.c"))
        assertTrue(UsernameFormatGuard.isValid("user1.test_2"))
    }

    @Test
    fun `format-invalid candidates are rejected`() {
        assertFalse(UsernameFormatGuard.isValid("ab"), "length 2 (below 3)")
        assertFalse(UsernameFormatGuard.isValid("Abc"), "uppercase")
        assertFalse(UsernameFormatGuard.isValid(".abc"), "leading dot")
        assertFalse(UsernameFormatGuard.isValid("abc."), "trailing dot")
        assertFalse(UsernameFormatGuard.isValid("_abc"), "leading underscore")
        assertFalse(UsernameFormatGuard.isValid("a..b"), "consecutive dots (application-layer guard)")
        assertFalse(UsernameFormatGuard.isValid("a b"), "whitespace is not in the charset")
    }

    @Test
    fun `length 30 is accepted and 31 is rejected`() {
        assertTrue(UsernameFormatGuard.isValid("a".repeat(30)), "30 code points is the upper bound")
        assertFalse(UsernameFormatGuard.isValid("a".repeat(31)), "31 exceeds the bound")
    }

    @Test
    fun `cap truncates to 30 code points`() {
        assertEquals(30, UsernameFormatGuard.cap("a".repeat(45)).length)
        assertEquals("abc", UsernameFormatGuard.cap("abc"), "under the cap is unchanged")
    }

    @Test
    fun `cap counts a surrogate pair as one code point`() {
        // "😀" is a surrogate pair (2 UTF-16 chars, 1 code point). 30 of them is exactly the cap.
        val emoji = "😀"
        val capped = UsernameFormatGuard.cap(emoji.repeat(40))
        // 30 code points == 60 UTF-16 chars; the cap never splits a surrogate pair.
        assertEquals(60, capped.length)
    }
}
