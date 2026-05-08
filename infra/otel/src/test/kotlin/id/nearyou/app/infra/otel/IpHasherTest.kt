package id.nearyou.app.infra.otel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlin.random.Random

/**
 * Coverage for [IpHasher]. Mirrors `UserIdHasherTest` structure.
 *
 * No collision-rate test by design (see `design.md` D7) — the 64-bit
 * collision probability over IPv4 space is below noise; a probabilistic
 * test would add nothing.
 *
 * All expected-hash values are computed dynamically via `IpHasher.hash(...)`
 * (never pinned as literal hex) so a future digest-function change is a
 * single-source edit.
 */
class IpHasherTest : StringSpec({
    "hash is deterministic" {
        val ip = "1.2.3.4"
        val first = IpHasher.hash(ip)
        val second = IpHasher.hash(ip)
        first shouldBe second
    }

    "hash differs between distinct IPs" {
        val a = IpHasher.hash("1.2.3.4")
        val b = IpHasher.hash("5.6.7.8")
        (a == b) shouldBe false
    }

    "hash output is exactly 16 hex characters (regex shape)" {
        val hashed = IpHasher.hash("203.0.113.42")
        hashed shouldMatch Regex("^[0-9a-f]{16}$")
    }

    "hash output is exactly 16 hex characters across 1000 random IPv4 addresses" {
        // Defense against an off-by-one in the hex assembly: a small typo
        // would produce 14 or 18 chars on rare digests. Exercise 1000 random
        // inputs (1000 vs UserIdHasherTest's 100 — IPv4 input space is tiny
        // enough that a higher count is cheap and tightens the safety net).
        val rng = Random(42)
        repeat(1000) {
            val ip =
                "${rng.nextInt(0, 256)}.${rng.nextInt(0, 256)}.${rng.nextInt(0, 256)}.${rng.nextInt(0, 256)}"
            val hashed = IpHasher.hash(ip)
            hashed.length shouldBe 16
            hashed shouldMatch Regex("^[0-9a-f]{16}$")
        }
    }

    "IPv6 input produces 16-hex output" {
        val hashed = IpHasher.hash("2001:db8::1")
        hashed shouldMatch Regex("^[0-9a-f]{16}$")
    }

    "blank input fails fast" {
        shouldThrow<IllegalArgumentException> { IpHasher.hash("") }
        shouldThrow<IllegalArgumentException> { IpHasher.hash(" ") }
        shouldThrow<IllegalArgumentException> { IpHasher.hash("\t") }
    }

    "whitespace is not trimmed" {
        val a = IpHasher.hash("1.2.3.4")
        val b = IpHasher.hash("1.2.3.4 ")
        (a == b) shouldBe false
    }

    "IPv6 case sensitivity is preserved" {
        val upper = IpHasher.hash("2001:DB8::1")
        val lower = IpHasher.hash("2001:db8::1")
        (upper == lower) shouldBe false
    }
})
