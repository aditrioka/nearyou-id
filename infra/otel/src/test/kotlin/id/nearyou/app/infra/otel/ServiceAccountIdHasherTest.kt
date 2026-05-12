package id.nearyou.app.infra.otel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

/**
 * Coverage for [ServiceAccountIdHasher]. Mirrors `UserIdHasherTest` and
 * `IpHasherTest` structure.
 *
 * Includes a pinned-reference-hash parity test on `hash("test")` to guard
 * against accidental algorithm or input-encoding drift — the spec contract
 * fixes both the digest function (SHA-256) and the input encoding (UTF-8),
 * so any silent change would surface as a parity test failure.
 */
class ServiceAccountIdHasherTest : StringSpec({
    "hash is deterministic" {
        val sub = "105329845711234567890"
        val first = ServiceAccountIdHasher.hash(sub)
        val second = ServiceAccountIdHasher.hash(sub)
        first shouldBe second
    }

    "hash differs between distinct inputs" {
        val a = ServiceAccountIdHasher.hash("105329845711234567890")
        val b = ServiceAccountIdHasher.hash("105329845711234567891")
        (a == b) shouldBe false
    }

    "hash output is exactly 16 lowercase hex chars (regex shape)" {
        val hashed = ServiceAccountIdHasher.hash("105329845711234567890")
        hashed shouldMatch Regex("^[0-9a-f]{16}$")
    }

    "blank input fails fast" {
        shouldThrow<IllegalArgumentException> { ServiceAccountIdHasher.hash("") }
        shouldThrow<IllegalArgumentException> { ServiceAccountIdHasher.hash(" ") }
        shouldThrow<IllegalArgumentException> { ServiceAccountIdHasher.hash("\t\n") }
    }

    "parity check: hash(\"test\") matches externally-computed SHA-256 truncation" {
        // SHA-256("test") = 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
        // First 16 hex chars: 9f86d081884c7d65
        // Pinned to guard against accidental algorithm / encoding drift.
        ServiceAccountIdHasher.hash("test") shouldBe "9f86d081884c7d65"
    }
})
