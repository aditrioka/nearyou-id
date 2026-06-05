package id.nearyou.app.screens.routing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Strips block comments (incl. KDoc) then line comments, so an explanatory comment mentioning the
 *  forbidden token does not trip the scan (per `feedback_source_scan_guard_strip_comments`). */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * 7.8 — source-scan guard mirroring `VendorSdkLeakageScanTest` / `PostCreationSourceGuardTest`:
 * asserts NO Voyager package reference remains anywhere under the `mobile/app/src` tree after the
 * `mobile-nav-swap-to-navigation3` swap to Navigation 3. The needle is assembled from fragments so
 * this test's OWN (comment-stripped) source never contains the contiguous package string — the scan
 * can safely include this file. Runs in every variant (NOT a Screen UI test, no Compose runner).
 */
class VoyagerAbsenceScanTest {
    // Assembled (not a contiguous literal) so the scan does not flag this guard itself.
    private val voyagerPackage = "cafe.adriel." + "voyager"

    @Test
    fun noVoyagerImportRemainsInMobileSources() {
        val srcRoot = File(findRepoRoot(), "mobile/app/src")
        assertTrue(srcRoot.isDirectory, "expected mobile/app/src to exist (resolved at $srcRoot)")

        val offenders =
            srcRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().stripComments().contains(voyagerPackage) }
                .map { it.relativeTo(srcRoot).path }
                .toList()

        assertTrue(
            offenders.isEmpty(),
            "Voyager references must be fully removed post-swap, but found in: $offenders",
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
