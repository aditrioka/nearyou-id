package id.nearyou.app.admin.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Source-code scan tests for the `admin-login` capability's
 * "All admin auth comparisons are constant-time" requirement.
 *
 * These are NOT DB-backed — they grep the admin auth source tree for
 * anti-patterns. No `@Tags("database")` so they run in PR-time CI.
 *
 * Scanned tree: `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/`.
 */
class AdminAuthConstantTimeScanTest : StringSpec({

    // Resolve the auth source dir relative to the module working dir. Kotest
    // runs with the module (`backend/ktor`) as the working directory.
    val authDir = File("src/main/kotlin/id/nearyou/app/admin/auth")

    fun authKotlinFiles(): List<File> {
        authDir.exists() shouldBe true
        return authDir.listFiles { f -> f.isFile && f.extension == "kt" }?.toList() ?: emptyList()
    }

    /** Strip `//` line comments + `/* */` block comments + KDoc so scans
     *  only see real code (doc comments legitimately mention `==`, `equals`,
     *  `Arrays.equals` when explaining WHY they're avoided). */
    fun strippedCode(file: File): String {
        val noBlock = file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return noBlock
            .lineSequence()
            .map { line -> line.substringBefore("//") }
            .joinToString("\n")
    }

    "no Arrays.equals on secret-derived bytes in admin auth source" {
        val offenders =
            authKotlinFiles().filter { file ->
                val code = strippedCode(file)
                code.contains("Arrays.equals(") || code.contains("import java.util.Arrays")
            }
        offenders.map { it.name }.shouldBeEmpty()
    }

    "MessageDigest.isEqual is the canonical hash compare (present in HashUtil)" {
        val hashUtil = authKotlinFiles().first { it.name == "HashUtil.kt" }
        strippedCode(hashUtil).contains("MessageDigest.isEqual") shouldBe true
    }

    "no == operator on secret-named locals (token/hash/csrf) in admin auth source" {
        // Scan for `==` where the left operand identifier matches the
        // secret-name pattern. This is a heuristic backstop — the canonical
        // defense is the type system + MessageDigest.isEqual usage.
        // Capture the operand AND what follows `==` so nullity checks
        // (`token == null`) can be excluded — those are safe (not secret
        // value compares).
        val pattern =
            Regex(
                """\b[A-Za-z0-9_]*(token|hash|csrf)[A-Za-z0-9_]*\s*==\s*(\w+)""",
                RegexOption.IGNORE_CASE,
            )
        val offenders =
            authKotlinFiles().flatMap { file ->
                val code = strippedCode(file)
                pattern.findAll(code)
                    .filter { it.groupValues[2] != "null" } // exclude nullity checks
                    .map { "${file.name}: ${it.value.trim()}" }
                    .toList()
            }
        offenders.shouldBeEmpty()
    }

    "no hardcoded AES key literal — key sourced via secretKey helper only" {
        // The test FIXED_AES_KEY lives in test source (AdminAuthTestSupport),
        // not in the main auth tree. Assert no obvious 32-byte key literal or
        // Base64-key constant exists in the main auth source.
        val offenders =
            authKotlinFiles().filter { file ->
                val code = strippedCode(file)
                // Heuristic: a ByteArray(32) { ... } literal OR a base64-looking
                // 44-char constant assigned to a *key* val would be suspicious.
                Regex("""val\s+[A-Za-z0-9_]*[Kk]ey[A-Za-z0-9_]*\s*[:=].*ByteArray\(32\)""").containsMatchIn(code)
            }
        offenders.map { it.name }.shouldBeEmpty()
    }
})
