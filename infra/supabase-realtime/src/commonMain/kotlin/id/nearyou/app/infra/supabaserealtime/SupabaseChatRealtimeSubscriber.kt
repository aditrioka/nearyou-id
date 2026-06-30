package id.nearyou.app.infra.supabaserealtime

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * mobile-chat-screen — the supabase-kt-backed implementation of [ChatRealtimeSubscriber] (design D1/D2).
 * This is the ONLY source file in the codebase permitted to import the supabase-kt vendor SDK
 * (`io.github.jan.supabase.*`); the vendor-free [ChatRealtimeSubscriber] / [ChatMessageInbound] /
 * [RealtimeTokenProvider] seam lives alongside it so `:mobile:app` consumes the interface type without
 * the vendor symbol reaching its compile classpath (CLAUDE.md invariant #16 — no vendor SDK import
 * outside `:infra:*`).
 *
 * **Channel + private RLS.** The backend publishes to the realtime TOPIC `conversation:<lowercase-uuid>`
 * with `private = true` (see `:infra:supabase` `SupabaseBroadcastChatClient` — it constructs the
 * `realtime:conversation:<uuid>` identifier and strips the `realtime:` prefix to derive the topic the
 * V15 `participants_can_subscribe` RLS policy evaluates). supabase-kt's [channel] takes that bare topic
 * (`conversation:<uuid>`) and prepends `realtime:` internally, so the on-wire channel identifier is the
 * canonical `realtime:conversation:<uuid>` (`chat-realtime-broadcast` § Channel name format). The channel
 * is opened `isPrivate = true` so Supabase enforces the participant RLS policy against the HS256 token.
 *
 * **Token + reconnect (design D4).** The Supabase client is created with an `accessToken` provider that
 * calls [tokenProvider] (`RealtimeTokenApiClient` in `:mobile:app`, which hits `GET /api/v1/realtime/token`).
 * supabase-kt invokes this provider on every (re)connection of the realtime socket, so a transport drop
 * re-fetches a FRESH HS256 token before the rejoin rather than reusing a near-expiry one. The token is
 * never persisted or logged — it lives only for the lifetime of the socket connection inside the SDK.
 *
 * **Lifecycle.** [subscribe] returns a cold [Flow]: collecting it creates the client, joins the channel,
 * and emits each inbound `chat_message` broadcast; cancelling collection runs the `finally` block which
 * closes the client (tearing down the channel + socket). The flow never completes on its own.
 */
@OptIn(ExperimentalUuidApi::class)
class SupabaseChatRealtimeSubscriber(
    private val supabaseUrl: String,
    private val supabaseKey: String,
    private val tokenProvider: RealtimeTokenProvider,
) : ChatRealtimeSubscriber {
    override fun subscribe(conversationId: Uuid): Flow<ChatMessageInbound> =
        flow {
            val client = createClient()
            try {
                val channel =
                    client.channel(channelTopic(conversationId)) {
                        // Private channel → Supabase evaluates the V15 participants_can_subscribe RLS
                        // policy against the HS256 token's `sub` before authorizing the subscription.
                        isPrivate = true
                    }
                // broadcastFlow deserializes the broadcast message's `payload` (the 9-key snake_case
                // object) into RealtimeBroadcastPayload; subscribe() joins the channel. Map → the
                // vendor-free inbound model (embedded_* dropped, redaction_reason never read).
                val inbound = channel.broadcastFlow<RealtimeBroadcastPayload>(BROADCAST_EVENT).map { it.toInbound() }
                channel.subscribe()
                emitAll(inbound)
            } finally {
                // Non-suspend teardown: closing the client unsubscribes every channel + closes the
                // realtime socket. Runs on collection cancellation (route popped / ViewModel cleared).
                client.close()
            }
        }

    private fun createClient(): SupabaseClient =
        createSupabaseClient(supabaseUrl = supabaseUrl, supabaseKey = supabaseKey) {
            // Custom JWT provider: supabase-kt calls this on every (re)connection, so each rejoin
            // fetches a fresh realtime token (design D4). We are NOT using the GoTrue/Auth module —
            // the HS256 companion token from the backend is the only credential the socket needs.
            accessToken = { tokenProvider.fetchToken() }
            install(Realtime)
        }

    private fun channelTopic(conversationId: Uuid): String = "$CHANNEL_TOPIC_PREFIX$conversationId"

    companion object {
        /** Broadcast event name — MUST match the publisher's `BROADCAST_EVENT` (`:infra:supabase`). */
        const val BROADCAST_EVENT: String = "chat_message"

        /** Topic prefix supabase-kt receives; it prepends `realtime:` to form the wire identifier. */
        const val CHANNEL_TOPIC_PREFIX: String = "conversation:"
    }
}

/**
 * The realtime broadcast `payload`, mirroring the 9-key snake_case wire owned by the
 * `chat-realtime-broadcast` capability (verified against the publisher's `payloadFor` in
 * `:infra:supabase` `SupabaseBroadcastChatClient`). The three `embedded_*` keys are decoded into the
 * vendor-free [ChatMessageInbound] embed fields (chat-embedded-posts); the snapshot JsonElement is
 * decoded to the plain [EmbeddedPostSnapshot] model at this boundary (design D7). `redaction_reason`
 * is intentionally NOT declared — it is never on the wire. [content] is `null` when the message is
 * redacted ([redactedAt] non-null).
 */
@Serializable
internal data class RealtimeBroadcastPayload(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("redacted_at") val redactedAt: String? = null,
    @SerialName("embedded_post_id") val embeddedPostId: String? = null,
    @SerialName("embedded_post_snapshot") val embeddedPostSnapshot: JsonElement? = null,
    @SerialName("embedded_post_edit_id") val embeddedPostEditId: String? = null,
)

/** Lenient JSON for decoding the embedded-post snapshot JsonElement at the infra boundary. */
private val embeddedSnapshotJson = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalUuidApi::class)
internal fun RealtimeBroadcastPayload.toInbound(): ChatMessageInbound =
    ChatMessageInbound(
        id = Uuid.parse(id),
        conversationId = Uuid.parse(conversationId),
        senderId = Uuid.parse(senderId),
        // The publisher already nulls content on a redacted row; pass it through (null when redacted).
        content = content,
        createdAt = Instant.parse(createdAt),
        redactedAt = redactedAt?.let { Instant.parse(it) },
        embeddedPostId = embeddedPostId?.let { Uuid.parse(it) },
        embeddedPostSnapshot =
            embeddedPostSnapshot?.takeIf { it != JsonNull }?.let {
                embeddedSnapshotJson.decodeFromJsonElement(EmbeddedPostSnapshot.serializer(), it)
            },
        embeddedPostEditId = embeddedPostEditId?.let { Uuid.parse(it) },
    )
