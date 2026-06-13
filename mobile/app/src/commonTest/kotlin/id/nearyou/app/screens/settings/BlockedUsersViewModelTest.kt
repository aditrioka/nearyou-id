package id.nearyou.app.screens.settings

import id.nearyou.app.data.block.BlockedUser
import id.nearyou.app.data.block.BlockedUsersOutcome
import id.nearyou.app.data.block.FakeBlockedUsersFlow
import id.nearyou.app.data.block.UnblockOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage of [BlockedUsersViewModel] — the `BlockedUsersRoute`-scoped block-list holder. Pins: the
 * first page loads once on construction, a successful unblock removes the row, a RETRYABLE unblock keeps
 * the row + flips [BlockedUsersViewModel.unblockError] (NON-optimistic), and a `401` unblock surfaces the
 * token-invalid outcome (→ sign-in). `viewModelScope` dispatches on Main → an [UnconfinedTestDispatcher]
 * runs init/unblock coroutines eagerly against the (non-suspending) fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockedUsersViewModelTest {
    private val rowA = BlockedUser(userId = "u-1", username = "raka.jkt", displayName = "Raka", isPremium = false)
    private val rowB = BlockedUser(userId = "u-2", username = "dewi.k", displayName = "Dewi", isPremium = true)

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun loadedFlow(
        rows: List<BlockedUser>,
        unblockOutcome: UnblockOutcome = UnblockOutcome.Success,
    ) = FakeBlockedUsersFlow(
        fetchOutcome = BlockedUsersOutcome.Loaded(rows, nextCursor = null),
        unblockOutcome = unblockOutcome,
    )

    @Test
    fun `first page loads once on construction`() {
        val flow = loadedFlow(listOf(rowA))
        val vm = BlockedUsersViewModel(flow)
        assertEquals(1, flow.fetchInvocationCount)
        assertEquals(false, vm.isInitialLoad.value)
        assertEquals(BlockedUsersOutcome.Loaded(listOf(rowA), null), vm.outcome.value)
    }

    @Test
    fun `successful unblock removes the row and issues the DELETE for that userId`() {
        val flow = loadedFlow(listOf(rowA, rowB))
        val vm = BlockedUsersViewModel(flow)
        vm.unblock("u-1")
        assertEquals(1, flow.unblockInvocationCount)
        assertEquals("u-1", flow.lastUnblockedUserId)
        val loaded = vm.outcome.value as BlockedUsersOutcome.Loaded
        assertEquals(listOf(rowB), loaded.blocks)
        assertEquals(false, vm.unblockError.value)
    }

    @Test
    fun `retryable unblock keeps the row and flips unblockError`() {
        val flow = loadedFlow(listOf(rowA), unblockOutcome = UnblockOutcome.RetryableError)
        val vm = BlockedUsersViewModel(flow)
        vm.unblock("u-1")
        val loaded = vm.outcome.value as BlockedUsersOutcome.Loaded
        assertEquals(listOf(rowA), loaded.blocks, "the row must NOT be optimistically dropped on failure")
        assertTrue(vm.unblockError.value)
    }

    @Test
    fun `401 unblock surfaces the token-invalid outcome`() {
        val flow = loadedFlow(listOf(rowA), unblockOutcome = UnblockOutcome.TokenInvalid)
        val vm = BlockedUsersViewModel(flow)
        vm.unblock("u-1")
        assertEquals(BlockedUsersOutcome.TokenInvalid, vm.outcome.value)
    }

    @Test
    fun `consumeUnblockError clears the flag`() {
        val flow = loadedFlow(listOf(rowA), unblockOutcome = UnblockOutcome.RetryableError)
        val vm = BlockedUsersViewModel(flow)
        vm.unblock("u-1")
        assertTrue(vm.unblockError.value)
        vm.consumeUnblockError()
        assertEquals(false, vm.unblockError.value)
    }
}
