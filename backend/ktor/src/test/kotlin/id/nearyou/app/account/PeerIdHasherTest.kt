package id.nearyou.app.account

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Unit tests for [PeerIdHasher] (capability `account-data-export`, task 8.13). The peer-id
 * hash is the privacy seam for every reference that leaves the export (follow / block / chat /
 * report counterparty): it MUST be a server-keyed `HMAC-SHA256(secret, peer_id)` — stable for a
 * given (secret, peer), different under a different secret (so it is non-correlatable across
 * exports), and never a raw id nor a bare `SHA256(uuid)`.
 */
class PeerIdHasherTest : StringSpec({

    val secretA = "secret-alpha-AAAAAAAAAAAAAAAAAAAA"
    val secretB = "secret-bravo-BBBBBBBBBBBBBBBBBBBB"

    "determinism: the same (secret, peer) always yields the same hash" {
        val peer = UUID.randomUUID()
        val hasher = PeerIdHasher.fromSecret(secretA)
        val first = hasher.hash(peer)
        val second = PeerIdHasher.fromSecret(secretA).hash(peer)
        first shouldBe second
    }

    "the hash is 64 lowercase hex chars (HMAC-SHA256)" {
        val hash = PeerIdHasher.fromSecret(secretA).hash(UUID.randomUUID())
        hash shouldMatch Regex("^[0-9a-f]{64}$")
    }

    "secrecy: a different secret produces a DIFFERENT hash for the same peer (non-correlatable)" {
        val peer = UUID.randomUUID()
        val underA = PeerIdHasher.fromSecret(secretA).hash(peer)
        val underB = PeerIdHasher.fromSecret(secretB).hash(peer)
        underA shouldNotBe underB
    }

    "distinct peers under the same secret produce distinct hashes" {
        val hasher = PeerIdHasher.fromSecret(secretA)
        hasher.hash(UUID.randomUUID()) shouldNotBe hasher.hash(UUID.randomUUID())
    }

    "the hash is NEVER the raw peer id" {
        val peer = UUID.randomUUID()
        val hash = PeerIdHasher.fromSecret(secretA).hash(peer)
        hash shouldNotBe peer.toString()
        hash.contains(peer.toString()) shouldBe false
    }

    "the hash is NOT a bare SHA256 of the peer id (the keyed-HMAC is the requirement)" {
        val peer = UUID.randomUUID()
        val keyed = PeerIdHasher.fromSecret(secretA).hash(peer)
        val bareSha =
            MessageDigest.getInstance("SHA-256")
                .digest(peer.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        keyed shouldNotBe bareSha
    }

    "the keyed hash matches an independent HMAC-SHA256 computation (oracle)" {
        val peer = UUID.randomUUID()
        val actual = PeerIdHasher.fromSecret(secretA).hash(peer)
        // Independent oracle: HMAC-SHA256(secretA bytes, peer.toString()).
        val mac =
            Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(secretA.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            }
        val expected = mac.doFinal(peer.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        actual shouldBe expected
    }

    "a blank/absent secret falls back to the dev-default (still keyed, deterministic)" {
        val peer = UUID.randomUUID()
        val viaNull = PeerIdHasher.fromSecret(null).hash(peer)
        val viaBlank = PeerIdHasher.fromSecret("   ").hash(peer)
        val viaDefault = PeerIdHasher.fromSecret(PeerIdHasher.DEV_DEFAULT_SECRET).hash(peer)
        viaNull shouldBe viaDefault
        viaBlank shouldBe viaDefault
        // And the dev-default still differs from a real secret (not a no-op / passthrough).
        viaDefault shouldNotBe PeerIdHasher.fromSecret(secretA).hash(peer)
    }
})
