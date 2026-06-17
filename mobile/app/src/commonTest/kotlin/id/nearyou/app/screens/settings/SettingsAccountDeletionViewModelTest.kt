package id.nearyou.app.screens.settings

import id.nearyou.app.data.accountdeletion.AccountDeletionOutcome
import id.nearyou.app.data.accountdeletion.DeletionCancelOutcome
import id.nearyou.app.data.accountdeletion.DeletionStatusOutcome
import id.nearyou.app.data.accountdeletion.FakeAccountDeletionFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage of [SettingsAccountDeletionViewModel] (account-deletion-tombstone, mobile-settings).
 * `viewModelScope` dispatches on Main → an [UnconfinedTestDispatcher] runs the init/confirm/cancel
 * coroutines eagerly against the (non-suspending) fake, so state is asserted right after each call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAccountDeletionViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init fetches status — pending shows the banner with the restore-by instant`() {
        val flow = FakeAccountDeletionFlow(statusOutcome = DeletionStatusOutcome.Pending("2026-07-17T00:00:00Z"))
        val vm = SettingsAccountDeletionViewModel(flow)
        assertEquals(1, flow.statusCount)
        assertEquals("2026-07-17T00:00:00Z", vm.banner.value?.scheduledHardDeleteAt)
    }

    @Test
    fun `init fetches status — not pending shows no banner`() {
        val vm = SettingsAccountDeletionViewModel(FakeAccountDeletionFlow(statusOutcome = DeletionStatusOutcome.NotPending))
        assertNull(vm.banner.value)
    }

    @Test
    fun `status read failure is non-trapping — no banner`() {
        val vm = SettingsAccountDeletionViewModel(FakeAccountDeletionFlow(statusOutcome = DeletionStatusOutcome.Unavailable))
        assertNull(vm.banner.value)
        assertFalse(vm.terminal401.value)
    }

    @Test
    fun `confirm deletion success sets the banner`() {
        val flow =
            FakeAccountDeletionFlow(
                statusOutcome = DeletionStatusOutcome.NotPending,
                requestOutcome = AccountDeletionOutcome.Scheduled("2026-08-01T00:00:00Z"),
            )
        val vm = SettingsAccountDeletionViewModel(flow)
        vm.confirmDeletion()
        assertEquals(1, flow.requestCount)
        assertEquals("2026-08-01T00:00:00Z", vm.banner.value?.scheduledHardDeleteAt)
    }

    @Test
    fun `confirm deletion 401 routes to sign-in`() {
        val flow = FakeAccountDeletionFlow(requestOutcome = AccountDeletionOutcome.Terminal401)
        val vm = SettingsAccountDeletionViewModel(flow)
        vm.confirmDeletion()
        assertTrue(vm.terminal401.value)
        assertNull(vm.banner.value)
    }

    @Test
    fun `confirm deletion retryable error surfaces requestError and no banner`() {
        val flow = FakeAccountDeletionFlow(requestOutcome = AccountDeletionOutcome.RetryableError)
        val vm = SettingsAccountDeletionViewModel(flow)
        vm.confirmDeletion()
        assertTrue(vm.requestError.value)
        assertNull(vm.banner.value)
        vm.consumeRequestError()
        assertFalse(vm.requestError.value)
    }

    @Test
    fun `cancel success clears the banner`() {
        val flow =
            FakeAccountDeletionFlow(
                statusOutcome = DeletionStatusOutcome.Pending("2026-07-17T00:00:00Z"),
                cancelOutcome = DeletionCancelOutcome.Success,
            )
        val vm = SettingsAccountDeletionViewModel(flow)
        assertEquals("2026-07-17T00:00:00Z", vm.banner.value?.scheduledHardDeleteAt)
        vm.cancelDeletion()
        assertEquals(1, flow.cancelCount)
        assertNull(vm.banner.value)
    }

    @Test
    fun `cancel retryable error keeps the banner (no optimistic clear)`() {
        val flow =
            FakeAccountDeletionFlow(
                statusOutcome = DeletionStatusOutcome.Pending("2026-07-17T00:00:00Z"),
                cancelOutcome = DeletionCancelOutcome.RetryableError,
            )
        val vm = SettingsAccountDeletionViewModel(flow)
        vm.cancelDeletion()
        assertTrue(vm.cancelError.value)
        assertEquals("2026-07-17T00:00:00Z", vm.banner.value?.scheduledHardDeleteAt) // banner stays
        vm.consumeCancelError()
        assertFalse(vm.cancelError.value)
    }

    @Test
    fun `cancel 401 routes to sign-in`() {
        val flow =
            FakeAccountDeletionFlow(
                statusOutcome = DeletionStatusOutcome.Pending("2026-07-17T00:00:00Z"),
                cancelOutcome = DeletionCancelOutcome.Terminal401,
            )
        val vm = SettingsAccountDeletionViewModel(flow)
        vm.cancelDeletion()
        assertTrue(vm.terminal401.value)
    }
}
