package id.nearyou.app.admin.auth

import com.password4j.types.Argon2
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

/**
 * Fast unit tests for [PasswordHasher] covering the `admin-login-argon2-totp`
 * spec Req "Argon2id verification uses tuned parameters with documented
 * benchmark" (parameter-floor assertions + sentinel-params discipline) +
 * Req "Login POST returns no-enumeration response on every failure path"
 * scenario "Sentinel hash is a valid Argon2id hash with current parameters".
 *
 * **Wall-time benchmark scenarios are intentionally NOT here** — they live
 * in [PasswordHasherBenchmarkTest] tagged `@Tag("benchmark")` so PR-time CI
 * skips them (mitigates CI flakiness per `admin-login-argon2-totp` design.md
 * D2 + tasks.md 11.5 + 11.17).
 */
class PasswordHasherTest : StringSpec({

    // ----------------- Parameter floor (OWASP) -----------------
    "configured Memory meets OWASP minimum (>= 15 MiB)" {
        (PasswordHasher.MEMORY_KIB >= PasswordHasher.OWASP_MIN_MEMORY_KIB) shouldBe true
    }

    "configured Iterations meets OWASP minimum (>= 2)" {
        (PasswordHasher.ITERATIONS >= PasswordHasher.OWASP_MIN_ITERATIONS) shouldBe true
    }

    "configured Parallelism is 1 per design.md D2" {
        PasswordHasher.PARALLELISM shouldBe 1
    }

    "configured hash output is 32 bytes per OWASP recommendation" {
        PasswordHasher.HASH_OUTPUT_BYTES shouldBe 32
    }

    "argon2Function uses Argon2id type" {
        PasswordHasher.argon2Function.variant shouldBe Argon2.ID
    }

    // ----------------- Hash / verify round-trip -----------------
    "verify of a freshly-hashed plaintext returns TRUE" {
        val hash = PasswordHasher.hash("correct horse battery staple")
        PasswordHasher.verify("correct horse battery staple", hash) shouldBe true
    }

    "verify of a different plaintext against the same hash returns FALSE" {
        val hash = PasswordHasher.hash("correct horse battery staple")
        PasswordHasher.verify("wrong password", hash) shouldBe false
    }

    "hash output format starts with the Argon2id type marker" {
        val hash = PasswordHasher.hash("any-plaintext")
        // Password4j's PHC format: `$argon2id$v=...$m=...,t=...,p=...$<salt>$<hash>`
        hash shouldStartWith "\$argon2id\$"
    }

    "hash of the same plaintext twice produces different hashes (random salt)" {
        val a = PasswordHasher.hash("identical-plaintext")
        val b = PasswordHasher.hash("identical-plaintext")
        a shouldNotBe b
    }

    // ----------------- Sentinel hash (D9 + D15) -----------------
    "sentinelHash is a valid Argon2id hash that PasswordHasher.verify can parse" {
        // Verify against ANY plaintext should NOT throw — it returns false
        // for almost any input (a hit would require guessing the sentinel
        // plaintext, but that's not a load-bearing secret).
        val result = PasswordHasher.verify("any-non-sentinel-input", PasswordHasher.sentinelHash)
        result shouldBe false
    }

    "sentinelHash is deterministic (lazy init) — repeated accesses return the same string" {
        val first = PasswordHasher.sentinelHash
        val second = PasswordHasher.sentinelHash
        first shouldBe second
    }

    "sentinelHash starts with the Argon2id type marker" {
        PasswordHasher.sentinelHash shouldStartWith "\$argon2id\$"
    }

    "sentinelHash embeds the same memory + iteration + parallelism params as production hashes" {
        // D15: sentinel-params == production-params, asserted at CI time.
        // Password4j's hash format embeds `m=<memory>,t=<iter>,p=<parallelism>`
        // — parse both the sentinel hash and a freshly-hashed production
        // value and assert the trio matches.
        val productionHash = PasswordHasher.hash("production-comparator")
        val productionParams = extractArgon2Params(productionHash)
        val sentinelParams = extractArgon2Params(PasswordHasher.sentinelHash)
        sentinelParams shouldBe productionParams
        // Also assert the params trio matches the constants exactly.
        sentinelParams shouldBe
            Argon2Params(
                memoryKib = PasswordHasher.MEMORY_KIB,
                iterations = PasswordHasher.ITERATIONS,
                parallelism = PasswordHasher.PARALLELISM,
            )
    }

    "verifyAgainstSentinel returns FALSE for any obvious non-sentinel input" {
        PasswordHasher.verifyAgainstSentinel("not the sentinel") shouldBe false
        PasswordHasher.verifyAgainstSentinel("") shouldBe false
    }
})

/**
 * Parsed Argon2 PHC params for the `m=...,t=...,p=...` segment.
 */
private data class Argon2Params(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
)

/**
 * Extract (m, t, p) from a Password4j Argon2id PHC hash string of the
 * shape `$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>`.
 */
private fun extractArgon2Params(phc: String): Argon2Params {
    // Use `[$]` (character class with literal `$`) rather than `\$` because
    // Kotlin raw strings still interpret `$` as the template-interpolation
    // prefix, so `\$m=...` would be parsed as `\` literal + template-ref to `m`.
    val regex = Regex("""[$]m=(\d+),t=(\d+),p=(\d+)[$]""")
    val match =
        regex.find(phc)
            ?: error("hash does not contain a valid Argon2 params segment: $phc")
    val groups = match.groupValues
    return Argon2Params(
        memoryKib = groups[1].toInt(),
        iterations = groups[2].toInt(),
        parallelism = groups[3].toInt(),
    )
}
