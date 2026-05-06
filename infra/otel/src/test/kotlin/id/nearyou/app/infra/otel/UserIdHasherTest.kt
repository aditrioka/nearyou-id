package id.nearyou.app.infra.otel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.util.UUID

class UserIdHasherTest : StringSpec({
    "hash is deterministic" {
        val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val first = UserIdHasher.hash(uuid)
        val second = UserIdHasher.hash(uuid)
        first shouldBe second
    }

    "hash differs between distinct UUIDs" {
        val a = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val b = UUID.fromString("11111111-2222-3333-4444-555555555556")
        val ha = UserIdHasher.hash(a)
        val hb = UserIdHasher.hash(b)
        (ha == hb) shouldBe false
    }

    "hash output is exactly 16 hex characters (regex shape)" {
        val uuid = UUID.randomUUID()
        val hashed = UserIdHasher.hash(uuid)
        hashed shouldMatch Regex("^[0-9a-f]{16}$")
    }

    "hash output is exactly 16 hex characters across many random UUIDs" {
        // Defense against an off-by-one in the hex assembly: a small typo would
        // produce 14 or 18 chars on rare digests. Exercise 100 random inputs.
        repeat(100) {
            val hashed = UserIdHasher.hash(UUID.randomUUID())
            hashed.length shouldBe 16
            hashed shouldMatch Regex("^[0-9a-f]{16}$")
        }
    }
})
