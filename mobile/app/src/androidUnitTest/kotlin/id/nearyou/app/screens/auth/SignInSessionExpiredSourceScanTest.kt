package id.nearyou.app.screens.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strips block comments (incl. KDoc) then line comments, so an explanatory comment mentioning a
 *  forbidden token does not trip the scan (per `feedback_source_scan_guard_strip_comments`). */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * 9.7 — `mobile-auth-signin` § "No hardcoded session-expired string in source"
 * (mobile-session-expiry-and-proactive-refresh D5). Asserts the involuntary-logout copy appears in
 * `SignInScreen.kt` ONLY via `stringResource(Res.string.signin_session_expired)`, never as an inline
 * literal — the mobile-strings invariant. Comments are stripped first so a KDoc that mentions the
 * string does not trip the scan; the literal needle is assembled from fragments so this guard never
 * flags its own source. Not a Compose UI test, so it runs in every variant (incl. Release).
 */
class SignInSessionExpiredSourceScanTest {
    // Assembled (not a contiguous literal) so the scan never flags this guard itself.
    private val sessionExpiredLiteral = "Sesi kamu " + "berakhir"
    private val resourceKey = "signin_session" + "_expired"

    @Test
    fun signInScreen_sourcesSessionExpiredCopyOnlyViaStringResource() {
        val signInScreen =
            File(findRepoRoot(), "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInScreen.kt")
        assertTrue(signInScreen.isFile, "expected SignInScreen.kt to exist (resolved at $signInScreen)")

        val src = signInScreen.readText().stripComments()
        assertFalse(
            src.contains(sessionExpiredLiteral),
            "SignInScreen must NOT inline the session-expired copy — source it via stringResource(Res.string.$resourceKey)",
        )
        assertTrue(
            src.contains(resourceKey),
            "SignInScreen must reference the session-expired string resource key",
        )
    }

    private companion object {
        /** Walks up to the repo root (the dir holding settings.gradle.kts). */
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }
    }
}
