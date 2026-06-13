package id.nearyou.app.screens.chat

import androidx.lifecycle.ViewModelStore
import id.nearyou.app.chat.ChatMessageDto
import id.nearyou.app.chat.ChatThreadOutcome
import id.nearyou.app.chat.FakeChatFlow
import id.nearyou.app.chat.FakeChatRealtimeSubscriber
import id.nearyou.app.chat.SendOutcome
import id.nearyou.app.chat.ViewerIdProvider
import id.nearyou.app.infra.supabaserealtime.ChatMessageInbound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val CONV = "11111111-1111-1111-1111-111111111111"
private const val VIEWER = "22222222-2222-2222-2222-222222222222"
private const val OTHER = "33333333-3333-3333-3333-333333333333"
private const val SERVER_MSG = "44444444-4444-4444-4444-444444444444"
private const val MSG_ID = "55555555-5555-5555-5555-555555555555"

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class ChatThreadViewModelTest {
    private fun dto(
        id: String,
        sender: String = OTHER,
        content: String? = "c",
        createdAt: String = "2026-06-01T10:00:00Z",
        redactedAt: String? = null,
    ) = ChatMessageDto(id = id, conversationId = CONV, senderId = sender, content = content, createdAt = createdAt, redactedAt = redactedAt)

    private fun inbound(
        id: String,
        sender: String = OTHER,
        content: String? = "c",
        createdAt: String = "2026-06-01T11:00:00Z",
        redactedAt: String? = null,
    ) = ChatMessageInbound(
        id = Uuid.parse(id),
        conversationId = Uuid.parse(CONV),
        senderId = Uuid.parse(sender),
        content = content,
        createdAt = Instant.parse(createdAt),
        redactedAt = redactedAt?.let { Instant.parse(it) },
    )

    private fun vm(
        flow: FakeChatFlow,
        subscriber: FakeChatRealtimeSubscriber,
    ) = ChatThreadViewModel(
        conversationId = CONV,
        flow = flow,
        chatRealtimeSubscriber = subscriber,
        viewerIdProvider = ViewerIdProvider { VIEWER },
        nowIso = { "2026-06-01T12:00:00Z" },
        resubscribeDelayMillis = 3000L,
    )

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun inboundNewIdAppends() =
        test {
            val sub = FakeChatRealtimeSubscriber()
            val flow = FakeChatFlow(listOf(ChatThreadOutcome.Loaded(listOf(dto("m1")), null)))
            val viewModel = vm(flow, sub)
            advanceUntilIdle()
            sub.emit(inbound(MSG_ID))
            advanceUntilIdle()
            assertEquals(listOf("m1", MSG_ID), viewModel.rows.value.map { it.id })
        }

    @Test
    fun inboundExistingIdCollapses() =
        test {
            val sub = FakeChatRealtimeSubscriber()
            val flow = FakeChatFlow(listOf(ChatThreadOutcome.Loaded(listOf(dto(MSG_ID)), null)))
            val viewModel = vm(flow, sub)
            advanceUntilIdle()
            sub.emit(inbound(MSG_ID))
            advanceUntilIdle()
            assertEquals(1, viewModel.rows.value.size, "a realtime echo of an existing id dedupes")
        }

    @Test
    fun ownEchoCollapsesOntoOptimisticRow() =
        test {
            val sub = FakeChatRealtimeSubscriber()
            val flow =
                FakeChatFlow(
                    historyOutcomes = listOf(ChatThreadOutcome.Loaded(emptyList(), null)),
                    sendOutcome = SendOutcome.Sent(dto(SERVER_MSG, sender = VIEWER, content = "hi")),
                )
            val viewModel = vm(flow, sub)
            advanceUntilIdle()
            viewModel.send("hi")
            advanceUntilIdle()
            // The optimistic row reconciled to the server id; a realtime echo of that id collapses (D6 —
            // own echoes are NOT dropped, they dedupe).
            sub.emit(inbound(SERVER_MSG, sender = VIEWER, content = "hi"))
            advanceUntilIdle()
            assertEquals(1, viewModel.rows.value.size, "optimistic + server-id echo = one row")
            assertTrue(viewModel.rows.value.single().isOwn)
        }

    @Test
    fun redactionInboundFlipsExistingRow() =
        test {
            val sub = FakeChatRealtimeSubscriber()
            val flow = FakeChatFlow(listOf(ChatThreadOutcome.Loaded(listOf(dto(MSG_ID, content = "secret")), null)))
            val viewModel = vm(flow, sub)
            advanceUntilIdle()
            sub.emit(inbound(MSG_ID, content = null, redactedAt = "2026-06-02T00:00:00Z"))
            advanceUntilIdle()
            val row = viewModel.rows.value.single()
            assertTrue(row.isRedacted)
            assertNull(row.content)
        }

    @Test
    fun resubscribeFiresRestResyncAndDedupesOverlap() =
        test {
            val sub = FakeChatRealtimeSubscriber()
            // Initial page has m1; the resync page has m1 (overlap) + m2.
            val flow =
                FakeChatFlow(
                    listOf(
                        ChatThreadOutcome.Loaded(listOf(dto("m1")), null),
                        ChatThreadOutcome.Loaded(listOf(dto("m1"), dto("m2", createdAt = "2026-06-01T10:05:00Z")), null),
                    ),
                )
            val viewModel = vm(flow, sub)
            advanceUntilIdle()
            assertEquals(1, flow.loadHistoryCount)
            assertEquals(1, sub.subscribeCount)

            sub.endCurrentSubscription()
            advanceTimeBy(3001L)
            advanceUntilIdle()

            assertEquals(2, flow.loadHistoryCount, "a re-subscribe triggers a REST first-page resync (no-outbox recovery)")
            assertEquals(2, sub.subscribeCount, "the subscription re-establishes")
            assertEquals(listOf("m1", "m2"), viewModel.rows.value.map { it.id }, "the overlap dedupes by id")
        }

    @Test
    fun onClearedCancelsTheSubscription() =
        test {
            val sub = FakeChatRealtimeSubscriber()
            val flow = FakeChatFlow(listOf(ChatThreadOutcome.Loaded(emptyList(), null)))
            val viewModel = vm(flow, sub)
            advanceUntilIdle()
            assertTrue(sub.active, "collection started on construction")
            // ViewModel.clear() is internal; clearing the store the VM is registered in invokes it
            // (→ onCleared → viewModelScope cancellation), the same path Nav3 takes on route pop.
            val store = ViewModelStore()
            store.put("chatThread", viewModel)
            store.clear()
            advanceUntilIdle()
            assertFalse(sub.active, "onCleared cancels viewModelScope → the subscription collection stops")
        }

    @Test
    fun realtimeFailureLeavesThreadRestUsable() =
        test {
            val sub = FakeChatRealtimeSubscriber().apply { failNextSubscribe = true }
            val flow = FakeChatFlow(listOf(ChatThreadOutcome.Loaded(listOf(dto("m1")), null)))
            val viewModel = vm(flow, sub)
            // runCurrent (NOT advanceUntilIdle) so the 3000ms re-subscribe backoff stays pending — we
            // observe the post-failure REST-only state, not the eventual re-subscribe.
            testScheduler.runCurrent()
            // The realtime subscribe threw, but REST history loaded and send still works — no error chrome.
            assertTrue(viewModel.historyOutcome.value is ChatThreadOutcome.Loaded)
            assertFalse(sub.active)
            viewModel.send("hi")
            testScheduler.runCurrent()
            assertEquals(1, flow.sendCount, "send still maps via REST despite the realtime failure")
            // Tear down the VM so the pending re-subscribe backoff coroutine is cancelled before runTest's
            // end-of-test drain (else it resumes the delay under Main.immediate and trips the dispatcher).
            ViewModelStore().apply {
                put("vm", viewModel)
                clear()
            }
        }

    @Test
    fun blockedSendSurfacesBlockedOutcomeAndDropsOptimisticRow() =
        test {
            val sub = FakeChatRealtimeSubscriber()
            val flow =
                FakeChatFlow(
                    historyOutcomes = listOf(ChatThreadOutcome.Loaded(emptyList(), null)),
                    sendOutcome = SendOutcome.Blocked,
                )
            val viewModel = vm(flow, sub)
            advanceUntilIdle()
            viewModel.send("hi")
            advanceUntilIdle()
            assertEquals(SendOutcome.Blocked, viewModel.sendOutcome.value)
            assertTrue(viewModel.rows.value.isEmpty(), "a blocked send drops the optimistic bubble")
        }
}
