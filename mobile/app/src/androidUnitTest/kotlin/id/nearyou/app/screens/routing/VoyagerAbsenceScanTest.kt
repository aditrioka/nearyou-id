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

    @Test
    fun viewModelStoreDecoratorAndArtifactAreWired() {
        // mobile-app-scaffold § "NavDisplay scopes per-entry ViewModel state via entry decorators"
        // (design Decision 5 — un-deferred: Home is the first ViewModel-backed screen, so the Nearby
        // feed's load state survives the composer round-trip). The per-entry ViewModel-store decorator
        // is wired in App() and the artifact is declared in the catalog. Needles assembled from
        // fragments so this guard does not flag itself.
        val vmDecorator = "rememberViewModelStore" + "NavEntryDecorator"
        val vmArtifact = "lifecycle-viewmodel-" + "navigation3"

        val app = File(findRepoRoot(), "mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt").readText().stripComments()
        assertTrue(
            app.contains(vmDecorator),
            "App() must wire the per-entry ViewModel-store NavEntry decorator (Decision 5 un-deferred)",
        )

        // The module coordinate (with hyphens) lives in the version catalog; build.gradle uses the
        // camelCase accessor, so scan the catalog for the artifact declaration.
        val catalog = File(findRepoRoot(), "gradle/libs.versions.toml").readText()
        assertTrue(
            catalog.contains(vmArtifact),
            "the lifecycle-viewmodel-navigation3 artifact must be declared in the version catalog",
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
