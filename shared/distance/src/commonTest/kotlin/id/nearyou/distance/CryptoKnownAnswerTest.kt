@file:OptIn(ExperimentalStdlibApi::class)

package id.nearyou.distance

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Cross-platform known-answer test for the [hmacSha256] actuals (JVM / Android / iOS), pinned to
 * published **RFC 4231 HMAC-SHA-256 test vectors** — an external oracle, so all three actuals are
 * checked against a known-correct digest rather than merely agreeing with each other.
 *
 * Written with **`kotlin.test`** (`@Test` + `assertContentEquals`), NOT Kotest: the existing
 * `:shared:distance` Kotest `StringSpec`s run via the JUnit5 platform (`useJUnitPlatform()`), which
 * executes ONLY on JVM/Android. A Kotest `commonTest` would compile for the Kotlin/Native targets
 * but never RUN there, leaving the new iOS CommonCrypto `CCHmac` actual unverified. `kotlin.test`
 * `@Test` IS executed by the Kotlin/Native test runner, so `./gradlew :shared:distance:iosSimulatorArm64Test`
 * genuinely exercises the iOS actual against the RFC vector (the verification command set is
 * `:shared:distance:iosSimulatorArm64Test` + `:shared:distance:testDebugUnitTest`, NOT merely
 * `:shared:distance:build`, which compiles but does not run Native tests).
 */
class CryptoKnownAnswerTest {
    // RFC 4231 Test Case 1 — a 20-byte binary key (0x0b ×20) + ASCII data.
    @Test
    fun hmacSha256_matchesRfc4231TestCase1() {
        val key = ByteArray(20) { 0x0b }
        val msg = "Hi There".encodeToByteArray()
        val expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7".hexToByteArray()
        assertContentEquals(expected, hmacSha256(key, msg))
    }

    // RFC 4231 Test Case 2 — ASCII key "Jefe" + ASCII data.
    @Test
    fun hmacSha256_matchesRfc4231TestCase2() {
        val key = "Jefe".encodeToByteArray()
        val msg = "what do ya want for nothing?".encodeToByteArray()
        val expected = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843".hexToByteArray()
        assertContentEquals(expected, hmacSha256(key, msg))
    }

    // Empty-message coverage for the iOS empty-array placeholder branch (CryptoIos.kt: an empty
    // `msg` is pinned via a 1-byte placeholder while length 0 is passed to CCHmac). RFC 4231 has
    // no empty-message vector, so rather than a hardcoded digest this asserts the properties that
    // the placeholder-pinning path MUST satisfy on every target: a full 32-byte digest, determinism
    // (no uninitialized placeholder bytes leak into the output), and genuine dependence on BOTH the
    // key and the message (ruling out a degenerate/constant result). Byte-exact correctness of the
    // underlying primitive is already pinned by the RFC 4231 vectors above — the empty-message path
    // is the SAME CCHmac/Mac call, differing only in the (placeholder, length 0) message argument.
    // (The empty-KEY edge is deliberately NOT exercised: javax.crypto's SecretKeySpec rejects an
    // empty key, so an empty-key assertion cannot hold cross-platform; JitterEngine never passes one.)
    @Test
    fun hmacSha256_emptyMessage_isWellFormedDeterministicAndInputDependent() {
        val key = "Jefe".encodeToByteArray()
        val emptyMsgMac = hmacSha256(key, ByteArray(0))
        assertEquals(32, emptyMsgMac.size, "HMAC-SHA256 of an empty message must still be a 32-byte digest")
        assertContentEquals(emptyMsgMac, hmacSha256(key, ByteArray(0)), "empty-message MAC must be deterministic")
        assertFalse(
            emptyMsgMac.contentEquals(hmacSha256(key, "x".encodeToByteArray())),
            "empty-message MAC must differ from a non-empty-message MAC under the same key",
        )
        assertFalse(
            emptyMsgMac.contentEquals(hmacSha256("Jeff".encodeToByteArray(), ByteArray(0))),
            "empty-message MAC must still depend on the key",
        )
    }
}
