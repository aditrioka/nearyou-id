package id.nearyou.app.screens.notifications

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

/** Strips block comments (incl. KDoc) then line comments, so the scanned file's own explanatory comments
 *  (which name the absent constructs) do not trip the scan (per `feedback_source_scan_guard_strip_comments`). */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * `mobile-notifications-deep-link-targets` § "NotificationsScreen exposes hoisted deep-link callbacks wired
 * through the shell" — source-scan guard mechanizing the navigation-free contract. The screen now deep-links
 * a tapped notification to its target (post / profile / chat thread), but it does so by invoking HOISTED
 * callbacks (`onOpenPost` / `onOpenProfile` / `onOpenChatThread`) that the shell wires to root-stack pushes —
 * NOT by holding a back-stack reference or constructing a `NavKey` route itself. So the screen source must
 * still reference no `NavBackStack`, no `NavKey`, and no `PostDetailRoute` (the shell builds the route from
 * the hoisted `PostDetailTarget`). This supersedes the prior "deep-link tap-through deferred (#193)" guard
 * (deep-linking is now SHIPPED, just hoisted). Needles are assembled from fragments AND the scanned source
 * is comment-stripped, so neither the screen's own KDoc nor this guard's source trips it. Runs in every
 * variant (NOT a Compose UI test). The positive per-type navigation behavior is asserted by
 * `NotificationsScreenNavTest` (Robolectric) + `NotificationsViewModelNavTest`.
 */
class NotificationsScreenNavFreeScanTest {
    // Assembled (not contiguous literals): a Nav3 back-stack handle, a route key, and #159's post-detail
    // route — none may be referenced by the navigation-free notifications screen (navigation is hoisted).
    private val backStackNeedle = "Nav" + "BackStack"
    private val navKeyNeedle = "Nav" + "Key"
    private val postDetailNeedle = "PostDetail" + "Route"

    @Test
    fun notificationsScreenStaysNavigationFreeHoistingViaCallbacks() {
        val screen =
            File(
                findRepoRoot(),
                "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/notifications/NotificationsScreen.kt",
            ).readText().stripComments()

        assertFalse(
            screen.contains(backStackNeedle),
            "NotificationsScreen must hold no back-stack reference (navigation is hoisted to the shell)",
        )
        assertFalse(
            screen.contains(navKeyNeedle),
            "NotificationsScreen must push no NavKey route (it invokes hoisted nav callbacks instead)",
        )
        assertFalse(
            screen.contains(postDetailNeedle),
            "NotificationsScreen must not construct a post-detail route (the shell builds it from the hoisted PostDetailTarget)",
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
