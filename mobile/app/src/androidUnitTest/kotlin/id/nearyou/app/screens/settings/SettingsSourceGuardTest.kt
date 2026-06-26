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

    // mobile-data-export-entry — the data-export seam + state holder. The reused block/consent PII guard
    // does NOT cover `downloadUrl`, so the data-export guards below add it to the denylist explicitly.
    private val dataExportSeamSources =
        listOf(
            "src/commonMain/kotlin/id/nearyou/app/data/dataexport/DataExportApiClient.kt",
            "src/commonMain/kotlin/id/nearyou/app/data/dataexport/DataExportFlow.kt",
            "src/commonMain/kotlin/id/nearyou/app/data/dataexport/DataExportOutcome.kt",
            "src/commonMain/kotlin/id/nearyou/app/data/dataexport/ExportDeadlineLabel.kt",
            "src/commonMain/kotlin/id/nearyou/app/screens/settings/SettingsDataExportViewModel.kt",
        )

    private val settingsScreenPath = "src/commonMain/kotlin/id/nearyou/app/screens/settings/SettingsScreen.kt"

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

    // mobile-data-export-entry § "No hardcoded strings in the data-export affordance": the row + dialog +
    // banner route every user-visible label/title/body/confirm/cancel through `Res.string.*`. The negative
    // is the screen-wide no-hardcoded-`Text("…")` invariant the data-export additions must also honor.
    @Test
    fun dataExportAffordance_usesOnlyStringResources() {
        val screen = stripped(settingsScreenPath)
        val requiredKeys =
            listOf(
                "Res.string.settings_row_data_export",
                "Res.string.settings_row_data_export_sub",
                "Res.string.data_export_dialog_title",
                "Res.string.data_export_dialog_body",
                "Res.string.data_export_confirm",
                "Res.string.data_export_cancel",
                "Res.string.data_export_in_progress",
                "Res.string.data_export_ready_banner",
                "Res.string.data_export_ready_open",
                "Res.string.data_export_expired",
                "Res.string.data_export_failed",
            )
        for (key in requiredKeys) {
            assertTrue(screen.contains(key), "the data-export affordance must resolve user-visible text via $key")
        }
        // No user-visible string is hardcoded — every Text/label flows through stringResource(Res.string.*).
        assertTrue(!screen.contains("Text(\""), "SettingsScreen has a hardcoded Text(\"…\") literal")
        assertTrue(
            !Regex("""(text|subtitle|title|contentDescription)\s*=\s*"""").containsMatchIn(screen),
            "SettingsScreen has a hardcoded user-visible string literal (text/subtitle/title/contentDescription = \"…\")",
        )
    }

    // mobile-data-export-entry § "The data-export seam logs no token, sub, or download URL": no logging
    // call site passes the bearer token / Authorization header / JWT sub / downloadUrl (downloadUrl added
    // to the denylist EXPLICITLY — the reused block/consent guard does not cover it).
    @Test
    fun dataExportSeam_logsNoTokenSubOrDownloadUrl() {
        val forbiddenOnLogLines =
            listOf("downloadUrl", "downloadExpiresAt", "Authorization", "Bearer", "accessToken", "refreshToken", "sub")
        for (path in dataExportSeamSources) {
            val text = stripped(path)
            // File-level: the seam neither handles nor references the auth header / bearer token at all.
            for (token in listOf("Authorization", "Bearer", "accessToken", "refreshToken")) {
                assertTrue(!text.contains(token), "$path must not reference '$token' (the shared Auth plugin owns auth)")
            }
            // Line-level: no logging call site echoes a forbidden value (downloadUrl most of all).
            text.lineSequence().forEach { line ->
                val isLogCall = line.contains("log(", ignoreCase = true) || line.contains("println")
                if (isLogCall) {
                    for (token in forbiddenOnLogLines) {
                        assertTrue(
                            !line.contains(token),
                            "$path logs '$token' — the data-export diagnostics must be status/type-only (PII discipline)",
                        )
                    }
                }
            }
        }
    }

    // mobile-data-export-entry § "The signed download URL is not persisted": no seam/VM call site writes the
    // downloadUrl (or any export field) to DataStore / preferences / disk / any cache — it lives only
    // transiently in UI state for the open-URL hand-off.
    @Test
    fun dataExportSeam_doesNotPersistDownloadUrl() {
        val persistenceApis =
            listOf("DataStore", "SharedPreferences", "putString", "edit {", "preferencesKey", "writeText", "FileOutputStream")
        for (path in dataExportSeamSources) {
            val text = stripped(path)
            for (api in persistenceApis) {
                assertTrue(
                    !text.contains(api),
                    "$path references '$api' — the signed downloadUrl must never be persisted (held transiently only)",
                )
            }
        }
    }
}
