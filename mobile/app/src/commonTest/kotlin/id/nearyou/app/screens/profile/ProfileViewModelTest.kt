package id.nearyou.app.screens.profile

import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.FollowToggleOutcome
import id.nearyou.app.profile.ProfileOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSelfUserIdProvider(private val id: String?) : SelfUserIdProvider {
    override suspend fun selfUserId(): String? = id
}

class ProfileViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** Creates the VM and activates a background collector so the `WhileSubscribed` uiState shares. */
    private fun TestScope.vm(
        flow: FakeProfileFlow = FakeProfileFlow(),
        self: SelfUserIdProvider = FakeSelfUserIdProvider("self-id"),
        target: String? = "u1",
    ): ProfileViewModel {
        val viewModel = ProfileViewModel(flow, self, target)
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `other-user load resolves Content for the route id`() =
        runTest {
            val flow = FakeProfileFlow(profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("u1")))
            val viewModel = vm(flow, target = "u1")
            advanceUntilIdle()
            val phase = assertIs<ProfilePhase.Content>(viewModel.uiState.value.phase)
            assertEquals("u1", phase.profile.userId)
            assertEquals("u1", flow.loadedUserId)
        }

    @Test
    fun `self load with null target resolves the self id from the session`() =
        runTest {
            val flow = FakeProfileFlow(profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("self-id", isSelf = true)))
            val viewModel = vm(flow, self = FakeSelfUserIdProvider("self-id"), target = null)
            advanceUntilIdle()
            assertEquals("self-id", flow.loadedUserId)
            val phase = assertIs<ProfilePhase.Content>(viewModel.uiState.value.phase)
            assertTrue(phase.profile.isSelf)
        }

    @Test
    fun `self load with no resolvable id maps to Error`() =
        runTest {
            val viewModel = vm(self = FakeSelfUserIdProvider(null), target = null)
            advanceUntilIdle()
            assertIs<ProfilePhase.Error>(viewModel.uiState.value.phase)
        }

    @Test
    fun `not-found outcome maps to NotFound`() =
        runTest {
            val viewModel = vm(FakeProfileFlow(profileOutcome = ProfileOutcome.NotFound))
            advanceUntilIdle()
            assertIs<ProfilePhase.NotFound>(viewModel.uiState.value.phase)
        }

    @Test
    fun `follow toggles optimistically and persists on success`() =
        runTest {
            val flow =
                FakeProfileFlow(
                    profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(followedByViewer = false)),
                    followOutcome = FollowToggleOutcome.Followed,
                )
            val viewModel = vm(flow)
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.followedByViewer)
            viewModel.onToggleFollow()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.followedByViewer)
            assertEquals("u1", flow.followedUserId)
        }

    @Test
    fun `follow reverts the optimistic flip on a network failure`() =
        runTest {
            val flow =
                FakeProfileFlow(
                    profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(followedByViewer = false)),
                    followOutcome = FollowToggleOutcome.NetworkError,
                )
            val viewModel = vm(flow)
            advanceUntilIdle()
            viewModel.onToggleFollow()
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.followedByViewer)
            assertNull(viewModel.uiState.value.message, "a silent revert surfaces no banner")
        }

    @Test
    fun `follow 429 reverts and surfaces the churn-limit message`() =
        runTest {
            val flow =
                FakeProfileFlow(
                    profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(followedByViewer = false)),
                    followOutcome = FollowToggleOutcome.RateLimited(30L),
                )
            val viewModel = vm(flow)
            advanceUntilIdle()
            viewModel.onToggleFollow()
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.followedByViewer)
            assertEquals(ProfileMessage.FOLLOW_RATE_LIMITED, viewModel.uiState.value.message)
        }

    @Test
    fun `follow POST 404 maps to the neutral target-unavailable message and reverts`() =
        runTest {
            val flow =
                FakeProfileFlow(
                    profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(followedByViewer = false)),
                    followOutcome = FollowToggleOutcome.TargetGone,
                )
            val viewModel = vm(flow)
            advanceUntilIdle()
            viewModel.onToggleFollow()
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.followedByViewer)
            assertEquals(ProfileMessage.TARGET_UNAVAILABLE, viewModel.uiState.value.message)
            assertEquals(false, viewModel.uiState.value.navigateBack, "a failed follow never navigates")
        }

    @Test
    fun `self profile shows no toggle effect - isSelf guards the action`() =
        runTest {
            val flow = FakeProfileFlow(profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("self-id", isSelf = true)))
            val viewModel = vm(flow, target = null, self = FakeSelfUserIdProvider("self-id"))
            advanceUntilIdle()
            viewModel.onToggleFollow()
            advanceUntilIdle()
            assertNull(flow.followedUserId, "self read must not issue a follow")
        }

    @Test
    fun `block success surfaces the toast and the navigate-back one-shot`() =
        runTest {
            val flow = FakeProfileFlow(blockOutcome = BlockOutcome.Blocked)
            val viewModel = vm(flow)
            advanceUntilIdle()
            viewModel.onBlockConfirmed()
            advanceUntilIdle()
            assertEquals(ProfileMessage.BLOCK_SUCCESS, viewModel.uiState.value.message)
            assertTrue(viewModel.uiState.value.navigateBack)
            assertEquals("u1", flow.blockedUserId)
            viewModel.onNavigatedBack()
            assertEquals(false, viewModel.uiState.value.navigateBack)
        }

    @Test
    fun `block rate limit surfaces a message and does not navigate`() =
        runTest {
            val viewModel = vm(FakeProfileFlow(blockOutcome = BlockOutcome.RateLimited(10L)))
            advanceUntilIdle()
            viewModel.onBlockConfirmed()
            advanceUntilIdle()
            assertEquals(ProfileMessage.BLOCK_RATE_LIMITED, viewModel.uiState.value.message)
            assertEquals(false, viewModel.uiState.value.navigateBack)
        }

    @Test
    fun `report success passes the mapped category and surfaces the toast`() =
        runTest {
            val flow = FakeProfileFlow(reportOutcome = ReportOutcome.Submitted)
            val viewModel = vm(flow)
            advanceUntilIdle()
            viewModel.onReportSubmitted(ReportReasonCategory.HARASSMENT, "note")
            advanceUntilIdle()
            assertEquals(ProfileMessage.REPORT_SUCCESS, viewModel.uiState.value.message)
            assertEquals(ReportReasonCategory.HARASSMENT, flow.lastReportCategory)
            assertEquals("note", flow.lastReportNote)
        }

    @Test
    fun `report duplicate surfaces the already-reported message`() =
        runTest {
            val viewModel = vm(FakeProfileFlow(reportOutcome = ReportOutcome.Duplicate))
            advanceUntilIdle()
            viewModel.onReportSubmitted(ReportReasonCategory.SPAM, null)
            advanceUntilIdle()
            assertEquals(ProfileMessage.REPORT_DUPLICATE, viewModel.uiState.value.message)
        }

    @Test
    fun `onMessageShown clears the one-shot message`() =
        runTest {
            val viewModel = vm(FakeProfileFlow(reportOutcome = ReportOutcome.Submitted))
            advanceUntilIdle()
            viewModel.onReportSubmitted(ReportReasonCategory.OTHER, null)
            advanceUntilIdle()
            assertEquals(ProfileMessage.REPORT_SUCCESS, viewModel.uiState.value.message)
            viewModel.onMessageShown()
            assertNull(viewModel.uiState.value.message)
        }
}
