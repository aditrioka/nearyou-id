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
 * Two scopes, because the forbidden vendor differs by module set:
 *  - **Server-side SDKs** (Supabase / Lettuce / Firebase Admin) are checked against `:core:domain`,
 *    `:core:data`, `:backend:ktor`. `:infra:*` modules are the canonical homes (exempt).
 *  - The **Sentry KMP SDK** (`io.sentry.`) is checked against `:mobile:app` — it must reach Sentry
 *    ONLY through the `:infra:sentry` `CrashReporter` interface (mobile-crash-reporting). The
 *    server-side prefixes are deliberately NOT applied to `mobile/app/src`: the mobile app
 *    legitimately depends on Firebase/Google *client* SDKs (a different surface from the Admin SDK).
 *
 * Pattern mirrors the existing source-scan tests for `BlockExclusionJoinRule`,
 * `RawFromPostsRule`, `RedisHashTagRule`, etc., which run as kotest specs in this module
 * and surface drift on every `:lint:detekt-rules:test` invocation (see `CLAUDE.md`
 * § Pre-push verification).
 */
class VendorSdkLeakageScanTest : StringSpec({
    val repoRoot = File(System.getProperty("user.dir"), "../../").canonicalFile

    fun scanImports(
        roots: List<File>,
        prefixes: List<String>,
    ): List<String> {
        val violations = mutableListOf<String>()
        roots.forEach { root ->
            if (!root.exists()) return@forEach
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { ktFile ->
                    val text = ktFile.readText()
                    text.lineSequence().forEachIndexed { idx, line ->
                        val trimmed = line.trimStart()
                        if (!trimmed.startsWith("import ")) return@forEachIndexed
                        prefixes.forEach { prefix ->
                            if (trimmed.contains("import $prefix")) {
                                violations.add("${ktFile.relativeTo(repoRoot)}:${idx + 1}: $trimmed")
                            }
                        }
                    }
                }
        }
        return violations
    }

    "no Supabase / Lettuce / Firebase SDK imports outside :infra:*" {
        val serverRoots =
            listOf(
                File(repoRoot, "core/domain/src"),
                File(repoRoot, "core/data/src"),
                File(repoRoot, "backend/ktor/src"),
            )
        val serverPrefixes =
            listOf(
                // Supabase server-side SDKs — `:infra:supabase` is the canonical home.
                "io.supabase.",
                "io.github.jan-tennert.supabase.",
                // Lettuce — `:infra:redis` is the canonical home.
                "io.lettuce.",
                // Firebase Admin SDK — `:infra:fcm` is the canonical home.
                "com.google.firebase.",
            )
        val violations = scanImports(serverRoots, serverPrefixes)
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

    "no Sentry SDK imports in :mobile:app — reach Sentry only via :infra:sentry" {
        // mobile-crash-reporting (invariant #16): :mobile:app must NOT import the Sentry KMP SDK
        // directly — it depends on the vendor-free `CrashReporter` interface in :infra:sentry, where
        // the `io.sentry.` import is fenced + `implementation`-scoped.
        val violations =
            scanImports(
                roots = listOf(File(repoRoot, "mobile/app/src")),
                prefixes = listOf("io.sentry."),
            )
        if (violations.isNotEmpty()) {
            error(
                "Sentry KMP SDK imports detected in :mobile:app — the vendor SDK is fenced to" +
                    " :infra:sentry (CLAUDE.md invariant #16). Depend on the `CrashReporter`" +
                    " interface (id.nearyou.app.infra.sentry) instead.\n\n" + violations.joinToString("\n"),
            )
        }
        violations shouldBe emptyList()
    }

    "no RevenueCat SDK imports in :mobile:app — reach RevenueCat only via :infra:revenuecat" {
        // mobile-paywall-screen (invariant #16): :mobile:app must NOT import the RevenueCat purchases-kmp
        // SDK directly — it depends on the vendor-free `PurchaseController` interface in :infra:revenuecat,
        // where the `com.revenuecat.` import is fenced + `implementation`-scoped.
        val violations =
            scanImports(
                roots = listOf(File(repoRoot, "mobile/app/src")),
                prefixes = listOf("com.revenuecat."),
            )
        if (violations.isNotEmpty()) {
            error(
                "RevenueCat SDK imports detected in :mobile:app — the vendor SDK is fenced to" +
                    " :infra:revenuecat (CLAUDE.md invariant #16). Depend on the `PurchaseController`" +
                    " interface (id.nearyou.app.infra.revenuecat) instead.\n\n" + violations.joinToString("\n"),
            )
        }
        violations shouldBe emptyList()
    }

    "no Google Mobile Ads + UMP SDK imports in :mobile:app — reach AdMob only via :infra:admob" {
        // mobile-admob-ads-foundation (invariant #16): :mobile:app must NOT import the Google Mobile Ads /
        // UMP SDKs directly — it depends on the vendor-free `AdProvider` interface + `NativeAdContent` +
        // `NativeAdSurface` composable in :infra:admob, where the vendor imports are fenced +
        // `implementation`-scoped. The PRECISE `com.google.android.gms.ads.` prefix (NOT a bare
        // `com.google.android.gms.`) avoids over-matching the legitimate Play-services *client* deps
        // (`play-services-location` / `credentials-play-services-auth`) the app uses directly. The iOS
        // cinterop packages are guarded too so the iosMain actual can't leak either.
        val violations =
            scanImports(
                roots = listOf(File(repoRoot, "mobile/app/src")),
                prefixes =
                    listOf(
                        "com.google.android.gms.ads.",
                        "com.google.android.ump.",
                        "cocoapods.Google_Mobile_Ads_SDK.",
                        "cocoapods.GoogleUserMessagingPlatform.",
                    ),
            )
        if (violations.isNotEmpty()) {
            error(
                "Google Mobile Ads / UMP SDK imports detected in :mobile:app — the vendor SDKs are fenced" +
                    " to :infra:admob (CLAUDE.md invariant #16). Depend on the `AdProvider` interface" +
                    " (id.nearyou.app.infra.admob) instead.\n\n" + violations.joinToString("\n"),
            )
        }
        violations shouldBe emptyList()
    }
})
