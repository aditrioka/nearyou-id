package id.nearyou.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-derivation coverage for the shared letter avatar (`mobile-post-card` § "Letter avatar
 * derivation is deterministic") — kotlin.test so the suite also runs on Kotlin/Native
 * (`:mobile:app:iosSimulatorArm64Test`), per docs/11 § 2.7.
 */
class LetterAvatarDerivationTest {
    @Test
    fun twoWordNameYieldsFirstAndLastInitials() {
        assertEquals("BS", avatarInitials("Budi Santoso"))
    }

    @Test
    fun singleWordNameYieldsOneInitial() {
        assertEquals("R", avatarInitials("Raka"))
    }

    @Test
    fun initialsAreUppercased() {
        assertEquals("RP", avatarInitials("raka pratama"))
    }

    @Test
    fun middleWordsAreSkipped_firstAndLastWin() {
        assertEquals("AW", avatarInitials("Agus Dwi Wibowo"))
    }

    @Test
    fun surrogatePairLeadingWordDoesNotCrashAndKeepsTheFullCodePoint() {
        // U+1F60E (😎) is a surrogate pair; the initial must keep both chars, not half a pair.
        val initials = avatarInitials("😎cool Dude")
        assertEquals("😎" + "D", initials)
    }

    @Test
    fun blankAndWhitespaceOnlyNamesYieldEmptyInitials() {
        assertEquals("", avatarInitials(""))
        assertEquals("", avatarInitials("   "))
    }

    @Test
    fun irregularWhitespaceIsSafe_emptySegmentsDiscarded() {
        assertEquals("BS", avatarInitials(" Budi  Santoso"))
    }

    @Test
    fun sameUsernameAlwaysYieldsTheSameTone() {
        assertEquals(avatarTone("dewi.kuliner"), avatarTone("dewi.kuliner"))
        assertEquals(avatarTone("raka.jkt"), avatarTone("raka.jkt"))
    }

    @Test
    fun toneMappingIsPinnedAcrossPlatforms() {
        // The fold is explicit (no hashCode()), so these single-char fixtures pin the exact
        // mapping: 'a'=97→Secondary, 'b'=98→Tertiary, 'c'=99→Primary. A platform divergence or an
        // accidental algorithm change breaks this test on every target, incl. iosSimulatorArm64.
        assertEquals(AvatarTone.Secondary, avatarTone("a"))
        assertEquals(AvatarTone.Tertiary, avatarTone("b"))
        assertEquals(AvatarTone.Primary, avatarTone("c"))
        assertTrue(setOf(avatarTone("a"), avatarTone("b"), avatarTone("c")).size == 3)
    }
}
