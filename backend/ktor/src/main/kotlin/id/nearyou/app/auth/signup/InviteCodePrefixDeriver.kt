package id.nearyou.app.auth.signup

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic invite-code-prefix derivation for `users.invite_code_prefix`.
 *
 * Formula (per docs/05-Implementation.md § Users Schema):
 *   base32lower(HMAC-SHA256(invite-code-secret, user_id.toString().toByteArray()))[0..N]
 *
 * The 8-char default hits the 8-char `VARCHAR(8)` UNIQUE column. On the
 * astronomical collision (~10^-4 per new user at 100k users), the signup
 * flow calls [deriveWithRetry] which lengthens to a 10-char prefix via
 * a SHA-256 expansion trick; because the underlying column is still 8 chars
 * in V2, the 10-char path today surfaces as a schema incompatibility — the
 * column-width bump lands with the staging-bootstrap change if ever needed.
 */
class InviteCodePrefixDeriver(
    secretBytes: ByteArray,
) {
    private val key: SecretKeySpec = SecretKeySpec(secretBytes, "HmacSHA256")

    fun derive(
        userId: UUID,
        length: Int = DEFAULT_LENGTH,
    ): String {
        require(length in 1..26) { "prefix length must be 1..26" }
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        val digest = mac.doFinal(userId.toString().toByteArray(Charsets.UTF_8))
        return base32LowerNoPadding(digest).take(length)
    }

    /**
     * Returns the first prefix [existsCheck] does not report as taken.
     * Tries the 8-char prefix first; on reported collision, falls back to
     * the 10-char prefix derived from the same HMAC digest (overlapping bytes).
     *
     * [existsCheck] is called with the candidate prefix; return true if taken.
     */
    fun deriveWithRetry(
        userId: UUID,
        existsCheck: (String) -> Boolean,
    ): String {
        val eight = derive(userId, DEFAULT_LENGTH)
        if (!existsCheck(eight)) return eight
        val ten = derive(userId, FALLBACK_LENGTH)
        if (!existsCheck(ten)) return ten
        error(
            "invite-code-prefix collision on both 8-char and 10-char derivation for user $userId; " +
                "this is operationally implausible and indicates a secret or userId reuse bug.",
        )
    }

    companion object {
        const val DEFAULT_LENGTH = 8
        const val FALLBACK_LENGTH = 10
        private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

        private fun base32LowerNoPadding(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 8 / 5 + 1)
            var buffer = 0
            var bitsInBuffer = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                bitsInBuffer += 8
                while (bitsInBuffer >= 5) {
                    val idx = (buffer shr (bitsInBuffer - 5)) and 0x1F
                    out.append(BASE32_ALPHABET[idx])
                    bitsInBuffer -= 5
                }
            }
            if (bitsInBuffer > 0) {
                val idx = (buffer shl (5 - bitsInBuffer)) and 0x1F
                out.append(BASE32_ALPHABET[idx])
            }
            return out.toString()
        }
    }
}
