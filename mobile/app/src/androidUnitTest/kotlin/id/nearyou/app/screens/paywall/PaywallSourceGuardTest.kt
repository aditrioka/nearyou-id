package id.nearyou.app.screens.paywall

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strips block comments (incl. KDoc) then line comments — the guards are about CODE, so an explanatory
 *  KDoc mentioning a token (e.g. a hex string in prose) must not trip the scan. */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * Static-source guards for the `mobile-paywall` § "No hardcoded UI strings and token-only styling"
 * inspection scenario — a property of the source, not of a single render (mirrors
 * `PostDetailSourceGuardTest`'s file-scan idiom: walk to the repo root, read the `.kt` text, assert).
 * Runs in every variant (NOT a `*ScreenTest`; needs no Compose runner). CLAUDE.md § "Engineering
 * judgment" forbids treating "covered by detekt" as a substitute for the spec's named scenario.
 */
class PaywallSourceGuardTest {
    private val repoRoot: File = findRepoRoot()

    private fun code(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(file.exists(), "expected source file missing: $relativePath (resolved under $repoRoot)")
        return file.readText().stripComments()
    }

    private val screen by lazy { code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/paywall/PaywallScreen.kt") }

    @Test
    fun paywallScreen_hasNoHardcodedUiStringLiterals() {
        assertFalse(screen.contains("Text(\""), "PaywallScreen has a hardcoded Text(\"…\") literal")
        assertFalse(Regex("""text\s*=\s*"""").containsMatchIn(screen), "PaywallScreen has a hardcoded text = \"…\" literal")
        assertFalse(
            Regex("""contentDescription\s*=\s*"""").containsMatchIn(screen),
            "PaywallScreen has a hardcoded contentDescription = \"…\" literal",
        )
        // Positive: the copy flows through stringResource(Res.string.*).
        assertTrue(screen.contains("stringResource(Res.string.cta_activate_premium)"), "the CTA must use stringResource")
        assertTrue(screen.contains("stringResource(Res.string.paywall_title)"), "the hero title must use stringResource")
    }

    @Test
    fun paywallScreen_usesThemeTokensNotHexColorLiterals() {
        // Colors/typography come from NearYouTheme/MaterialTheme tokens — no hex literals, no Color(...) construction.
        assertFalse(Regex("""0x[0-9A-Fa-f]{6,8}""").containsMatchIn(screen), "PaywallScreen has a hex color literal")
        assertFalse(screen.contains("Color(0x"), "PaywallScreen constructs a literal Color(0x…)")
        assertTrue(screen.contains("MaterialTheme.colorScheme"), "colors resolve via MaterialTheme.colorScheme tokens")
    }

    @Test
    fun paywallScreen_holdsNoBackStackReference() {
        // Navigation comes only via the hoisted onClose / onPurchaseComplete lambdas (spec § navigation-free).
        assertFalse(screen.contains("NavBackStack"), "the screen must not reference NavBackStack")
        assertFalse(screen.contains("backStack"), "the screen must not perform its own back-stack mutation")
        assertTrue(screen.contains("onClose"), "the screen takes navigation via the hoisted onClose lambda")
    }

    private companion object {
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }
    }
}
