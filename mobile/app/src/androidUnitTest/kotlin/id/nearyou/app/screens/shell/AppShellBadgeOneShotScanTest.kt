package id.nearyou.app.screens.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

/** Strips block comments (incl. KDoc) then line comments, so the scanned file's own explanatory comments
 *  (which name the absent constructs — "no polling timer", "live updates deferred") do not trip the scan
 *  (per `feedback_source_scan_guard_strip_comments`). */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * `mobile-home-tab-host` § "Badge is one-shot (no live updates wired)" — source-scan guard (mirroring
 * `FollowingTabNoFetchScanTest`) mechanizing design D6: the Notifikasi unread badge is fetched ONLY on
 * shell composition (the VM's `init`) + on leaving the section (`DisposableEffect` `onDispose` →
 * `refreshUnreadCount()`), with NO polling timer / repeating delay / push-driven live `collect`
 * subscription. Live updates are deferred (follow-up issue #197 (`mobile-notifications-live-unread-badge`)).
 *
 * The fetch logic was moved off the composable into [AppShellViewModel] (2026-06-10 audit, finding 05-#9),
 * so the one-shot guarantee is enforced over the **VM** (all four needles): the VM uses a single
 * `viewModelScope.launch` fetch + `map`/`stateIn`, never a polling loop or a live `.collect` on a source
 * flow. The **shell** is additionally scanned for the polling needles (loop / delay / timer) so it can't
 * reintroduce a driver — but NOT for `.collect`, since the shell now legitimately collects the VM state via
 * `collectAsStateWithLifecycle` (a state projection, not a live notifications subscription).
 *
 * Needles are assembled from fragments AND the scanned source is comment-stripped, so neither the scanned
 * files' KDoc nor this guard's source trips it. Runs in every variant (NOT a Compose UI test).
 */
class AppShellBadgeOneShotScanTest {
    // Assembled (not contiguous literals): a polling loop, a repeating delay, a timer, and a flow
    // subscription — none may back the one-shot unread badge.
    private val whileLoopNeedle = "while" + " ("
    private val delayNeedle = "delay" + "("
    private val timerNeedle = "Timer"
    private val collectNeedle = "." + "collect"

    @Test
    fun appShellBadgeIsOneShot_noPollingOrLiveSubscription() {
        val viewModel =
            File(
                findRepoRoot(),
                "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/shell/AppShellViewModel.kt",
            ).readText().stripComments()

        // The badge's unread-count fetch lives in the VM (audit 05-#9). It must stay one-shot: no polling
        // loop, no repeating delay, no timer, and no live flow subscription (map+stateIn only).
        assertFalse(viewModel.contains(whileLoopNeedle), "the unread badge must not poll in a loop (one-shot — design D6)")
        assertFalse(viewModel.contains(delayNeedle), "the unread badge must wire no repeating delay (one-shot — design D6)")
        assertFalse(viewModel.contains(timerNeedle), "the unread badge must wire no polling timer (live updates deferred)")
        assertFalse(viewModel.contains(collectNeedle), "the unread badge must wire no live flow subscription (one-shot fetch only)")

        // The shell must not reintroduce a polling driver either (it now only renders the VM state).
        val shell =
            File(
                findRepoRoot(),
                "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/shell/AppShellScreen.kt",
            ).readText().stripComments()

        assertFalse(shell.contains(whileLoopNeedle), "the shell must not poll the unread badge in a loop (one-shot — design D6)")
        assertFalse(shell.contains(delayNeedle), "the shell must wire no repeating delay for the unread badge (one-shot — design D6)")
        assertFalse(shell.contains(timerNeedle), "the shell must wire no polling timer for the unread badge (live updates deferred)")
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
