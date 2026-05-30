package id.nearyou.app.admin.auth

import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.commons.codec.binary.Base32
import java.security.SecureRandom

/**
 * Unit tests for [TotpVerifier] covering the `admin-login-argon2-totp`
 * spec Req "TOTP verification uses RFC 6238 with SHA-1 / 30s / 6 digits /
 * ±1 step skew".
 *
 * Skew matrix:
 *  - current step (T)         → TRUE
 *  - T - 30s (1 step back)    → TRUE  (±1 skew tolerance)
 *  - T + 30s (1 step forward) → TRUE  (±1 skew tolerance)
 *  - T - 60s (2 steps back)   → FALSE (outside window)
 *  - T + 60s (2 steps forward)→ FALSE (outside window)
 *
 * Plus negative cases: malformed (non-digit), wrong length, leading/trailing
 * whitespace all return FALSE without throwing.
 */
class TotpVerifierTest : StringSpec({

    val codeGenerator = DefaultCodeGenerator(HashingAlgorithm.SHA1, TotpVerifier.DIGITS)
    val base32 = Base32()
    val secureRandom = SecureRandom()

    /** Helper: generate a 6-digit TOTP code for a given secret + epoch second. */
    fun generateCode(
        secretRawBytes: ByteArray,
        epochSecond: Long,
    ): String {
        val step = epochSecond / TotpVerifier.TIME_PERIOD_SECONDS
        return codeGenerator.generate(base32.encodeAsString(secretRawBytes), step)
    }

    fun randomSecretBytes(): ByteArray = ByteArray(20).also { secureRandom.nextBytes(it) }

    // ----------------- Parameter constants -----------------
    "algorithm is SHA-1 (Google Authenticator default)" {
        TotpVerifier.ALGORITHM shouldBe HashingAlgorithm.SHA1
    }

    "step period is 30 seconds (RFC 6238 default)" {
        TotpVerifier.TIME_PERIOD_SECONDS shouldBe 30
    }

    "digit count is 6 (RFC 6238 + universal authenticator compat)" {
        TotpVerifier.DIGITS shouldBe 6
    }

    "skew tolerance is ±1 step (RFC §5.2 default)" {
        TotpVerifier.ALLOWED_DISCREPANCY_STEPS shouldBe 1
    }

    // ----------------- Skew matrix -----------------
    "code generated for the current step verifies (T)" {
        val secret = randomSecretBytes()
        val now = System.currentTimeMillis() / 1000
        val code = generateCode(secret, now)
        TotpVerifier.verify(secret, code) shouldBe true
    }

    "code from one step ago verifies (T - 30s; ±1 skew tolerance)" {
        val secret = randomSecretBytes()
        val now = System.currentTimeMillis() / 1000
        val pastCode = generateCode(secret, now - TotpVerifier.TIME_PERIOD_SECONDS)
        // Note: real-time verify of a past-step code requires no time
        // mocking — the verifier inherently checks the current ±N window.
        TotpVerifier.verify(secret, pastCode) shouldBe true
    }

    "code from one step ahead verifies (T + 30s; forward skew tolerance)" {
        val secret = randomSecretBytes()
        val now = System.currentTimeMillis() / 1000
        val futureCode = generateCode(secret, now + TotpVerifier.TIME_PERIOD_SECONDS)
        TotpVerifier.verify(secret, futureCode) shouldBe true
    }

    "code from two steps ago fails (T - 60s; outside window)" {
        val secret = randomSecretBytes()
        val now = System.currentTimeMillis() / 1000
        val tooFarPastCode = generateCode(secret, now - 2 * TotpVerifier.TIME_PERIOD_SECONDS)
        TotpVerifier.verify(secret, tooFarPastCode) shouldBe false
    }

    "code from two steps ahead fails (T + 60s; outside window)" {
        val secret = randomSecretBytes()
        val now = System.currentTimeMillis() / 1000
        val tooFarFutureCode = generateCode(secret, now + 2 * TotpVerifier.TIME_PERIOD_SECONDS)
        TotpVerifier.verify(secret, tooFarFutureCode) shouldBe false
    }

    // ----------------- Malformed-input handling -----------------
    "code with non-digit characters returns FALSE" {
        val secret = randomSecretBytes()
        TotpVerifier.verify(secret, "abcdef") shouldBe false
        TotpVerifier.verify(secret, "12ab56") shouldBe false
    }

    "code with wrong length (5 digits) returns FALSE" {
        val secret = randomSecretBytes()
        TotpVerifier.verify(secret, "12345") shouldBe false
    }

    "code with wrong length (7 digits) returns FALSE" {
        val secret = randomSecretBytes()
        TotpVerifier.verify(secret, "1234567") shouldBe false
    }

    "code with leading whitespace returns FALSE (no silent strip)" {
        val secret = randomSecretBytes()
        val now = System.currentTimeMillis() / 1000
        val code = generateCode(secret, now)
        TotpVerifier.verify(secret, " $code") shouldBe false
    }

    "code with trailing whitespace returns FALSE (no silent strip)" {
        val secret = randomSecretBytes()
        val now = System.currentTimeMillis() / 1000
        val code = generateCode(secret, now)
        TotpVerifier.verify(secret, "$code ") shouldBe false
    }

    "empty code returns FALSE" {
        val secret = randomSecretBytes()
        TotpVerifier.verify(secret, "") shouldBe false
    }

    // ----------------- Sentinel for timing equalization -----------------
    "verifyAgainstSentinel returns FALSE for arbitrary 6-digit code (sentinel not a load-bearing secret)" {
        // Try a handful of arbitrary 6-digit codes; the sentinel-secret +
        // current-step combination would only hit one specific 6-digit
        // value (which the test doesn't know and doesn't need to know).
        TotpVerifier.verifyAgainstSentinel("000000") shouldBe false
        TotpVerifier.verifyAgainstSentinel("123456") shouldBe false
        TotpVerifier.verifyAgainstSentinel("999999") shouldBe false
    }

    "verifyAgainstSentinel returns FALSE for malformed inputs (consistent with verify)" {
        TotpVerifier.verifyAgainstSentinel("abcdef") shouldBe false
        TotpVerifier.verifyAgainstSentinel("") shouldBe false
        TotpVerifier.verifyAgainstSentinel("12345") shouldBe false
    }
})
