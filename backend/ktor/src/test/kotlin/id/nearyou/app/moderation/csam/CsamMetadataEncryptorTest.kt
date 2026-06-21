package id.nearyou.app.moderation.csam

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import javax.crypto.AEADBadTagException

/**
 * Pure-unit coverage for [CsamMetadataEncryptor] (task 7.10 unit part) — the
 * fail-soft AES-256-GCM wrapper over the `csam_detection_archive.encrypted_metadata`
 * blob. No DB; the encryptor is a thin AAD-binding façade over [AesGcmCipher].
 *
 * Asserts the three load-bearing contracts:
 *  - round-trip: encrypt → decrypt under the SAME 32-byte key + image_hash AAD yields
 *    the original plaintext byte-for-byte.
 *  - fail-soft (design D4): a `null`-returning key provider makes [encrypt] return
 *    `null` (never throws) — the takedown still proceeds with a NULL blob.
 *  - row-swap guard: decrypting with a DIFFERENT `imageHash` (a relocated ciphertext)
 *    fails the GCM tag with [AEADBadTagException].
 */
class CsamMetadataEncryptorTest : StringSpec({

    // A fixed 32-byte AES-256 key. NOT a real secret — test-only.
    val fixedKey = ByteArray(32) { (it + 7).toByte() }
    val imageHash = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    val plaintext = """{"uploader_user_id":"a1b2c3d4-0000-0000-0000-000000000001"}""".toByteArray(Charsets.UTF_8)

    "7.10 round-trip — encrypt then decrypt under the same key + image_hash AAD yields the original plaintext" {
        val encryptor = CsamMetadataEncryptor { fixedKey }

        val ciphertext = encryptor.encrypt(plaintext, imageHash)
        ciphertext.shouldNotBeNull()
        // GCM ciphertext is nonce(12) || ct || tag(16); never equals the plaintext.
        ciphertext shouldNotBe plaintext

        val roundTripped = encryptor.decrypt(ciphertext, imageHash)
        roundTripped.toList() shouldBe plaintext.toList()
    }

    "7.10 fail-soft — a null-returning key provider makes encrypt return null (degraded, no throw)" {
        val encryptor = CsamMetadataEncryptor { null }

        encryptor.encrypt(plaintext, imageHash) shouldBe null
    }

    "7.10 fail-soft — a PROVISIONED-but-malformed key (wrong length) makes encrypt degrade to null, NOT throw" {
        // A crypto misconfig (e.g. a 16-byte key from bad Base64) MUST NOT block the
        // safety-critical takedown: AesGcmCipher's require(key.size == 32) throws, and the
        // encryptor catches it → null blob. The takedown proceeds; only the blob degrades.
        val encryptor = CsamMetadataEncryptor { ByteArray(16) { it.toByte() } }

        encryptor.encrypt(plaintext, imageHash) shouldBe null
    }

    "7.10 fail-soft — decrypt throws when the key slot is unprovisioned (admin read path requires the key)" {
        val encryptor = CsamMetadataEncryptor { null }
        // Construct a valid ciphertext with a provisioned key first.
        val ciphertext = CsamMetadataEncryptor { fixedKey }.encrypt(plaintext, imageHash)
        ciphertext.shouldNotBeNull()

        // Same blob, but a key-less encryptor cannot decrypt — explicit error, not a null.
        shouldThrow<IllegalStateException> {
            encryptor.decrypt(ciphertext, imageHash)
        }
    }

    "7.10 row-swap guard — decrypt with a different image_hash AAD fails the GCM tag (AEADBadTagException)" {
        val encryptor = CsamMetadataEncryptor { fixedKey }
        val ciphertext = encryptor.encrypt(plaintext, imageHash)
        ciphertext.shouldNotBeNull()

        // A ciphertext relocated to a row whose image_hash differs must not decrypt —
        // the image_hash is bound as AAD, mirroring the admin-UUID AAD binding.
        shouldThrow<AEADBadTagException> {
            encryptor.decrypt(ciphertext, imageHash + "-tampered")
        }
    }
})
