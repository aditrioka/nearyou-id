package id.nearyou.app.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Wire DTOs for the chat data plane. Per `chat-foundation/specs/chat-conversations/spec.md`:
 *  - Redacted messages MUST serialize with `content: null` and SHALL NOT include
 *    `redaction_reason` at all in the JSON body. The original content stays in the DB for
 *    audit but is never returned.
 *  - Conversation list rows surface the OTHER participant's profile via LEFT JOIN
 *    `visible_users` + COALESCE-to-placeholder (`username = 'akun_dihapus'`,
 *    `display_name = 'Akun Dihapus'`, `is_premium = FALSE`) so a shadow-banned partner masks
 *    out — the conversation row still surfaces, only the partner's identity is hidden.
 *
 * The send/list-messages serialization NULL-masks `content` via [chatMessageJson] (manual
 * JsonObject build) because the app-wide `ContentNegotiation` has `explicitNulls = false` —
 * a `Serializable` `content: String?` would silently drop the `"content": null` field, and
 * the spec scenario "Redacted message returns NULL content + redacted_at" relies on the
 * caller observing `content == null` explicitly in the body shape.
 */
@Serializable
data class CreateConversationRequest(
    @SerialName("recipient_user_id") val recipientUserId: String? = null,
)

/**
 * Send-message request body. The three embed fields are accepted for forward compatibility
 * with the deferred `chat-embedded-posts` change, but the route handler ALWAYS ignores them
 * (with a structured WARN log per ignored field) and inserts the row with all three NULL.
 */
@Serializable
data class SendMessageRequest(
    val content: String? = null,
    @SerialName("embedded_post_id") val embeddedPostId: String? = null,
    @SerialName("embedded_post_snapshot") val embeddedPostSnapshot: JsonElement? = null,
    @SerialName("embedded_post_edit_id") val embeddedPostEditId: String? = null,
)

@Serializable
data class PartnerProfileDto(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_premium") val isPremium: Boolean,
)

@Serializable
data class ConversationDto(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
)

@Serializable
data class ParticipantDto(
    @SerialName("user_id") val userId: String,
    val slot: Int,
    @SerialName("joined_at") val joinedAt: String,
    @SerialName("left_at") val leftAt: String? = null,
)

@Serializable
data class CreateConversationResponse(
    val conversation: ConversationDto,
    val participants: List<ParticipantDto>,
)

@Serializable
data class ConversationListItemDto(
    val conversation: ConversationDto,
    val partner: PartnerProfileDto,
)

@Serializable
data class ConversationListResponse(
    val conversations: List<ConversationListItemDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class MessageListResponse(
    val messages: List<JsonObject>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class SendMessageError(val error: String)

const val BLOCKED_BODY_TEXT: String = "Tidak dapat mengirim pesan ke user ini"

/**
 * Manual JSON build for a chat-message row. NULL-masks `content` when [redactedAt] is non-null
 * AND deliberately omits the `redaction_reason` field from the body shape regardless of value.
 */
fun chatMessageJson(
    id: String,
    conversationId: String,
    senderId: String,
    content: String?,
    createdAt: String,
    redactedAt: String?,
): JsonObject =
    buildJsonObject {
        put("id", JsonPrimitive(id))
        put("conversation_id", JsonPrimitive(conversationId))
        put("sender_id", JsonPrimitive(senderId))
        if (redactedAt != null) {
            put("content", JsonNull)
            put("redacted_at", JsonPrimitive(redactedAt))
        } else {
            put("content", content?.let(::JsonPrimitive) ?: JsonNull)
        }
        put("created_at", JsonPrimitive(createdAt))
        // redaction_reason is intentionally NEVER included on the chat data plane — admin-only.
    }
