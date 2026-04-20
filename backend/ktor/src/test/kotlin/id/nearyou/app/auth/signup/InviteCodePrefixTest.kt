package id.nearyou.app.auth.signup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.util.UUID

class InviteCodePrefixTest : StringSpec({
    val secret = "test-invite-code-secret-bytes-32b".toByteArray()
    val deriver = InviteCodePrefixDeriver(secret)

    "same user_id + secret → same 8-char prefix (deterministic)" {
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val a = deriver.derive(id)
        val b = deriver.derive(id)
        a shouldBe b
        a.length shouldBe 8
    }

    "different user_ids → different prefixes with overwhelming probability" {
        val a = deriver.derive(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        val b = deriver.derive(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        a shouldNotBe b
    }

    "prefix matches lowercase base32 charset [a-z2-7]" {
        val prefix = deriver.derive(UUID.randomUUID())
        prefix.shouldMatch(Regex("^[a-z2-7]{8}$"))
    }

    "different secret → different prefix for same user_id" {
        val d2 = InviteCodePrefixDeriver("different-invite-secret-key-32byt".toByteArray())
        val id = UUID.fromString("33333333-3333-3333-3333-333333333333")
        deriver.derive(id) shouldNotBe d2.derive(id)
    }

    "10-char fallback extends the 8-char prefix contiguously" {
        val id = UUID.randomUUID()
        val eight = deriver.derive(id, 8)
        val ten = deriver.derive(id, 10)
        // The first 8 chars of the 10-char prefix are exactly the 8-char prefix
        // because both are truncations of the same digest-driven base32 stream.
        ten.substring(0, 8) shouldBe eight
        ten.length shouldBe 10
    }

    "deriveWithRetry: happy path returns 8-char" {
        val id = UUID.randomUUID()
        val result = deriver.deriveWithRetry(id) { false }
        result.length shouldBe 8
        result shouldBe deriver.derive(id, 8)
    }

    "deriveWithRetry: 8-char taken → falls back to 10-char" {
        val id = UUID.randomUUID()
        val eight = deriver.derive(id, 8)
        val result = deriver.deriveWithRetry(id) { candidate -> candidate == eight }
        result.length shouldBe 10
        result shouldNotBe eight
    }
})
