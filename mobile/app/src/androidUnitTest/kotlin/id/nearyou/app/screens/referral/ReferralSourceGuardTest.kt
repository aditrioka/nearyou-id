package id.nearyou.app.screens.referral

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strips block comments (incl. KDoc) then line comments — the guards are about CODE, so an explanatory
 *  KDoc mentioning a term must not trip the scan. */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * Static-source guards for the `mobile-referral` § "No hardcoded UI strings in the referral surface
 * source" inspection scenario (mirrors `PaywallSourceGuardTest`'s file-scan idiom). A property of the
 * source, not of a single render — every UI-string call site sources via `stringResource(Res.string.*)`
 * — plus the open-to-all guard: the screen references no `Paywall` (design D3). Runs in every variant
 * (NOT a `*ScreenTest`; needs no Compose runner).
 */
class ReferralSourceGuardTest {
    private val repoRoot: File = findRepoRoot()

    private fun code(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(file.exists(), "expected source file missing: $relativePath (resolved under $repoRoot)")
        return file.readText().stripComments()
    }

    private val screen by lazy { code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/referral/ReferralScreen.kt") }

    @Test
    fun referralScreen_hasNoHardcodedUiStringLiterals() {
        assertFalse(screen.contains("Text(\""), "ReferralScreen has a hardcoded Text(\"…\") literal")
        assertFalse(Regex("""text\s*=\s*"""").containsMatchIn(screen), "ReferralScreen has a hardcoded text = \"…\" literal")
        assertFalse(
            Regex("""contentDescription\s*=\s*"""").containsMatchIn(screen),
            "ReferralScreen has a hardcoded contentDescription = \"…\" literal",
        )
        // Positive: the copy flows through stringResource(Res.string.*).
        assertTrue(screen.contains("stringResource(Res.string.referral_copy_action)"), "the copy action must use stringResource")
        assertTrue(screen.contains("stringResource(Res.string.referral_title)"), "the screen title must use stringResource")
    }

    @Test
    fun referralScreen_referencesNoPaywall() {
        // design D3 — the referral surface is open to ALL tiers: no PaywallRoute/PaywallEntry/upsell.
        assertFalse(screen.contains("Paywall"), "the referral surface must not reference a Paywall (open to all tiers)")
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
