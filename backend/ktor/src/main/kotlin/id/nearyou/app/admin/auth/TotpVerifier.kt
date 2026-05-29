package id.nearyou.app.admin.auth

import dev.samstevens.totp.code.CodeGenerator
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.DefaultCodeVerifier
import dev.samstevens.totp.code.HashingAlgorithm
import dev.samstevens.totp.time.SystemTimeProvider
import org.apache.commons.codec.binary.Base32

/**
 * TOTP RFC 6238 verifier wrapping `dev.samstevens.totp:totp`.
 *
 * Per `admin-login-argon2-totp` design.md D3 + spec Req "TOTP verification
 * uses RFC 6238 with SHA-1 / 30s / 6 digits / ±1 step skew":
 *  - Algorithm: HMAC-SHA-1 (Google Authenticator + 1Password + Authy default)
 *  - Step period: 30 seconds (RFC 6238 default)
 *  - Digits: 6 (RFC 6238 default + universal authenticator-app compat)
 *  - Skew tolerance: ±1 step (90-second total window; RFC §5.2 default)
 *
 * The DB stores the TOTP secret as encrypted raw bytes in
 * `admin_users.totp_secret_encrypted`. At login-verify time the bytes are
 * decrypted via [AesGcmCipher] then passed to [verify] which base32-encodes
 * them for the samstevens verifier.
 *
 * A fixed sentinel secret is used by the login handler for timing
 * equalization on the wrong-password path (so the wall time of a
 * password_mismatch matches a totp_mismatch — design.md D9).
 */
object TotpVerifier {
    const val TIME_PERIOD_SECONDS = 30
    const val DIGITS = 6
    const val ALLOWED_DISCREPANCY_STEPS = 1
    val ALGORITHM: HashingAlgorithm = HashingAlgorithm.SHA1

    private const val TOTP_SECRET_LENGTH_BYTES = 20

    private val codeGenerator: CodeGenerator by lazy { DefaultCodeGenerator(ALGORITHM, DIGITS) }

    private val verifier: DefaultCodeVerifier by lazy {
        DefaultCodeVerifier(codeGenerator, SystemTimeProvider()).apply {
            setTimePeriod(TIME_PERIOD_SECONDS)
            setAllowedTimePeriodDiscrepancy(ALLOWED_DISCREPANCY_STEPS)
        }
    }

    private val base32 = Base32()

    /**
     * Verify [code] against the TOTP [secretRawBytes] (typically 20 bytes of
     * SecureRandom-generated entropy decrypted from
     * `admin_users.totp_secret_encrypted`). Returns true on match within the
     * ±1-step skew window, false otherwise.
     *
     * The library handles constant-time comparison internally. A malformed
     * code (non-digit characters, wrong length, leading/trailing whitespace)
     * returns false without throwing.
     */
    fun verify(
        secretRawBytes: ByteArray,
        code: String,
    ): Boolean {
        // Defensive: trim/whitespace handling per spec Req "Login input
        // handling tolerates Unicode and rejects malformed shapes safely"
        // scenario "TOTP code with leading whitespace is rejected" —
        // verifier rejects anything that doesn't match its expected shape.
        // We do NOT strip the whitespace before passing to the verifier
        // (per the spec scenario, the verifier MUST see the original input
        // so leading/trailing whitespace causes a clean false return).
        if (code.length != DIGITS) return false
        if (!code.all { it.isDigit() }) return false
        return verifier.isValidCode(base32.encodeAsString(secretRawBytes), code)
    }

    // ----- Sentinel secret for timing equalization (design.md D9) -----
    //
    // Fixed 20-byte value committed in source — its secrecy is NOT load-
    // bearing (an attacker who guesses it just learns "this is the
    // sentinel"; the response is the no-enumeration error regardless).
    // What IS load-bearing: a verify against the sentinel takes the same
    // wall time as a verify against a production secret.
    private val SENTINEL_SECRET_BYTES: ByteArray =
        byteArrayOf(
            0x0F, 0x1E, 0x2D, 0x3C, 0x4B, 0x5A, 0x69, 0x78,
            0x6E.toByte(), 0x6F.toByte(), 0x73.toByte(), 0x65.toByte(),
            0x6E.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6E.toByte(),
            0x65.toByte(), 0x6C.toByte(), 0x2D.toByte(), 0x00.toByte(),
        ).also { require(it.size == TOTP_SECRET_LENGTH_BYTES) }

    /**
     * Verify [code] against the sentinel TOTP secret for timing equalization
     * on the wrong-password / inactive-admin / missing-secret login paths.
     * Same wall-time profile as a production verify; result is discarded
     * by the caller.
     */
    fun verifyAgainstSentinel(code: String): Boolean = verify(SENTINEL_SECRET_BYTES, code)
}
