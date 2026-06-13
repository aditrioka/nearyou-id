package id.nearyou.app.screens.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PII-discipline guard for `mobile-settings` (mirrors `ConsentSourceGuardTest`). Two checks, comments
 * stripped first so a KDoc legitimately naming a term does not trip the scan:
 *
 *  1. No settings consent / block source references the bearer token / auth header — the shared
 *     `Auth { bearer }` client owns auth; settings code neither handles nor logs it (task 4.5).
 *  2. No block source LOGS a blocked user's `userId` / `username` / `displayName` — the repository's
 *     diagnostics are status/type-only; the UUID is the unblock path-param, never logged (task 3.4).
 *
 * A plain JVM test (file I/O), not a Robolectric UI test, so it runs in every variant.
 */
class SettingsSourceGuardTest {
    private fun stripped(path: String): String {
        val file = File(path)
        assertTrue(file.exists(), "expected settings source at $path")
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
    }

    private val consentSources =
        listOf(
            "src/commonMain/kotlin/id/nearyou/app/screens/settings/ConsentSettingsScreen.kt",
            "src/commonMain/kotlin/id/nearyou/app/screens/settings/ConsentSettingsViewModel.kt",
            "src/commonMain/kotlin/id/nearyou/app/data/consent/ConsentSnapshotStore.kt",
        )

    private val blockSources =
        listOf(
            "src/commonMain/kotlin/id/nearyou/app/data/block/BlockedUsersApiClient.kt",
            "src/commonMain/kotlin/id/nearyou/app/data/block/BlockedUsersFlow.kt",
            "src/commonMain/kotlin/id/nearyou/app/data/block/BlockedUsersOutcome.kt",
            "src/commonMain/kotlin/id/nearyou/app/screens/settings/BlockedUsersScreen.kt",
            "src/commonMain/kotlin/id/nearyou/app/screens/settings/BlockedUsersViewModel.kt",
        )

    @Test
    fun settingsConsentSources_referenceNoAuthHeaderOrBearerToken() {
        val forbidden = listOf("Authorization", "Bearer", "accessToken", "refreshToken")
        for (path in consentSources) {
            val text = stripped(path)
            for (token in forbidden) {
                assertTrue(!text.contains(token), "$path must not reference '$token' (PII discipline)")
            }
        }
    }

    @Test
    fun blockSources_logNoBlockedUserIdentifier() {
        val piiFields = listOf("userId", "username", "displayName")
        for (path in blockSources) {
            stripped(path).lineSequence().forEach { line ->
                val isLogCall = line.contains("log(", ignoreCase = true) || line.contains("println")
                if (isLogCall) {
                    for (field in piiFields) {
                        assertTrue(
                            !line.contains(field),
                            "$path logs a blocked-user '$field' — the diagnostics must be status/type-only (PII discipline)",
                        )
                    }
                }
            }
        }
    }
}
