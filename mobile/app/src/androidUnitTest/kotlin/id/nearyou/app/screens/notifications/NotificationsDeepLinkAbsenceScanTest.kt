package id.nearyou.app.screens.notifications

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

/** Strips block comments (incl. KDoc) then line comments, so the scanned file's own explanatory comments
 *  (which name the absent constructs — "deep-link tap-through is deferred", "wires no navigation") do not
 *  trip the scan (per `feedback_source_scan_guard_strip_comments`). */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * `mobile-notifications-list` § "Tapping a row wires no navigation to a post/reply/profile route
 * (deferred)" — source-scan guard (mirroring `FollowingTabNoFetchScanTest`) mechanizing design D5: the
 * navigation-free `NotificationsScreen` holds no back-stack reference and pushes no `NavKey`, so a
 * notification row's tap handler issues only the mark-read call. Deep-link tap-through to a post / reply /
 * profile surface is deferred (`FOLLOW_UPS.md` `mobile-notifications-deep-link-targets`). Needles are
 * assembled from fragments AND the scanned source is comment-stripped, so neither the screen's own KDoc nor
 * this guard's source trips it. Runs in every variant (NOT a Compose UI test).
 */
class NotificationsDeepLinkAbsenceScanTest {
    // Assembled (not contiguous literals): a Nav3 back-stack handle, a route key, and #159's
    // post-detail route — none may be referenced by the navigation-free notifications screen.
    private val backStackNeedle = "Nav" + "BackStack"
    private val navKeyNeedle = "Nav" + "Key"
    private val postDetailNeedle = "PostDetail" + "Route"

    @Test
    fun notificationsScreenWiresNoNavigationRoute() {
        val screen =
            File(
                findRepoRoot(),
                "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/notifications/NotificationsScreen.kt",
            ).readText().stripComments()

        assertFalse(screen.contains(backStackNeedle), "NotificationsScreen must hold no back-stack reference (navigation-free — design D5)")
        assertFalse(screen.contains(navKeyNeedle), "NotificationsScreen must push no NavKey route (deep-link tap-through deferred)")
        assertFalse(
            screen.contains(postDetailNeedle),
            "NotificationsScreen must not navigate to a post-detail route (#159 deep-link deferred)",
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
