package id.nearyou.app.subscription

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Source-code scan for the `subscription-billing-webhook` capability's constant-
 * time auth-comparison requirement (mirrors `AdminAuthConstantTimeScanTest`). The
 * Bearer + HMAC comparisons MUST use `MessageDigest.isEqual`, never `==` /
 * `Arrays.equals` on secret-derived bytes. Not DB-backed — runs at PR time (no
 * `@Tags("database")`). Scanned tree:
 * `backend/ktor/src/main/kotlin/id/nearyou/app/subscription/`.
 */
class RevenueCatWebhookConstantTimeScanTest : StringSpec({

    val srcDir = File("src/main/kotlin/id/nearyou/app/subscription")

    fun kotlinFiles(): List<File> {
        srcDir.exists() shouldBe true
        return srcDir.listFiles { f -> f.isFile && f.extension == "kt" }?.toList() ?: emptyList()
    }

    /** Strip block + line comments so KDoc mentioning `==` etc. is not flagged. */
    fun strippedCode(file: File): String {
        val noBlock = file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return noBlock.lineSequence().map { it.substringBefore("//") }.joinToString("\n")
    }

    "the webhook route uses MessageDigest.isEqual for secret comparison" {
        val route = kotlinFiles().first { it.name == "RevenueCatWebhookRoutes.kt" }
        strippedCode(route).contains("MessageDigest.isEqual") shouldBe true
    }

    "no Arrays.equals in the subscription source" {
        val offenders =
            kotlinFiles().filter { file ->
                val code = strippedCode(file)
                code.contains("Arrays.equals(") || code.contains("import java.util.Arrays")
            }
        offenders.map { it.name }.shouldBeEmpty()
    }

    "no == operator on secret-named locals (bearer/secret/signature/hmac) in the subscription source" {
        val pattern =
            Regex(
                """\b[A-Za-z0-9_]*(bearer|secret|signature|hmac)[A-Za-z0-9_]*\s*==\s*(\w+)""",
                RegexOption.IGNORE_CASE,
            )
        val offenders =
            kotlinFiles().flatMap { file ->
                val code = strippedCode(file)
                pattern.findAll(code)
                    .filter { it.groupValues[2] != "null" } // nullity checks are safe
                    .map { "${file.name}: ${it.value.trim()}" }
                    .toList()
            }
        offenders.shouldBeEmpty()
    }
})
