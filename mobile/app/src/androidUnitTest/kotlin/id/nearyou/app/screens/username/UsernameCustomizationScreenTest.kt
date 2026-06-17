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
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val GATE_BODY = "Ganti username adalah fitur Premium."
private const val GATE_CTA = "Aktifkan Premium"
private const val FORMAT_ERR =
    "Username harus 3–30 karakter: huruf kecil, angka, titik, atau garis bawah (tidak di awal/akhir, tanpa titik ganda)."
private const val UNAVAILABLE = "Username ini tidak tersedia. Coba username lain."
private const val MODERATED = "Username ini akan ditinjau tim moderasi. Silakan pilih username lain atau tunggu hasil review."
private const val COOLDOWN_14 = "Ganti username berikutnya tersedia dalam 14 hari."
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…"
private const val DISABLED = "Fitur ganti username sedang tidak tersedia. Coba lagi nanti."

/**
 * Render + behavior coverage of `UsernameCustomizationScreen` via the Robolectric CMP UI runner. Drives
 * states through the on-entry self-profile read (Premium / Free) + the no-delay submit path (the probe's
 * 500 ms debounce runs on viewModelScope, which the compose runner does not advance — the inline
 * availability hint is covered purely by `UsernameUiStateTest`). Covers: the on-entry PremiumGate + the
 * CTA→onActivatePremium hoist, the inline format error, the submit-confirmation modal (confirm fires /
 * dismiss issues nothing), the success→onChanged path, and the change-result messages (Unavailable /
 * Moderated / CooldownActive / Disabled / SessionRedirect).
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class UsernameCustomizationScreenTest {
    private lateinit var usernameFlow: FakeUsernameFlow

    private fun installKoin(
        changeOutcome: UsernameChangeOutcome = UsernameChangeOutcome.Success("newhandle"),
        isPremium: Boolean = true,
        currentUsername: String = "oldname",
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        usernameFlow = FakeUsernameFlow(changeOutcome = changeOutcome)
        startKoin {
            modules(
                module {
                    single<UsernameFlow> { usernameFlow }
                    single<ProfileFlow> { FakeProfileFlow(profileOutcome = selfProfile(currentUsername, isPremium)) }
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
    fun onEntryFree_showsGate_andCtaFiresOnActivatePremium() {
        installKoin(isPremium = false)
        runComposeUiTest {
            var activated = 0
            setContent { KoinContext { NearYouTheme { UsernameCustomizationScreen(onBack = {}, onActivatePremium = { activated++ }) } } }
            waitForIdle()
            onNodeWithText(GATE_BODY).assertExists()
            onNodeWithText(GATE_CTA).assertExists()
            onNodeWithTag(USERNAME_GATE_CTA_TAG).performClick()
            waitForIdle()
            assertEquals(1, activated, "the gate CTA invokes the hoisted onActivatePremium")
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
    fun submit_confirmModal_thenSuccess_firesOnChanged() {
        installKoin(changeOutcome = UsernameChangeOutcome.Success("newhandle"))
        runComposeUiTest {
            var changed = 0
            setContent { KoinContext { NearYouTheme { UsernameCustomizationScreen(onBack = {}, onChanged = { changed++ }) } } }
            waitForIdle()
            onNodeWithTag(USERNAME_FIELD_TAG).performTextInput("newhandle")
            waitForIdle()
            onNodeWithTag(USERNAME_SUBMIT_TAG).performClick()
            waitForIdle()
            onNodeWithTag(USERNAME_CONFIRM_TAG).assertExists()
            onNodeWithTag(USERNAME_CONFIRM_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { changed == 1 }
            assertEquals(listOf("newhandle"), usernameFlow.changeCalls)
            assertEquals(1, changed, "Success → onChanged pops the route")
        }
    }

    @Test
    fun submit_dismiss_issuesNoChange() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { UsernameCustomizationScreen(onBack = {}) } } }
            waitForIdle()
            onNodeWithTag(USERNAME_FIELD_TAG).performTextInput("newhandle")
            waitForIdle()
            onNodeWithTag(USERNAME_SUBMIT_TAG).performClick()
            waitForIdle()
            onNodeWithTag(USERNAME_DISMISS_TAG).performClick()
            waitForIdle()
            assertEquals(true, usernameFlow.changeCalls.isEmpty(), "Batal issues no change request")
        }
    }

    @Test
    fun submit_unavailable_showsGenericMessage() = assertChangeMessage(UsernameChangeOutcome.Unavailable, UNAVAILABLE)

    @Test
    fun submit_moderated_showsModerationMessage() = assertChangeMessage(UsernameChangeOutcome.Moderated, MODERATED)

    @Test
    fun submit_cooldown_showsDayCountdown() = assertChangeMessage(UsernameChangeOutcome.CooldownActive(1_209_600L), COOLDOWN_14)

    @Test
    fun submit_disabled_showsKillSwitchCopy() = assertChangeMessage(UsernameChangeOutcome.Disabled, DISABLED)

    @Test
    fun submit_sessionExpired_showsRedirect() = assertChangeMessage(UsernameChangeOutcome.SessionExpired, SESSION_REDIRECT)

    /** Type a valid candidate → submit → confirm → assert the change result's message renders. */
    private fun assertChangeMessage(
        outcome: UsernameChangeOutcome,
        expectedText: String,
    ) {
        installKoin(changeOutcome = outcome)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { UsernameCustomizationScreen(onBack = {}) } } }
            waitForIdle()
            onNodeWithTag(USERNAME_FIELD_TAG).performTextInput("newhandle")
            waitForIdle()
            onNodeWithTag(USERNAME_SUBMIT_TAG).performClick()
            waitForIdle()
            onNodeWithTag(USERNAME_CONFIRM_TAG).performClick()
            waitForIdle()
            onNodeWithText(expectedText).assertExists()
        }
    }
}
