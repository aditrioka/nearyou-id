package id.nearyou.app.ui.timeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage of [LoadMoreController] — the ONE shared cursor load-more lifecycle all five paginated
 * surfaces delegate to (`mobile-design-system` § "Shared generic load-more controller"). Pins: append +
 * cursor-advance, the end-reached terminal (null cursor ⇒ no further fetch), the non-destructive error
 * footer (loaded list retained) + retry re-issuing the SAME cursor, the per-instance in-flight guard,
 * the eligibility gate (no fetch while a refresh/initial-load is in flight or when end-reached), and
 * `reset` clearing the footer state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadMoreControllerTest {
    /** Stand-in host owning the list + cursor the controller appends through (the ViewModel's role). */
    private class Host {
        var items: List<String> = listOf("a", "b")
        var cursor: String? = "c1"
        var canLoad: Boolean = true
    }

    private class FakeFetch(pages: List<LoadMorePage<String>>) {
        private val queue = ArrayDeque(pages)
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0
        val cursors = mutableListOf<String>()

        suspend fun fetch(cursor: String): LoadMorePage<String> {
            calls++
            cursors += cursor
            gate?.await()
            return queue.removeFirst()
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.controller(
        host: Host,
        fetch: FakeFetch,
    ): LoadMoreController<String> =
        LoadMoreController(
            scope = this,
            currentCursor = { host.cursor },
            canLoadMore = { host.canLoad },
            fetchPage = { fetch.fetch(it) },
            appendItems = { items, next ->
                host.items = host.items + items
                host.cursor = next
            },
        )

    @Test
    fun appendsBelowAndAdvancesCursor() =
        runTest {
            val fetch = FakeFetch(listOf(LoadMorePage.Success(listOf("c", "d"), "c2")))
            val host = Host()
            val controller = controller(host, fetch)

            controller.loadMore()
            advanceUntilIdle()

            assertEquals(listOf("a", "b", "c", "d"), host.items, "page appends below the retained list")
            assertEquals("c2", host.cursor, "cursor advances to the new page's cursor")
            assertEquals(listOf("c1"), fetch.cursors, "the retained cursor was fetched")
            assertFalse(controller.isLoadingMore.value, "footer spinner cleared on completion")
        }

    @Test
    fun nullCursorPage_marksEndReached_andStopsFurtherFetch() =
        runTest {
            val fetch = FakeFetch(listOf(LoadMorePage.Success(listOf("c"), null)))
            val host = Host()
            val controller = controller(host, fetch)

            controller.loadMore()
            advanceUntilIdle()

            assertEquals(null, host.cursor)
            assertTrue(controller.endReached, "a null nextCursor marks end-reached")
            controller.loadMore()
            advanceUntilIdle()
            assertEquals(1, fetch.calls, "no further fetch once end-reached")
        }

    @Test
    fun failure_raisesNonDestructiveErrorFooter_andRetryRefetchesSameCursor() =
        runTest {
            val fetch = FakeFetch(listOf(LoadMorePage.Failure, LoadMorePage.Success(listOf("c"), "c2")))
            val host = Host()
            val controller = controller(host, fetch)

            controller.loadMore()
            advanceUntilIdle()
            assertTrue(controller.loadMoreError.value, "a failed page raises the error footer")
            assertEquals(listOf("a", "b"), host.items, "the loaded list is retained (non-destructive)")

            controller.retry()
            advanceUntilIdle()
            assertFalse(controller.loadMoreError.value, "retry clears the error on success")
            assertEquals(listOf("a", "b", "c"), host.items)
            assertEquals(listOf("c1", "c1"), fetch.cursors, "retry re-issues for the SAME cursor")
        }

    @Test
    fun inFlightGuard_ignoresConcurrentLoadMore() =
        runTest {
            val fetch = FakeFetch(listOf(LoadMorePage.Success(listOf("c"), "c2"))).apply { gate = CompletableDeferred() }
            val host = Host()
            val controller = controller(host, fetch)

            controller.loadMore()
            controller.loadMore()
            fetch.gate!!.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fetch.calls, "the in-flight guard ignores the concurrent load-more (no duplicate fetch)")
        }

    @Test
    fun ineligible_whenRefreshInFlight_orEndReached() =
        runTest {
            val fetch = FakeFetch(emptyList())
            val host = Host().apply { canLoad = false }
            val controller = controller(host, fetch)

            controller.loadMore()
            advanceUntilIdle()
            assertEquals(0, fetch.calls, "no fetch while a refresh / initial load is in flight (canLoadMore false)")

            host.canLoad = true
            host.cursor = null
            controller.loadMore()
            advanceUntilIdle()
            assertEquals(0, fetch.calls, "no fetch when end-reached (cursor null)")
        }

    @Test
    fun reset_clearsFooterState() =
        runTest {
            val fetch = FakeFetch(listOf(LoadMorePage.Failure))
            val host = Host()
            val controller = controller(host, fetch)

            controller.loadMore()
            advanceUntilIdle()
            assertTrue(controller.loadMoreError.value)

            controller.reset()
            assertFalse(controller.loadMoreError.value, "reset clears the error footer")
            assertFalse(controller.isLoadingMore.value)
        }
}
