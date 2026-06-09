package id.nearyou.app.screens.timeline

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
 * `mobile-home-tab-host` § "Following tab issues no network fetch" — source-scan guard (mirroring
 * `VoyagerAbsenceScanTest`) asserting the Following tab wires NO Following-timeline fetch: no source
 * file references the `/api/v1/timeline/following` endpoint path nor a Following-timeline
 * API-client / repository / flow type. The Following tab renders a static placeholder
 * (`FollowingPlaceholderScreen`) and issues no request — the real feed is deferred per
 * `docs/08-Roadmap-Risk.md` § Phase 3.
 *
 * Both needles are assembled from fragments AND this class is deliberately NOT named with the
 * client-type needle, so this guard never flags its own (comment-stripped) source. Runs in every
 * variant (NOT a Compose UI test).
 */
class FollowingTabNoFetchScanTest {
    // Assembled (not contiguous literals) so the scan does not flag this guard itself.
    private val followingEndpoint = "timeline/" + "following"
    private val followingClientPrefix = "Following" + "Timeline"

    @Test
    fun followingTabWiresNoFeedFetchInMobileSources() {
        val srcRoot = File(findRepoRoot(), "mobile/app/src")
        assertTrue(srcRoot.isDirectory, "expected mobile/app/src to exist (resolved at $srcRoot)")

        val offenders =
            srcRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter {
                    val src = it.readText().stripComments()
                    src.contains(followingEndpoint) || src.contains(followingClientPrefix)
                }
                .map { it.relativeTo(srcRoot).path }
                .toList()

        assertTrue(
            offenders.isEmpty(),
            "the Following tab must wire no timeline-following fetch (the feed is deferred), but references were found in: $offenders",
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
