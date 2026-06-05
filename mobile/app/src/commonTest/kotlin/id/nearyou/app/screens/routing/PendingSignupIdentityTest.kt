package id.nearyou.app.screens.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage of the [PendingSignupIdentity] holder contract (design Decision 4 / `mobile-age-gate`
 * § "AgeGateScreen reuses the verified Google identity"). Pins the two load-bearing properties the
 * screen-level effects rely on: [PendingSignupIdentity.peek] is **non-clearing** (so an in-screen
 * retryable error can resubmit with the same identity) and [PendingSignupIdentity.clear] actually
 * nulls the token and is idempotent (so a terminal exit leaves no residue and double-clears are safe).
 * The behavioral wiring (the screen calls `clear()` on terminal exits but not on retryable errors) is
 * covered by `AgeGateScreenTest`; this test guards the holder primitive itself.
 */
class PendingSignupIdentityTest {
    @Test
    fun peekIsNonClearing_returnsTheSameTokenAcrossReads() {
        val holder = PendingSignupIdentity()
        holder.set("g-id")
        assertEquals("g-id", holder.peek(), "first peek returns the set token")
        assertEquals("g-id", holder.peek(), "peek does NOT consume — a second read returns the same token")
    }

    @Test
    fun set_overwritesAnyPriorToken() {
        val holder = PendingSignupIdentity()
        holder.set("first")
        holder.set("second")
        assertEquals("second", holder.peek(), "set overwrites the prior token")
    }

    @Test
    fun clear_nullsTheToken_andIsIdempotent() {
        val holder = PendingSignupIdentity()
        holder.set("g-id")
        holder.clear()
        assertNull(holder.peek(), "clear nulls the token (a terminal exit leaves no residue)")
        holder.clear()
        assertNull(holder.peek(), "clear is idempotent — a second clear is safe and stays null")
    }

    @Test
    fun freshHolder_peeksNull() {
        assertNull(PendingSignupIdentity().peek(), "a never-set holder (e.g. after process death) peeks null")
    }
}
