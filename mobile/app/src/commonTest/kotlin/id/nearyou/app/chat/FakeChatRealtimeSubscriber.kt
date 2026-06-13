package id.nearyou.app.chat

import id.nearyou.app.infra.supabaserealtime.ChatMessageInbound
import id.nearyou.app.infra.supabaserealtime.ChatRealtimeSubscriber
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A scripted [ChatRealtimeSubscriber] for ViewModel tests. Each [subscribe] collection drains a fresh
 * [Channel]; the test pushes inbound messages via [emit] and ends the current subscription via
 * [endCurrentSubscription] (to exercise the re-subscribe + REST-resync path). [failNextSubscribe] makes
 * the next collection throw (the realtime-failure degrade-to-REST path). [subscribeCount] / [active]
 * let the test assert (re)subscribe + teardown.
 */
@OptIn(ExperimentalUuidApi::class)
class FakeChatRealtimeSubscriber : ChatRealtimeSubscriber {
    var subscribeCount: Int = 0
        private set
    var active: Boolean = false
        private set
    var failNextSubscribe: Boolean = false

    private var channel: Channel<ChatMessageInbound>? = null

    override fun subscribe(conversationId: Uuid): Flow<ChatMessageInbound> =
        flow {
            subscribeCount++
            if (failNextSubscribe) {
                failNextSubscribe = false
                throw RuntimeException("realtime transport failure")
            }
            val ch = Channel<ChatMessageInbound>(Channel.UNLIMITED)
            channel = ch
            active = true
            try {
                for (message in ch) emit(message)
            } finally {
                active = false
            }
        }

    suspend fun emit(message: ChatMessageInbound) {
        channel?.send(message)
    }

    fun endCurrentSubscription() {
        channel?.close()
    }
}
