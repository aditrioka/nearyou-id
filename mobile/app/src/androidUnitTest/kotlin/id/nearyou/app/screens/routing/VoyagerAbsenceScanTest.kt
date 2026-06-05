package id.nearyou.app.screens.routing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
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

    @Test
    fun noDeferredViewModelStoreDecoratorOrArtifactIsIntroduced() {
        // mobile-app-scaffold § "No unused ViewModel-store decorator or artifact is introduced"
        // (design Decision 5 — per-entry ViewModel scoping is deferred until the first ViewModel-backed
        // screen). Needles assembled from fragments so this guard does not flag itself.
        val vmDecorator = "rememberViewModelStore" + "NavEntryDecorator"
        val vmArtifact = "lifecycle-viewmodel-" + "navigation3"
        val srcRoot = File(findRepoRoot(), "mobile/app/src")

        val decoratorOffenders =
            srcRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().stripComments().contains(vmDecorator) }
                .map { it.relativeTo(srcRoot).path }
                .toList()
        assertTrue(
            decoratorOffenders.isEmpty(),
            "the ViewModel-store NavEntry decorator must stay deferred (Decision 5), but found in: $decoratorOffenders",
        )

        val buildFile = File(findRepoRoot(), "mobile/app/build.gradle.kts").readText().stripComments()
        assertFalse(
            buildFile.contains(vmArtifact),
            "the lifecycle-viewmodel-navigation3 artifact must stay deferred (Decision 5)",
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
