package id.nearyou.app.moderation.csam

import id.nearyou.app.admin.auth.AesGcmCipher

/**
 * Fail-soft AES-256-GCM encryptor for `csam_detection_archive.encrypted_metadata`
 * (`csam-detection` capability).
 *
 * Reuses the existing [AesGcmCipher] (the `admin-login-argon2-totp` TOTP-secret
 * precedent) — JCA `AES/GCM/NoPadding`, ciphertext = nonce(12) || ct || tag(16).
 * The 32-byte key is sourced by [keyProvider], which wraps
 * `secrets.resolve(secretKey(env, "csam-archive-aes-key"))` + Base64 decode and
 * returns `null` when the slot is unprovisioned.
 *
 * **Fail-soft contract (design D4):** when the key is unset — the pre-launch /
 * staging state, since the slot is operator-provisioned at the Month-6 image launch —
 * [encrypt] returns `null` instead of throwing. The caller (the takedown service)
 * still writes the archive row with its plaintext Kominfo essentials and a NULL
 * `encrypted_metadata`; the safety-critical tombstone/ban/cascade are NEVER blocked
 * on key availability. A launch-readiness check provisions the key before
 * `image_upload_enabled` flips TRUE.
 *
 * The match's `image_hash` (the stable archive dedup key) is bound as AAD, so a
 * ciphertext relocated to a different archive row fails decryption (row-swap guard),
 * mirroring the admin-UUID AAD binding.
 */
class CsamMetadataEncryptor(
    private val keyProvider: () -> ByteArray?,
) {
    /**
     * Encrypt [plaintext] bound to [imageHash] (AAD). Returns the ciphertext, or
     * `null` when the key slot is unprovisioned (degraded — see fail-soft contract).
     */
    fun encrypt(
        plaintext: ByteArray,
        imageHash: String,
    ): ByteArray? {
        val key = keyProvider() ?: return null
        return AesGcmCipher.encrypt(plaintext, key, imageHash.toByteArray(Charsets.UTF_8))
    }

    /**
     * Decrypt [ciphertext] bound to [imageHash] (AAD). Used by round-trip tests here
     * and the deferred admin decrypt-read surface. Throws when the key is unset (the
     * admin read path requires a provisioned key) or the tag/AAD does not verify.
     */
    fun decrypt(
        ciphertext: ByteArray,
        imageHash: String,
    ): ByteArray {
        val key = keyProvider() ?: error("csam-archive-aes-key is not provisioned; cannot decrypt")
        return AesGcmCipher.decrypt(ciphertext, key, imageHash.toByteArray(Charsets.UTF_8))
    }
}
