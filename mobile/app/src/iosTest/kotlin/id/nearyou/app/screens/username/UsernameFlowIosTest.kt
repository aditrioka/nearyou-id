package id.nearyou.app.screens.username

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.username.FakeUsernameFlow
import id.nearyou.app.username.UsernameChangeOutcome
import id.nearyou.app.username.UsernameFlow
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val GATE_BODY = "Ganti username adalah fitur Premium."
private const val FORMAT_ERR =
    "Username harus 3–30 karakter: huruf kecil, angka, titik, atau garis bawah (tidak di awal/akhir, tanpa titik ganda)."
private const val UNAVAILABLE = "Username ini tidak tersedia. Coba username lain."

/**
 * iOS counterpart to the Robolectric [UsernameCustomizationScreenTest] — the Ganti Username surface run
 * natively on the iOS simulator (`:mobile:app:iosSimulatorArm64Test`), proving the new
 * `UsernameCustomizationRoute` + data seam compile + run on Kotlin/Native (the universal per-screen
 * `*FlowIosTest` convention). Focused on the states this change adds: the on-entry PremiumGate, the
 * inline format error, and a submit-driven change result. Reuses the commonTest fakes.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class UsernameFlowIosTest {
    private lateinit var usernameFlow: FakeUsernameFlow

    private fun installKoin(
        changeOutcome: UsernameChangeOutcome = UsernameChangeOutcome.Success("newhandle"),
        isPremium: Boolean = true,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        usernameFlow = FakeUsernameFlow(changeOutcome = changeOutcome)
        startKoin {
            modules(
                module {
                    single<UsernameFlow> { usernameFlow }
                    single<ProfileFlow> { FakeProfileFlow(profileOutcome = selfProfile(isPremium)) }
                    single<SelfUserIdProvider> { FakeSelfUserIdProvider() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun onEntryFree_showsGate() {
        installKoin(isPremium = false)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { UsernameCustomizationScreen(onBack = {}) } } }
            waitForIdle()
            onNodeWithText(GATE_BODY).assertExists()
        }
    }

    @Test
    fun editingFormatInvalid_showsInlineError() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { UsernameCustomizationScreen(onBack = {}) } } }
            waitForIdle()
            onNodeWithTag(USERNAME_FIELD_TAG).performTextInput("ab")
            waitForIdle()
            onNodeWithText(FORMAT_ERR).assertExists()
        }
    }

    @Test
    fun submit_unavailable_showsMessage() {
        installKoin(changeOutcome = UsernameChangeOutcome.Unavailable)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { UsernameCustomizationScreen(onBack = {}) } } }
            waitForIdle()
            onNodeWithTag(USERNAME_FIELD_TAG).performTextInput("newhandle")
            waitForIdle()
            onNodeWithTag(USERNAME_SUBMIT_TAG).performClick()
            waitForIdle()
            onNodeWithTag(USERNAME_CONFIRM_TAG).performClick()
            waitForIdle()
            onNodeWithText(UNAVAILABLE).assertExists()
        }
    }
}
