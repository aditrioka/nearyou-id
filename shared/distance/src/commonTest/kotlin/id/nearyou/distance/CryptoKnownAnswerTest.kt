@file:OptIn(ExperimentalStdlibApi::class)

package id.nearyou.distance

import kotlin.test.Test
import kotlin.test.assertContentEquals

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
}
