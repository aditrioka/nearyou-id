package id.nearyou.app.admin.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException

/**
 * Unit tests for [AesGcmCipher] covering the `admin-login-argon2-totp`
 * spec Req "TOTP secret decryption uses AES-256-GCM with key from GCP Secret
 * Manager and admin-UUID AAD binding".
 *
 * Scenarios:
 *  - AAD-bound round-trip succeeds when the admin UUID matches
 *  - AAD-bound decrypt fails when the admin UUID is swapped (row-swap defense)
 *  - Tampered ciphertext + tampered tag both produce AEADBadTagException
 *  - Wrong-length key (AES-128 instead of AES-256) is rejected loudly
 *  - Ciphertext format is BYTEA = nonce || ciphertext || tag (length check)
 */
class AesGcmCipherTest : StringSpec({

    val secureRandom = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    // ----------------- Round-trip + AAD binding -----------------
    "round-trip on a known-secret fixture returns original plaintext" {
        val secret = randomBytes(20) // realistic TOTP secret length
        val key = randomBytes(32)
        val adminUuid = UUID.randomUUID()
        val aad = AesGcmCipher.adminUuidAsBytes(adminUuid)

        val ciphertext = AesGcmCipher.encrypt(secret, key, aad)
        val decrypted = AesGcmCipher.decrypt(ciphertext, key, aad)

        decrypted shouldBe secret
    }

    "AAD-bound decrypt FAILS when the admin UUID is swapped" {
        val secret = randomBytes(20)
        val key = randomBytes(32)
        val originalUuid = UUID.randomUUID()
        val swappedUuid = UUID.randomUUID()

        val ciphertext = AesGcmCipher.encrypt(secret, key, AesGcmCipher.adminUuidAsBytes(originalUuid))

        shouldThrow<AEADBadTagException> {
            AesGcmCipher.decrypt(ciphertext, key, AesGcmCipher.adminUuidAsBytes(swappedUuid))
        }
    }

    // ----------------- Tamper detection -----------------
    "tampered auth tag (last 16 bytes) throws AEADBadTagException" {
        val secret = randomBytes(20)
        val key = randomBytes(32)
        val aad = AesGcmCipher.adminUuidAsBytes(UUID.randomUUID())

        val ciphertext = AesGcmCipher.encrypt(secret, key, aad)
        // Flip one byte in the auth tag region (last 16 bytes).
        val tampered = ciphertext.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        shouldThrow<AEADBadTagException> {
            AesGcmCipher.decrypt(tampered, key, aad)
        }
    }

    "tampered ciphertext (middle byte) throws AEADBadTagException" {
        val secret = randomBytes(20)
        val key = randomBytes(32)
        val aad = AesGcmCipher.adminUuidAsBytes(UUID.randomUUID())

        val ciphertext = AesGcmCipher.encrypt(secret, key, aad)
        // Flip one byte inside the ciphertext region (not nonce, not tag).
        val tampered = ciphertext.copyOf()
        // Index 12 = first ciphertext byte (after the 12-byte nonce).
        tampered[12] = (tampered[12].toInt() xor 0x01).toByte()

        shouldThrow<AEADBadTagException> {
            AesGcmCipher.decrypt(tampered, key, aad)
        }
    }

    "tampered nonce (first 12 bytes) throws AEADBadTagException" {
        val secret = randomBytes(20)
        val key = randomBytes(32)
        val aad = AesGcmCipher.adminUuidAsBytes(UUID.randomUUID())

        val ciphertext = AesGcmCipher.encrypt(secret, key, aad)
        val tampered = ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        shouldThrow<AEADBadTagException> {
            AesGcmCipher.decrypt(tampered, key, aad)
        }
    }

    // ----------------- Wrong-length key -----------------
    "wrong-length key (AES-128 instead of AES-256) is rejected loudly on encrypt" {
        val secret = randomBytes(20)
        val aes128Key = randomBytes(16) // wrong length
        val aad = AesGcmCipher.adminUuidAsBytes(UUID.randomUUID())

        shouldThrow<IllegalArgumentException> {
            AesGcmCipher.encrypt(secret, aes128Key, aad)
        }
    }

    "wrong-length key (AES-128 instead of AES-256) is rejected loudly on decrypt" {
        val secret = randomBytes(20)
        val realKey = randomBytes(32)
        val aad = AesGcmCipher.adminUuidAsBytes(UUID.randomUUID())
        val ciphertext = AesGcmCipher.encrypt(secret, realKey, aad)

        val aes128Key = randomBytes(16)
        shouldThrow<IllegalArgumentException> {
            AesGcmCipher.decrypt(ciphertext, aes128Key, aad)
        }
    }

    // ----------------- Format invariants -----------------
    "ciphertext length is exactly nonce + plaintext + tag" {
        val secret = randomBytes(20)
        val key = randomBytes(32)
        val aad = AesGcmCipher.adminUuidAsBytes(UUID.randomUUID())

        val ciphertext = AesGcmCipher.encrypt(secret, key, aad)
        // 12 (nonce) + 20 (plaintext) + 16 (tag) = 48
        ciphertext.size shouldBe 48
    }

    "decrypt rejects ciphertext that is too short to contain nonce + tag" {
        val key = randomBytes(32)
        val aad = AesGcmCipher.adminUuidAsBytes(UUID.randomUUID())
        val tooShort = randomBytes(27) // need >= 28

        shouldThrow<IllegalArgumentException> {
            AesGcmCipher.decrypt(tooShort, key, aad)
        }
    }

    "adminUuidAsBytes produces exactly 16 bytes for any UUID" {
        repeat(10) {
            val uuid = UUID.randomUUID()
            AesGcmCipher.adminUuidAsBytes(uuid).size shouldBe 16
        }
    }

    "adminUuidAsBytes is deterministic for the same UUID" {
        val uuid = UUID.randomUUID()
        AesGcmCipher.adminUuidAsBytes(uuid) shouldBe AesGcmCipher.adminUuidAsBytes(uuid)
    }
})
