package id.nearyou.app.account

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Server-keyed peer-id hashing for the data-export scope matrix (design D5).
 *
 * Every peer reference that leaves the export (follow / block / chat / report
 * counterparty) is `HMAC-SHA256(export-peer-hash-secret, peer_id)` rendered as
 * lowercase hex — NOT a bare `SHA256(uuid)`. A bare digest of an enumerable UUID
 * is brute-forceable AND identical across every user's export, which would let two
 * colluding users confirm a shared counterparty; the keyed HMAC makes peer
 * references non-reversible and non-correlatable across exports.
 *
 * The secret is resolved via the `secretKey(env, "export-peer-hash-secret")`
 * convention (the invite-code-HMAC precedent — see [InviteCodePrefixDeriver]).
 * A dev/test default ([DEV_DEFAULT_SECRET]) keeps offline unit gather working when
 * the slot is un-provisioned; staging/prod resolve a real secret.
 */
class PeerIdHasher(
    secretBytes: ByteArray,
) {
    private val key: SecretKeySpec = SecretKeySpec(secretBytes, HMAC_SHA256)

    /** Lowercase-hex `HMAC-SHA256(secret, peerId.toString())`. Stable for a given (secret, peer). */
    fun hash(peerId: UUID): String {
        val mac = Mac.getInstance(HMAC_SHA256).apply { init(key) }
        val digest = mac.doFinal(peerId.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val HMAC_SHA256 = "HmacSHA256"

        /**
         * Dev/test fallback key material so the gather runs offline when the
         * `export-peer-hash-secret` slot is unset. Non-sensitive (it never guards a
         * real export — staging/prod resolve a provisioned secret); its only job is to
         * make the keyed-HMAC deterministic in unit tests.
         */
        const val DEV_DEFAULT_SECRET = "nearyou-dev-export-peer-hash-secret"

        /** Builds a hasher from a resolved secret string, or the dev default when blank/absent. */
        fun fromSecret(secret: String?): PeerIdHasher {
            val material = secret?.takeIf { it.isNotBlank() } ?: DEV_DEFAULT_SECRET
            return PeerIdHasher(material.toByteArray(Charsets.UTF_8))
        }
    }
}
