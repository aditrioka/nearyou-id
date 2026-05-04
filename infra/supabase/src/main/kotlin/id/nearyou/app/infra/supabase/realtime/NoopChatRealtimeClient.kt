package id.nearyou.app.infra.supabase.realtime

import id.nearyou.app.core.domain.chat.ChatMessageBroadcast
import id.nearyou.app.core.domain.chat.ChatRealtimeClient
import id.nearyou.app.core.domain.chat.PublishResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * No-op [ChatRealtimeClient] for unit-test suites that don't want the real adapter (e.g.,
 * the existing `ChatSendRouteTest` / `ChatSendRateLimitTest` chat-foundation suites that
 * don't care about broadcast). Returns [PublishResult.Success] immediately and captures
 * an invocation count via [invocationCount] for sanity assertions.
 */
class NoopChatRealtimeClient : ChatRealtimeClient {
    private val counter = AtomicInteger(0)

    val invocationCount: Int
        get() = counter.get()

    override suspend fun publish(
        conversationId: UUID,
        message: ChatMessageBroadcast,
    ): PublishResult {
        counter.incrementAndGet()
        return PublishResult.Success
    }
}
