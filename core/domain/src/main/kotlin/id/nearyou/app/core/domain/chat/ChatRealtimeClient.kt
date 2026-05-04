package id.nearyou.app.core.domain.chat

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.util.UUID

/**
 * Server-side chat-broadcast publisher abstraction. The interface lives in `:core:domain` so
 * the chat send handler in `:backend:ktor` can depend on it without pulling any vendor SDK
 * transitively, per `CLAUDE.md` § Critical invariants ("No vendor SDK import outside `:infra:*`").
 *
 * **Single method:** [publish]. Subscriber-side `subscribe`/`unsubscribe` is a mobile-client
 * concern and is intentionally NOT modeled here (the `docs/04-Architecture.md:139-149` sketch
 * shows the consumer side; this interface is the publisher side). The post-swap
 * `KtorWebSocketChatClient` (Month 15+, backed by Redis Streams) implements the same interface.
 *
 * **Retry policy is internal to implementations.** [SupabaseBroadcastChatClient] performs 4
 * total attempts (1 initial + 3 retries) with ~100/300/900 ms backoff per the canonical
 * contract at `docs/05-Implementation.md:1213-1216`. Callers invoke [publish] exactly once
 * per send and treat the returned [PublishResult] as final.
 *
 * **Privileged binding non-goal.** The DI binding equipped with the Supabase service role
 * key bypasses RLS by design (the V15 `participants_can_subscribe` policy gates SUBSCRIBERS,
 * not the server-originated publisher). The ONLY caller permitted to invoke [publish] in
 * this change is the chat send route handler at `POST /api/v1/chat/{conversation_id}/messages`.
 * Any future caller MUST replicate the chat-foundation auth + active-participant +
 * bidirectional-block check pre-publish (paper-trail non-goal per `chat-realtime-broadcast`
 * spec § Privileged publish path).
 */
interface ChatRealtimeClient {
    suspend fun publish(
        conversationId: UUID,
        message: ChatMessageBroadcast,
    ): PublishResult
}

/**
 * Wire-shape projection of a `chat_messages` row passed by the chat send handler to
 * [ChatRealtimeClient.publish]. Mirrors the columns surfaced by `GET /api/v1/chat/{id}/messages`
 * with the chat-foundation read-path render policy applied: `redaction_reason` is NEVER
 * serialized; when [redactedAt] is non-null, the wire payload's `content` field is `null`
 * regardless of the in-memory [content] value.
 *
 * The three `embedded_*` fields are present-with-null in this change (the future
 * `chat-embedded-posts` change populates them); serializing them now keeps the mobile
 * parser stable across that ship.
 *
 * **JSON naming via per-field [SerialName] annotations.** Per `chat-realtime-broadcast`
 * design § D13, the wire format uses snake_case keys. Each field carries an explicit
 * [SerialName] annotation matching the wire key — NO global `JsonNamingStrategy` is
 * configured. The publisher implementation hand-rolls a `JsonObject` matching these keys
 * (the [Contextual] serializers for [UUID] / [Instant] are not invoked at runtime; the
 * annotations are present so the wire contract is visible at the data-class definition).
 *
 * **`redaction_reason` is intentionally NOT a property** of this class — assert at
 * compile-time via the integration-test reflection check.
 */
@Serializable
data class ChatMessageBroadcast(
    @Contextual val id: UUID,
    @SerialName("conversation_id") @Contextual val conversationId: UUID,
    @SerialName("sender_id") @Contextual val senderId: UUID,
    val content: String?,
    @SerialName("embedded_post_id") @Contextual val embeddedPostId: UUID?,
    @SerialName("embedded_post_snapshot") val embeddedPostSnapshot: JsonElement?,
    @SerialName("embedded_post_edit_id") @Contextual val embeddedPostEditId: UUID?,
    @SerialName("created_at") @Contextual val createdAt: Instant,
    @SerialName("redacted_at") @Contextual val redactedAt: Instant?,
)

/**
 * Result of a [ChatRealtimeClient.publish] invocation. The chat send handler logs a
 * structured WARN line on [Failure] (and on any thrown `Throwable` caught after the
 * chat-foundation tx commits) but does NOT alter the HTTP response: the row is already
 * persisted and mobile REST resync recovers a missed broadcast.
 *
 * [Failure.reason] is the fully-qualified class name of the LAST exception captured during
 * the implementation's retry loop (e.g., `"java.io.IOException"`,
 * `"java.net.SocketTimeoutException"`). Per `chat-realtime-broadcast` design § D12, this
 * keeps the WARN log's `error_class` field consistent across both the Failure path and
 * the thrown-exception path.
 */
sealed class PublishResult {
    data object Success : PublishResult()

    data class Failure(val reason: String) : PublishResult()
}
