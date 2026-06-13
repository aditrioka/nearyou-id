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
 * One conversation row, mirroring the SHIPPED backend `ConversationDto`
 * (`backend/ktor/.../chat/ChatDtos.kt`). **Casing pinned to the wire (design D9):** `id` is bare;
 * `created_at` / `last_message_at` are `@SerialName` snake_case (verified 2026-06-13 against the
 * deployed handler — see PR body wire-verification note). `last_message_at` is nullable (a freshly
 * created conversation with no messages yet).
 */
@Serializable
data class ConversationDto(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
)

/**
 * The OTHER participant's profile, mirroring the shipped `PartnerProfileDto`. A shadow-banned or
 * deleted partner is masked server-side via `LEFT JOIN visible_users` + COALESCE placeholders
 * (`username = 'akun_dihapus'`, `display_name = 'Akun Dihapus'`, `is_premium = FALSE`) — the row still
 * surfaces, only the identity is hidden. [id] is the partner's user UUID (PII) and MUST NOT be rendered
 * or carried into UI state / the back stack.
 */
@Serializable
data class PartnerProfileDto(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_premium") val isPremium: Boolean,
)

/**
 * One conversation-list item, mirroring the shipped `ConversationListItemDto` — a **NESTED** shape
 * (`{ "conversation": {…}, "partner": {…} }`), NOT a flattened row. A test asserts the nesting parses
 * field-by-field (the Nearby D10 casing trap).
 */
@Serializable
data class ConversationListItemDto(
    val conversation: ConversationDto,
    val partner: PartnerProfileDto,
)

/**
 * The list-conversations envelope, mirroring the shipped `ConversationListResponse`: `conversations`
 * + a `@SerialName("next_cursor")` snake_case cursor (UNLIKE the timelines' bare camelCase
 * `nextCursor` — the same wire that bit Nearby). `next_cursor` tolerates absence/null.
 */
@Serializable
data class ConversationListResponseDto(
    val conversations: List<ConversationListItemDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** `POST /api/v1/conversations` body — `{ "recipient_user_id": "<uuid>" }` (shipped `recipient_user_id`). */
@Serializable
data class CreateConversationRequestDto(
    @SerialName("recipient_user_id") val recipientUserId: String,
)

/**
 * The create-or-return 201/200 body, mirroring the shipped `CreateConversationResponse`
 * (`{ "conversation": {…}, "participants": [...] }`). Only the conversation [id] is load-bearing for
 * the mobile flow (navigate to the thread); `participants` is tolerated-and-ignored via the shared
 * `Json`'s `ignoreUnknownKeys`.
 */
@Serializable
data class CreatedConversationDto(
    val conversation: ConversationDto,
)

/** Low-level result of a list-conversations fetch (mirrors `GlobalApiResult`). Non-2xx is a VALUE. */
sealed interface ConversationsApiResult {
    data class Success(val body: ConversationListResponseDto) : ConversationsApiResult

    data class HttpError(val status: Int) : ConversationsApiResult

    data class NetworkError(val cause: Throwable) : ConversationsApiResult
}

/**
 * Low-level result of a create-or-return call. The 201 (new) / 200 (existing) split is preserved as
 * two distinct members even though the mobile flow treats both as "navigate to the thread" — so the
 * MockEngine test can assert the differential. 400 → [SelfConversation], 404 → [RecipientNotFound]
 * (the distinct split per tasks 6.2/12.2), 403 → [Blocked]; other non-2xx → [HttpError]; transport →
 * [NetworkError]. A non-2xx is a VALUE, never a thrown exception.
 */
sealed interface CreateConversationApiResult {
    data class Created(val body: CreatedConversationDto) : CreateConversationApiResult

    data class Returned(val body: CreatedConversationDto) : CreateConversationApiResult

    data object Blocked : CreateConversationApiResult

    data object SelfConversation : CreateConversationApiResult

    data object RecipientNotFound : CreateConversationApiResult

    data class HttpError(val status: Int) : CreateConversationApiResult

    data class NetworkError(val cause: Throwable) : CreateConversationApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for `GET/POST /api/v1/conversations`. The Bearer
 * `Authorization` header + 401 refresh are owned by the shipped `Auth` plugin — this client MUST NOT
 * reimplement them. NO `X-Session-Id` header (the conversation endpoints are not session-soft-capped).
 * MUST NOT `println`/log bodies (they carry the partner's profile + the `recipient_user_id` UUID).
 */
class ConversationsApiClient(
    private val client: HttpClient,
) {
    suspend fun getConversations(cursor: String? = null): ConversationsApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/conversations") {
                    cursor?.let { parameter("cursor", it) }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return ConversationsApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.OK) {
            return try {
                ConversationsApiResult.Success(response.body<ConversationListResponseDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                ConversationsApiResult.NetworkError(cause)
            }
        }
        return ConversationsApiResult.HttpError(status = response.status.value)
    }

    suspend fun createOrReturnConversation(recipientUserId: String): CreateConversationApiResult {
        val response: HttpResponse =
            try {
                client.post("/api/v1/conversations") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateConversationRequestDto(recipientUserId = recipientUserId))
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return CreateConversationApiResult.NetworkError(cause)
            }
        return when (response.status.value) {
            201 -> parseCreated(response) { CreateConversationApiResult.Created(it) }
            200 -> parseCreated(response) { CreateConversationApiResult.Returned(it) }
            403 -> CreateConversationApiResult.Blocked
            400 -> CreateConversationApiResult.SelfConversation
            404 -> CreateConversationApiResult.RecipientNotFound
            else -> CreateConversationApiResult.HttpError(status = response.status.value)
        }
    }

    private suspend fun parseCreated(
        response: HttpResponse,
        wrap: (CreatedConversationDto) -> CreateConversationApiResult,
    ): CreateConversationApiResult =
        try {
            wrap(response.body<CreatedConversationDto>())
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            // A 2xx whose body fails to parse is a transport/contract failure, not a success.
            CreateConversationApiResult.NetworkError(cause)
        }
}
