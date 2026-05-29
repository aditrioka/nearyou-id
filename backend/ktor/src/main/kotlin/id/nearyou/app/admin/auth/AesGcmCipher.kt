package id.nearyou.app.admin.auth

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encrypt/decrypt for `admin_users.totp_secret_encrypted`.
 *
 * Per `admin-login-argon2-totp` design.md D1 + spec Req "TOTP secret
 * decryption uses AES-256-GCM with key from GCP Secret Manager and admin-UUID
 * AAD binding":
 *
 *   ciphertext format: BYTEA = nonce (12 bytes) || ciphertext || auth_tag (16 bytes)
 *
 * The admin's UUID (16 raw bytes) is bound as Additional Authenticated Data
 * via [Cipher.updateAAD] before [Cipher.doFinal]. Decryption with the wrong
 * AAD (e.g. another admin's UUID — a row-swap attack) fails with
 * `AEADBadTagException`. The key is sourced via `secretKey(env, name)` per
 * the project's secret-helper convention; no constant is hardcoded outside
 * test fixtures.
 *
 * JCA built-in (`AES/GCM/NoPadding`) — no third-party dependency. JDK 11+
 * supports AES-GCM natively; the project's pinned JDK is 21.
 */
object AesGcmCipher {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val UUID_BYTES_LENGTH = 16
    private const val AES_256_KEY_LENGTH_BYTES = 32

    private val secureRandom = SecureRandom()

    /**
     * Encrypt [plaintext] under [key] with [aad] bound. Returns a fresh
     * BYTEA of length `12 + plaintext.size + 16` (nonce || ciphertext || tag).
     *
     * Throws [IllegalArgumentException] if the key length is not 32 bytes
     * (AES-256). Caller should source [key] via the `secretKey(env, name)`
     * helper, never from a hardcoded constant.
     */
    fun encrypt(
        plaintext: ByteArray,
        key: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == AES_256_KEY_LENGTH_BYTES) {
            "AES-256 key must be $AES_256_KEY_LENGTH_BYTES bytes; got ${key.size}"
        }
        val nonce = ByteArray(NONCE_LENGTH_BYTES)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, ALGORITHM),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
        )
        cipher.updateAAD(aad)
        val ciphertextAndTag = cipher.doFinal(plaintext)

        return ByteBuffer
            .allocate(NONCE_LENGTH_BYTES + ciphertextAndTag.size)
            .put(nonce)
            .put(ciphertextAndTag)
            .array()
    }

    /**
     * Decrypt the [ciphertext] BYTEA (nonce-prepended format from [encrypt])
     * under [key] with [aad] bound. Returns the original plaintext.
     *
     * Throws:
     *  - `javax.crypto.AEADBadTagException` if the auth tag doesn't verify
     *    (tampered ciphertext, wrong AAD, wrong key)
     *  - `IllegalArgumentException` if the key length is wrong or the
     *    ciphertext is too short to contain a nonce + tag
     *  - `java.security.InvalidKeyException` if the JCA rejects the key
     *    material (e.g. zero-byte key)
     */
    fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == AES_256_KEY_LENGTH_BYTES) {
            "AES-256 key must be $AES_256_KEY_LENGTH_BYTES bytes; got ${key.size}"
        }
        // Auth tag is GCM_TAG_LENGTH_BITS / 8 = 16 bytes. The minimum payload
        // is 12-byte nonce + 16-byte tag = 28 bytes (zero plaintext).
        require(ciphertext.size >= NONCE_LENGTH_BYTES + GCM_TAG_LENGTH_BITS / 8) {
            "ciphertext too short: ${ciphertext.size} bytes (need >= 28)"
        }

        val nonce = ciphertext.copyOfRange(0, NONCE_LENGTH_BYTES)
        val ciphertextAndTag = ciphertext.copyOfRange(NONCE_LENGTH_BYTES, ciphertext.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, ALGORITHM),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextAndTag)
    }

    /**
     * Render a [UUID] as its canonical 16-byte big-endian representation
     * for use as AAD in [encrypt] / [decrypt]. 8 bytes for the most-
     * significant longs followed by 8 bytes for the least-significant longs.
     */
    fun adminUuidAsBytes(uuid: UUID): ByteArray =
        ByteBuffer
            .allocate(UUID_BYTES_LENGTH)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
}
