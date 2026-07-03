package id.nearyou.app.screens.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.data.accountdeletion.AccountDeletionFlow
import id.nearyou.app.data.accountdeletion.FakeAccountDeletionFlow
import id.nearyou.app.data.dataexport.DataExportFlow
import id.nearyou.app.data.dataexport.DataExportStatusOutcome
import id.nearyou.app.data.dataexport.FakeDataExportFlow
import id.nearyou.app.hidedistance.HideDistanceRepository
import id.nearyou.app.hidedistance.HideDistanceState
import id.nearyou.app.theme.NearYouTheme
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val DATA_EXPORT = "Unduh Data Saya"
private const val DATA_EXPORT_DIALOG_TITLE = "Unduh data kamu?"
private const val DATA_EXPORT_CONFIRM = "Ya, minta export"

/** Minimal [HideDistanceRepository] stub so `SettingsScreen` resolves (the export flow is what we exercise). */
private class StubHideDistanceRepository : HideDistanceRepository {
    override suspend fun loadState(): HideDistanceState? = null

    override suspend fun setHideDistance(value: Boolean): Boolean = true
}

/**
 * iOS counterpart to the Robolectric `SettingsScreenTest` data-export path (`mobile-data-export-entry`) —
 * the "Unduh Data Saya" request flow run natively on the iOS simulator. Covers open-settings →
 * "Unduh Data Saya" → confirm → exactly one `POST`, reusing the commonTest [FakeDataExportFlow]. See
 * `SignInFlowIosTest` for the iosTest-placement + Kotlin/Native test-name rationale (no `(`,`)`,`,`,`#`).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class DataExportFlowIosTest {
    private lateinit var dataExportFlow: FakeDataExportFlow

    private fun installKoin(dataExport: FakeDataExportFlow) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        dataExportFlow = dataExport
        startKoin {
            modules(
                module {
                    single<TokenStore> { InMemoryTokenStore() }
                    single<AccountDeletionFlow> { FakeAccountDeletionFlow() }
                    single<DataExportFlow> { dataExportFlow }
                    single<HideDistanceRepository> { StubHideDistanceRepository() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun confirmingDataExportIssuesOnePost() {
        installKoin(FakeDataExportFlow(statusOutcome = DataExportStatusOutcome.None))
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            onNodeWithText(DATA_EXPORT).performScrollTo().performClick()
            onNodeWithText(DATA_EXPORT_DIALOG_TITLE).assertExists()
            onNodeWithText(DATA_EXPORT_CONFIRM).performClick()
            waitUntil { dataExportFlow.requestCount == 1 }
            assertEquals(1, dataExportFlow.requestCount)
        }
    }
}
