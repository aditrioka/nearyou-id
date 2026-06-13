package id.nearyou.app.screens.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

/** Strips block comments (incl. KDoc) then line comments, so an explanatory comment in the scanned
 *  file mentioning a forbidden token does not trip the scan (per `feedback_source_scan_guard_strip_comments`). */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * `mobile-home-tab-host` § "No per-tab NavDisplay or tab-root NavKey is introduced" — source-scan guard
 * (mirroring `VoyagerAbsenceScanTest`) mechanizing design D1: `HomeScreen`
 * renders the selected tab's screen **directly** (no nested per-tab navigation host), and `NavKeys`
 * declares **no** per-tab root key. Both needles are assembled from fragments AND the scanned files are
 * comment-stripped, so the explanatory KDoc in `HomeScreen` (which names the absent constructs) and this
 * guard's own source never trip it. Runs in every variant (NOT a Compose UI test).
 */
class TabHostDirectRenderScanTest {
    // Assembled (not contiguous literals): a per-tab navigation host, and a per-tab root NavKey
    // (e.g. a "Nearby<root>" key) — both deferred per design D1.
    private val nestedNavHostNeedle = "Nav" + "Display"
    private val perTabRootKeyNeedle = "Tab" + "Root"

    @Test
    fun homeScreenRendersTabsDirectly_andNavKeysDeclaresNoPerTabRootKey() {
        val root = findRepoRoot()
        val homeScreen =
            File(root, "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt")
                .readText().stripComments()
        val navKeys =
            File(root, "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt")
                .readText().stripComments()

        assertFalse(
            homeScreen.contains(nestedNavHostNeedle),
            "HomeScreen must render the selected tab's screen directly (design D1) — no nested per-tab navigation host",
        )
        assertFalse(
            navKeys.contains(perTabRootKeyNeedle),
            "NavKeys must declare no per-tab root key (per-tab back stacks are deferred — design D1)",
        )
    }

    private companion object {
        /** Walks up to the repo root (the dir holding settings.gradle.kts) so the scan resolves
         *  whether `user.dir` is the module dir or the repo root. */
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }
    }
}
