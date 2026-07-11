package id.nearyou.app.screens.settings

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.push.NotificationContentPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage of the notification chat-preview toggle on [SettingsViewModel]
 * (mobile-notification-preview-toggle, executing #431). Pins: the seed reflects the stored
 * preference (default OFF when unset); toggling updates the flow AND round-trips through
 * [NotificationContentPreference] (the ONLY store — on iOS the Koin binding writes the App-Group
 * suite the NSE reads); a null preference leaves the row inert OFF (the fail-safe DI-gap contract).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsChatPreviewViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private class InMemoryPreference(
        private var value: Boolean? = null,
    ) : NotificationContentPreference {
        override suspend fun previewEnabled(): Boolean = value ?: false

        override suspend fun setPreviewEnabled(v: Boolean) {
            value = v
        }
    }

    private fun viewModel(preference: NotificationContentPreference?): SettingsViewModel =
        SettingsViewModel(
            tokenStore = InMemoryTokenStore(),
            notificationContentPreference = preference,
        )

    @Test
    fun seedReflectsAStoredTrue() =
        runTest {
            assertTrue(viewModel(InMemoryPreference(value = true)).chatPreviewChecked.value)
        }

    @Test
    fun seedDefaultsOffWhenUnset() =
        runTest {
            assertFalse(viewModel(InMemoryPreference()).chatPreviewChecked.value)
        }

    @Test
    fun toggleUpdatesTheFlowAndRoundTripsThroughThePreference() =
        runTest {
            val preference = InMemoryPreference()
            val vm = viewModel(preference)
            vm.onChatPreviewToggle(true)
            assertTrue(vm.chatPreviewChecked.value)
            assertTrue(preference.previewEnabled())
            vm.onChatPreviewToggle(false)
            assertFalse(vm.chatPreviewChecked.value)
            assertFalse(preference.previewEnabled())
        }

    @Test
    fun nullPreferenceLeavesTheRowInertOff() =
        runTest {
            val vm = viewModel(preference = null)
            vm.onChatPreviewToggle(true)
            assertFalse(vm.chatPreviewChecked.value)
        }
}
