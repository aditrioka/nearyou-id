package id.nearyou.lint.detekt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Static-source scan asserting that vendor-SDK imports stay confined to `:infra:*` modules
 * per `CLAUDE.md` § Critical invariants ("No vendor SDK import outside `:infra:*` —
 * domain/data code depends only on interfaces"). Recurring CI guard for the
 * chat-realtime-broadcast Phase 8.4 follow-on (one-shot greps in 8.3 catch the current
 * state but don't prevent future drift).
 *
 * Forbidden import prefixes are checked against the source roots of `:core:domain`,
 * `:core:data`, and `:backend:ktor`. `:infra:*` modules are deliberately exempt — they
 * are the canonical homes for vendor SDK adapters.
 *
 * Pattern mirrors the existing source-scan tests for `BlockExclusionJoinRule`,
 * `RawFromPostsRule`, `RedisHashTagRule`, etc., which run as kotest specs in this module
 * and surface drift on every `:lint:detekt-rules:test` invocation (see `CLAUDE.md`
 * § Pre-push verification).
 */
class VendorSdkLeakageScanTest : StringSpec({
    val repoRoot = File(System.getProperty("user.dir"), "../../").canonicalFile

    val nonInfraSourceRoots =
        listOf(
            File(repoRoot, "core/domain/src"),
            File(repoRoot, "core/data/src"),
            File(repoRoot, "backend/ktor/src"),
        )

    val forbiddenPrefixes =
        listOf(
            // Supabase server-side SDKs — `:infra:supabase` is the canonical home.
            "io.supabase.",
            "io.github.jan-tennert.supabase.",
            // Lettuce — `:infra:redis` is the canonical home.
            "io.lettuce.",
            // Firebase Admin SDK — `:infra:fcm` is the canonical home.
            "com.google.firebase.",
        )

    "no Supabase / Lettuce / Firebase SDK imports outside :infra:*" {
        val violations = mutableListOf<String>()
        nonInfraSourceRoots.forEach { root ->
            if (!root.exists()) return@forEach
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { ktFile ->
                    val text = ktFile.readText()
                    text.lineSequence().forEachIndexed { idx, line ->
                        val trimmed = line.trimStart()
                        if (!trimmed.startsWith("import ")) return@forEachIndexed
                        forbiddenPrefixes.forEach { prefix ->
                            if (trimmed.contains("import $prefix")) {
                                violations.add(
                                    "${ktFile.relativeTo(repoRoot)}:${idx + 1}: $trimmed",
                                )
                            }
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            error(
                "Vendor SDK imports detected outside :infra:* modules — these belong in" +
                    " an `:infra:<vendor>` module per CLAUDE.md § Critical invariants. Move the" +
                    " import to the appropriate `:infra:*` adapter and depend on a domain" +
                    " interface from the violating module.\n\n" + violations.joinToString("\n"),
            )
        }
        violations shouldBe emptyList()
    }
})
