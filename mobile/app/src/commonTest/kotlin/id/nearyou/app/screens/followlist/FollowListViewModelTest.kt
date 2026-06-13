package id.nearyou.app.screens.followlist

import id.nearyou.app.followlist.FakeFollowListFlow
import id.nearyou.app.followlist.FollowListOutcome
import id.nearyou.app.followlist.FollowListTab
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FollowListViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** Creates the VM + activates a background collector so the `WhileSubscribed` uiState shares. */
    private fun TestScope.vm(
        flow: FakeFollowListFlow,
        initial: FollowListTab = FollowListTab.Followers,
    ): FollowListViewModel {
        val viewModel = FollowListViewModel(flow, userId = "p1", initialTab = initial)
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `init loads the initial tab and leaves the other tab loading until revealed`() =
        runTest {
            val flow = FakeFollowListFlow().apply { responder = { _, _ -> FakeFollowListFlow.page(listOf("a", "b"), null) } }
            val viewModel = vm(flow, initial = FollowListTab.Followers)
            advanceUntilIdle()
            val followers = assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.followers)
            assertEquals(2, followers.users.size)
            assertEquals(FollowListTabUiState.Loading, viewModel.uiState.value.following)
            assertEquals(0, flow.callCount(FollowListTab.Following))
            assertEquals(FollowListTab.Followers, viewModel.initialTab)
        }

    @Test
    fun `onTabRevealed lazily loads the other tab exactly once`() =
        runTest {
            val flow = FakeFollowListFlow().apply { responder = { _, _ -> FakeFollowListFlow.page(listOf("a"), null) } }
            val viewModel = vm(flow, initial = FollowListTab.Followers)
            advanceUntilIdle()
            viewModel.onTabRevealed(FollowListTab.Following)
            advanceUntilIdle()
            assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.following)
            assertEquals(1, flow.callCount(FollowListTab.Following))
            viewModel.onTabRevealed(FollowListTab.Following)
            advanceUntilIdle()
            assertEquals(1, flow.callCount(FollowListTab.Following))
        }

    @Test
    fun `initialTab Following deep-links the following tab as the loaded one`() =
        runTest {
            val flow = FakeFollowListFlow().apply { responder = { tab, _ -> FakeFollowListFlow.page(listOf(tab.name), null) } }
            val viewModel = vm(flow, initial = FollowListTab.Following)
            advanceUntilIdle()
            assertEquals(FollowListTab.Following, viewModel.initialTab)
            assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.following)
            assertEquals(FollowListTabUiState.Loading, viewModel.uiState.value.followers)
        }

    @Test
    fun `load-more appends the next page and stops at a null cursor`() =
        runTest {
            val flow =
                FakeFollowListFlow().apply {
                    responder = { _, cursor ->
                        if (cursor == null) FakeFollowListFlow.page(listOf("a", "b"), "C1") else FakeFollowListFlow.page(listOf("c"), null)
                    }
                }
            val viewModel = vm(flow)
            advanceUntilIdle()
            viewModel.onLoadMore(FollowListTab.Followers)
            advanceUntilIdle()
            val content = assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.followers)
            assertEquals(3, content.users.size)
            assertFalse(content.canLoadMore)
            val before = flow.callCount(FollowListTab.Followers)
            viewModel.onLoadMore(FollowListTab.Followers)
            advanceUntilIdle()
            assertEquals(before, flow.callCount(FollowListTab.Followers))
        }

    @Test
    fun `single-page list never offers load-more`() =
        runTest {
            val flow = FakeFollowListFlow().apply { responder = { _, _ -> FakeFollowListFlow.page(listOf("a"), null) } }
            val viewModel = vm(flow)
            advanceUntilIdle()
            assertFalse(assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.followers).canLoadMore)
            viewModel.onLoadMore(FollowListTab.Followers)
            advanceUntilIdle()
            assertEquals(1, flow.callCount(FollowListTab.Followers))
        }

    @Test
    fun `no duplicate in-flight load-more`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val flow =
                FakeFollowListFlow().apply {
                    responder = { _, cursor ->
                        if (cursor == null) {
                            FakeFollowListFlow.page(listOf("a"), "C1")
                        } else {
                            gate.await()
                            FakeFollowListFlow.page(listOf("b"), null)
                        }
                    }
                }
            val viewModel = vm(flow)
            advanceUntilIdle()
            viewModel.onLoadMore(FollowListTab.Followers)
            advanceUntilIdle()
            viewModel.onLoadMore(FollowListTab.Followers)
            advanceUntilIdle()
            assertEquals(2, flow.callCount(FollowListTab.Followers))
            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `load-more failure retains the rows and the footer retries`() =
        runTest {
            val flow =
                FakeFollowListFlow().apply {
                    responder = { _, cursor ->
                        if (cursor == null) FakeFollowListFlow.page(listOf("a", "b"), "C1") else FollowListOutcome.NetworkError
                    }
                }
            val viewModel = vm(flow)
            advanceUntilIdle()
            viewModel.onLoadMore(FollowListTab.Followers)
            advanceUntilIdle()
            val failed = assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.followers)
            assertEquals(2, failed.users.size)
            assertTrue(failed.loadMoreFailed)
            flow.responder = { _, cursor -> if (cursor == null) error("not expected") else FakeFollowListFlow.page(listOf("c"), null) }
            viewModel.onRetryLoadMore(FollowListTab.Followers)
            advanceUntilIdle()
            val after = assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.followers)
            assertEquals(3, after.users.size)
            assertFalse(after.loadMoreFailed)
        }

    @Test
    fun `refresh replaces the rows and toggles isRefreshing`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var refreshStarted = false
            val flow =
                FakeFollowListFlow().apply {
                    responder = { _, _ ->
                        if (refreshStarted) {
                            gate.await()
                            FakeFollowListFlow.page(listOf("x"), null)
                        } else {
                            FakeFollowListFlow.page(listOf("a", "b"), null)
                        }
                    }
                }
            val viewModel = vm(flow)
            advanceUntilIdle()
            refreshStarted = true
            viewModel.onRefresh(FollowListTab.Followers)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.followersRefreshing)
            assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.followers)
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.followersRefreshing)
            val content = assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.followers)
            assertEquals(listOf("x"), content.users.map { it.userId })
        }

    @Test
    fun `refresh failure keeps the prior rows`() =
        runTest {
            var fail = false
            val flow =
                FakeFollowListFlow().apply {
                    responder = { _, _ -> if (fail) FollowListOutcome.NetworkError else FakeFollowListFlow.page(listOf("a", "b"), null) }
                }
            val viewModel = vm(flow)
            advanceUntilIdle()
            fail = true
            viewModel.onRefresh(FollowListTab.Followers)
            advanceUntilIdle()
            val content = assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.followers)
            assertEquals(2, content.users.size)
            assertFalse(viewModel.uiState.value.followersRefreshing)
        }

    @Test
    fun `constant 404 maps both tabs to NotFound`() =
        runTest {
            val flow = FakeFollowListFlow().apply { responder = { _, _ -> FollowListOutcome.NotFound } }
            val viewModel = vm(flow, initial = FollowListTab.Followers)
            advanceUntilIdle()
            assertEquals(FollowListTabUiState.NotFound, viewModel.uiState.value.followers)
            viewModel.onTabRevealed(FollowListTab.Following)
            advanceUntilIdle()
            assertEquals(FollowListTabUiState.NotFound, viewModel.uiState.value.following)
        }

    @Test
    fun `retry reloads after an initial-load error`() =
        runTest {
            var fail = true
            val flow =
                FakeFollowListFlow().apply {
                    responder = { _, _ -> if (fail) FollowListOutcome.NetworkError else FakeFollowListFlow.page(listOf("a"), null) }
                }
            val viewModel = vm(flow)
            advanceUntilIdle()
            assertEquals(FollowListTabUiState.Error, viewModel.uiState.value.followers)
            fail = false
            viewModel.onRetry(FollowListTab.Followers)
            advanceUntilIdle()
            assertIs<FollowListTabUiState.Content>(viewModel.uiState.value.followers)
        }
}
