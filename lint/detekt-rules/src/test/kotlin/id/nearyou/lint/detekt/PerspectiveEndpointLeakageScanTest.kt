package id.nearyou.lint.detekt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Static-source scan asserting that the Google Perspective API endpoint URL
 * (`commentanalyzer.googleapis.com` and the path `v1alpha1/comments:analyze`) is
 * fully encapsulated inside `:infra:perspective`. Per the
 * `text-moderation-perspective-api-layer` capability spec
 * `### Requirement: :infra:perspective is the sole owner of the Google Perspective API client`
 * Scenario "`:backend:ktor` source files contain no direct Perspective API endpoint reference":
 *
 *  - The literal substring `commentanalyzer.googleapis.com` SHALL appear ZERO times in
 *    `:backend:ktor` source files.
 *  - The literal substring `v1alpha1/comments:analyze` SHALL appear ZERO times in
 *    `:backend:ktor` source files.
 *
 * Why a separate scan, NOT extending [VendorSdkLeakageScanTest]: that scan walks
 * `import` lines for forbidden Kotlin package prefixes (Supabase, Lettuce, Firebase SDKs).
 * Perspective doesn't have a Kotlin SDK package — `:infra:perspective` uses the Ktor
 * HTTP client + a URL string. The canonical scan's import-line discipline doesn't
 * fit; URL-substring isolation is a different scan shape with a different invariant.
 *
 * Mirrors the existing `:lint:detekt-rules` Kotest StringSpec convention so drift surfaces
 * on every `./gradlew :lint:detekt-rules:test` invocation.
 */
class PerspectiveEndpointLeakageScanTest : StringSpec({
    val repoRoot = File(System.getProperty("user.dir"), "../../").canonicalFile
    val backendKtorRoot = File(repoRoot, "backend/ktor/src")

    val forbiddenSubstrings =
        listOf(
            "commentanalyzer.googleapis.com",
            "v1alpha1/comments:analyze",
        )

    "Perspective endpoint URL substrings do NOT appear in :backend:ktor source files" {
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
                                        "this URL belongs in :infra:perspective per `text-moderation-perspective-api-layer`",
                                )
                            }
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            error(
                "Perspective API endpoint URL substring leak detected in :backend:ktor — the " +
                    "Perspective endpoint URL is encapsulated inside :infra:perspective per " +
                    "`text-moderation-perspective-api-layer/spec.md`. Move the URL reference into " +
                    ":infra:perspective and depend on the PerspectiveClient interface.\n\n" +
                    violations.joinToString("\n"),
            )
        }
        violations shouldBe emptyList()
    }
})
