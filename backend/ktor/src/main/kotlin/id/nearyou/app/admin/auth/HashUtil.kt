package id.nearyou.app.admin.auth

import java.security.MessageDigest
import java.util.HexFormat

/**
 * Hash utilities for the admin auth flow.
 *
 * SHA-256 in hex is the canonical at-rest format for `admin_sessions
 * .session_token_hash` + `.csrf_token_hash` (TEXT NOT NULL per V16 schema).
 * Constant-time comparison happens on the raw 32-byte digest via
 * [MessageDigest.isEqual] — never via `String.equals(...)` on the hex
 * encoding (which is not guaranteed constant-time).
 */
object HashUtil {
    private val HEX_FORMAT = HexFormat.of() // lowercase, no separator

    /**
     * SHA-256(plaintext) → lowercase hex string (64 chars). Used at INSERT
     * time when writing `session_token_hash` / `csrf_token_hash`.
     */
    fun sha256Hex(plaintext: String): String = HEX_FORMAT.formatHex(sha256(plaintext))

    /**
     * SHA-256(plaintext) → 32 raw bytes.
     */
    fun sha256(plaintext: String): ByteArray = sha256(plaintext.toByteArray(Charsets.UTF_8))

    fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    /**
     * Constant-time compare two SHA-256 hex strings.
     *
     * Per `admin-login-argon2-totp` spec Req "All admin auth comparisons
     * are constant-time": converts both to raw bytes and delegates to
     * [MessageDigest.isEqual] (documented constant-time in JDK 17+).
     * Returns false if either string is not a valid 64-char hex digest.
     */
    fun constantTimeEqualHex(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != HEX_DIGEST_LENGTH || b.length != HEX_DIGEST_LENGTH) return false
        val aBytes =
            try {
                HEX_FORMAT.parseHex(a)
            } catch (_: IllegalArgumentException) {
                return false
            }
        val bBytes =
            try {
                HEX_FORMAT.parseHex(b)
            } catch (_: IllegalArgumentException) {
                return false
            }
        return MessageDigest.isEqual(aBytes, bBytes)
    }

    private const val HEX_DIGEST_LENGTH = 64 // SHA-256 = 32 bytes = 64 hex chars
}
