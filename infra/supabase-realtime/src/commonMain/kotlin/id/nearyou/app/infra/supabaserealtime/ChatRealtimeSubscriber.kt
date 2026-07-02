package id.nearyou.app.infra.supabaserealtime

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * mobile-chat-screen — the vendor-SDK-FREE subscribe-side realtime seam for 1:1 chat.
 *
 * The mirror of the backend's publish-side `ChatRealtimeClient` (which lives in the JVM-only
 * `:core:domain`). The mobile app is Kotlin Multiplatform and CANNOT depend on `:core:domain`
 * (JVM-only), so this consumer-side interface lives here in the KMP `:infra:supabase-realtime`
 * module instead — a deliberate apply-phase correction of the proposal's "interface in
 * :core:domain" plan (design.md D2). It carries NO vendor (`io.github.jan-tennert.supabase.*`)
 * import; the supabase-kt SDK is fenced to [SupabaseChatRealtimeSubscriber] in this same module,
 * satisfying CLAUDE.md § Critical invariants ("No vendor SDK import outside `:infra:*`"). The app
 * depends on this module to obtain the interface type; the supabase-kt transitive dependency is
 * `implementation`-scoped and never reaches the app's compile classpath.
 *
 * The future post-swap (Month 15+) Ktor-WebSocket transport implements this same interface.
 */
interface ChatRealtimeSubscriber {
    /**
     * Cold flow of inbound messages for one conversation. Collecting the flow subscribes (fetching
     * a fresh realtime token via [RealtimeTokenProvider] and joining channel
     * `realtime:conversation:<lowercase-uuid>`); cancelling collection unsubscribes (the channel is
     * left). A transport drop triggers a rejoin with a freshly-fetched token. The flow never
     * completes on its own and never emits the subscriber's own un-echoed sends — REST history +
     * optimistic append cover those; the ViewModel merges this flow by message id (dedupe).
     */
    @OptIn(ExperimentalUuidApi::class)
    fun subscribe(conversationId: Uuid): Flow<ChatMessageInbound>
}

/**
 * A realtime inbound message, projected from the broadcast payload owned by the
 * `chat-realtime-broadcast` capability. Mirrors the 9-key snake_case wire (minus `redaction_reason`,
 * which is never serialized). [content] is `null` when the message is redacted ([redactedAt]
 * non-null). [senderId] is carried so the ViewModel can apply own-vs-other alignment without a
 * re-fetch — shadow-ban filtering is server-authoritative (the client carries no ban signal;
 * design.md D6).
 *
 * The three `embedded_*` fields (chat-embedded-posts) surface the shared-post context card:
 * [embeddedPostId] is the live post id (null after the source post is hard-deleted — the snapshot
 * still renders in a "deleted" state); [embeddedPostSnapshot] is the self-contained coordinate-free
 * snapshot (a plain Kotlin model decoded from the snapshot JSON at the
 * [SupabaseChatRealtimeSubscriber] boundary — never a vendor type); [embeddedPostEditId] is the
 * version-at-share-time anchor the thread compares against the live post for the edited-since-shared
 * banner. All three are null for a plain text / redacted message.
 */
@OptIn(ExperimentalUuidApi::class)
data class ChatMessageInbound(
    val id: Uuid,
    val conversationId: Uuid,
    val senderId: Uuid,
    val content: String?,
    val createdAt: Instant,
    val redactedAt: Instant?,
    val embeddedPostId: Uuid? = null,
    val embeddedPostSnapshot: EmbeddedPostSnapshot? = null,
    val embeddedPostEditId: Uuid? = null,
)

/**
 * The coordinate-free, self-contained snapshot of a shared post (chat-embedded-posts), decoded from
 * the `embedded_post_snapshot` JSONB built server-side. A plain Kotlin model — NO vendor/Supabase
 * symbol — carrying ONLY display fields: the author handle + display name, the post `content`, the
 * `cityName` label, and the `createdAt` / `editedAt` ISO-8601 timestamps. It carries NO coordinate
 * (`latitude` / `longitude` / `display_location`) and no author UUID (spatial-fuzzing invariant; the
 * key set mirrors the server's allowlist exactly). [editedAt] is null when the post was unedited at
 * share time (it drives the card's own "Diedit" label, NOT the edited-since-shared banner — that
 * uses [ChatMessageInbound.embeddedPostEditId]).
 */
@Serializable
data class EmbeddedPostSnapshot(
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val cityName: String,
    val createdAt: String,
    val editedAt: String? = null,
)

/**
 * Seam for fetching the short-lived HS256 realtime companion token (`GET /api/v1/realtime/token`).
 * Defined here (vendor-free) so [SupabaseChatRealtimeSubscriber] can fetch a fresh token before
 * each (re)join without this module depending on `:mobile:app` (which owns the Ktor client + the
 * `RealtimeTokenApiClient` that implements this). The implementation returns the JWS string from
 * the shipped `{ "token", "expires_in" }` envelope; the token is never persisted or logged.
 */
fun interface RealtimeTokenProvider {
    suspend fun fetchToken(): String
}
