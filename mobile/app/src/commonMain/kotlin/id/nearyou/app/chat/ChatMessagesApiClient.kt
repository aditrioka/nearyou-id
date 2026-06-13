package id.nearyou.app.chat

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * One chat message, mirroring the SHIPPED backend `chatMessageJson` wire shape
 * (`backend/ktor/.../chat/ChatDtos.kt`). **Casing pinned to the wire (design D9):** `id` bare;
 * `conversation_id` / `sender_id` / `created_at` / `redacted_at` are `@SerialName` snake_case.
 *  - [content] is `null` for a redacted message (the backend NULL-masks it).
 *  - [redactedAt] is **absent** on a non-redacted row (the handler omits the key entirely) → defaulted
 *    to `null`; a present `redacted_at` marks a redacted message.
 *  - `redaction_reason` is NEVER on the wire and is deliberately NOT declared here (a test asserts a
 *    body carrying it does not surface any model property — it is ignored via the shared `Json`).
 * [senderId] is a UUID used ONLY for the own-vs-other decision; it MUST NOT be rendered (PII).
 */
@Serializable
data class ChatMessageDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("redacted_at") val redactedAt: String? = null,
)

/**
 * The list-messages envelope, mirroring the shipped `MessageListResponse`: `messages` + a
 * `@SerialName("next_cursor")` snake_case cursor (tolerates absence/null).
 */
@Serializable
data class MessageListResponseDto(
    val messages: List<ChatMessageDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * `POST /api/v1/chat/{id}/messages` body — `{ "content": "<text>" }` ONLY. The three `embedded_*`
 * fields the backend tolerates are deliberately NOT sent (design D7); a test asserts the body carries
 * no `embedded_*` key.
 */
@Serializable
data class SendMessageRequestDto(
    val content: String,
)

/** Low-level result of a list-messages fetch. Non-2xx is a VALUE. */
sealed interface ChatMessagesApiResult {
    data class Success(val body: MessageListResponseDto) : ChatMessagesApiResult

    data class HttpError(val status: Int) : ChatMessagesApiResult

    data class NetworkError(val cause: Throwable) : ChatMessagesApiResult
}

/**
 * Low-level result of a send. `201` → [Created] (the inserted [ChatMessageDto]); any non-201 →
 * [HttpError] (400 length/empty, 403 block, 404 unknown conversation, 429 rate-limit, 5xx); transport →
 * [NetworkError]. A non-2xx is a VALUE, never a thrown exception.
 */
sealed interface SendMessageApiResult {
    data class Created(val message: ChatMessageDto) : SendMessageApiResult

    data class HttpError(val status: Int) : SendMessageApiResult

    data class NetworkError(val cause: Throwable) : SendMessageApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for the conversation-scoped message sub-resources
 * (`GET` / `POST /api/v1/chat/{conversation_id}/messages`). Bearer + 401 refresh owned by the shipped
 * `Auth` plugin (MUST NOT be reimplemented). NO `X-Session-Id` header. MUST NOT `println`/log bodies
 * (message content + sender UUIDs).
 */
class ChatMessagesApiClient(
    private val client: HttpClient,
) {
    suspend fun getMessages(
        conversationId: String,
        cursor: String? = null,
    ): ChatMessagesApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/chat/$conversationId/messages") {
                    cursor?.let { parameter("cursor", it) }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return ChatMessagesApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.OK) {
            return try {
                ChatMessagesApiResult.Success(response.body<MessageListResponseDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                ChatMessagesApiResult.NetworkError(cause)
            }
        }
        return ChatMessagesApiResult.HttpError(status = response.status.value)
    }

    suspend fun sendMessage(
        conversationId: String,
        content: String,
    ): SendMessageApiResult {
        val response: HttpResponse =
            try {
                client.post("/api/v1/chat/$conversationId/messages") {
                    contentType(ContentType.Application.Json)
                    // Body is { content } ONLY — no embedded_* keys (design D7).
                    setBody(SendMessageRequestDto(content = content))
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return SendMessageApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.Created) {
            return try {
                SendMessageApiResult.Created(response.body<ChatMessageDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                SendMessageApiResult.NetworkError(cause)
            }
        }
        return SendMessageApiResult.HttpError(status = response.status.value)
    }
}
