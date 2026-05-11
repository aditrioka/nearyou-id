package id.nearyou.lint.detekt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Static-source scan asserting that the OpenAI Moderation API endpoint URL
 * (`api.openai.com/v1/moderations`) is fully encapsulated inside
 * `:infra:openai-moderation`. Per the `text-moderation-perspective-api-layer`
 * capability spec
 * `### Requirement: :infra:openai-moderation is the sole owner of the OpenAI Moderation API client`
 * Scenario "`:backend:ktor` source files contain no direct moderation API endpoint reference":
 *
 *  - The literal substring `api.openai.com/v1/moderations` SHALL appear ZERO times
 *    in `:backend:ktor` source files.
 *  - The host substring `api.openai.com` SHALL appear ZERO times in
 *    `:backend:ktor` source files (defense-in-depth in case the path part is
 *    abbreviated in any future code).
 *
 * **Vendor history**: the original capability spec planned Google Perspective API
 * (`commentanalyzer.googleapis.com` / `v1alpha1/comments:analyze`). The vendor
 * pivoted to OpenAI Moderation mid-implementation when Perspective announced
 * sunset (end-of-2026). This scan now polices the new endpoint URL; the
 * encapsulation invariant is unchanged (vendor-specific URLs stay in `:infra:*`).
 *
 * Why a separate scan, NOT extending [VendorSdkLeakageScanTest]: that scan walks
 * `import` lines for forbidden Kotlin package prefixes (Supabase, Lettuce, Firebase
 * SDKs). The OpenAI Moderation client doesn't pull in an OpenAI Kotlin SDK —
 * `:infra:openai-moderation` uses the Ktor HTTP client + a URL string. The canonical
 * scan's import-line discipline doesn't fit; URL-substring isolation is a different
 * scan shape with a different invariant.
 *
 * Mirrors the existing `:lint:detekt-rules` Kotest StringSpec convention so drift
 * surfaces on every `./gradlew :lint:detekt-rules:test` invocation.
 */
class OpenAiModerationEndpointLeakageScanTest : StringSpec({
    val repoRoot = File(System.getProperty("user.dir"), "../../").canonicalFile
    val backendKtorRoot = File(repoRoot, "backend/ktor/src")

    val forbiddenSubstrings =
        listOf(
            "api.openai.com/v1/moderations",
            "api.openai.com",
        )

    "OpenAI Moderation endpoint URL substrings do NOT appear in :backend:ktor source files" {
        val violations = mutableListOf<String>()
        if (backendKtorRoot.exists()) {
            backendKtorRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { ktFile ->
                    val text = ktFile.readText()
                    text.lineSequence().forEachIndexed { idx, line ->
                        forbiddenSubstrings.forEach { substring ->
                            if (line.contains(substring)) {
                                violations.add(
                                    "${ktFile.relativeTo(repoRoot)}:${idx + 1}: contains '$substring' — " +
                                        "this URL belongs in :infra:openai-moderation per `text-moderation-perspective-api-layer`",
                                )
                            }
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            error(
                "OpenAI Moderation endpoint URL substring leak detected in :backend:ktor — the " +
                    "endpoint URL is encapsulated inside :infra:openai-moderation per " +
                    "`text-moderation-perspective-api-layer/spec.md`. Move the URL reference into " +
                    ":infra:openai-moderation and depend on the ModerationClient interface.\n\n" +
                    violations.joinToString("\n"),
            )
        }
        violations shouldBe emptyList()
    }
})
