package id.nearyou.app.chat

import id.nearyou.app.common.InvalidCursorException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * Chat cursor encoding/decoding helpers per `chat-foundation` design.md § D4.
 *
 * Two cursor shapes:
 *  - Messages cursor: `{ts: <ISO-8601>, id: <UUID>}` for `(created_at DESC, id DESC)` keyset
 *    pagination of `chat_messages`.
 *  - Conversations cursor: `{ts: <ISO-8601>?, id: <UUID>}` for the conversation list, which is
 *    ordered `last_message_at DESC NULLS LAST, created_at DESC`. The `ts` field is nullable
 *    because the NULLS-LAST tail block has `last_message_at = NULL`; pagination across that
 *    tail uses `created_at` as the secondary key with `id` as the tiebreaker.
 *
 * Wire format: base64url-encoded JSON. Malformed input throws [InvalidCursorException], which
 * routes catch to return 400 `invalid_cursor` (matching the existing `common.Cursor` contract).
 */
data class MessagesCursor(
    val createdAt: Instant,
    val id: UUID,
)

data class ConversationsCursor(
    /** `last_message_at` of the previous-page-last row; null if that row had no messages yet. */
    val lastMessageAt: Instant?,
    /** `created_at` of the previous-page-last row — used as secondary key when `lastMessageAt` is null. */
    val createdAt: Instant,
    val id: UUID,
)

@Serializable
private data class MessagesCursorPayload(val ts: String, val id: String)

@Serializable
private data class ConversationsCursorPayload(
    val lmt: String? = null,
    val ct: String,
    val id: String,
)

private val json = Json { ignoreUnknownKeys = true }

fun encodeMessagesCursor(
    createdAt: Instant,
    id: UUID,
): String {
    val payload = MessagesCursorPayload(ts = createdAt.toString(), id = id.toString())
    val bytes = json.encodeToString(MessagesCursorPayload.serializer(), payload).toByteArray()
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun decodeMessagesCursor(token: String): MessagesCursor {
    val raw =
        try {
            Base64.getUrlDecoder().decode(token)
        } catch (_: IllegalArgumentException) {
            throw InvalidCursorException()
        }
    val payload =
        try {
            json.decodeFromString(MessagesCursorPayload.serializer(), String(raw))
        } catch (_: Exception) {
            throw InvalidCursorException()
        }
    return try {
        MessagesCursor(
            createdAt = Instant.parse(payload.ts),
            id = UUID.fromString(payload.id),
        )
    } catch (_: DateTimeParseException) {
        throw InvalidCursorException()
    } catch (_: IllegalArgumentException) {
        throw InvalidCursorException()
    }
}

fun encodeConversationsCursor(
    lastMessageAt: Instant?,
    createdAt: Instant,
    id: UUID,
): String {
    val payload =
        ConversationsCursorPayload(
            lmt = lastMessageAt?.toString(),
            ct = createdAt.toString(),
            id = id.toString(),
        )
    val bytes = json.encodeToString(ConversationsCursorPayload.serializer(), payload).toByteArray()
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun decodeConversationsCursor(token: String): ConversationsCursor {
    val raw =
        try {
            Base64.getUrlDecoder().decode(token)
        } catch (_: IllegalArgumentException) {
            throw InvalidCursorException()
        }
    val payload =
        try {
            json.decodeFromString(ConversationsCursorPayload.serializer(), String(raw))
        } catch (_: Exception) {
            throw InvalidCursorException()
        }
    return try {
        ConversationsCursor(
            lastMessageAt = payload.lmt?.let { Instant.parse(it) },
            createdAt = Instant.parse(payload.ct),
            id = UUID.fromString(payload.id),
        )
    } catch (_: DateTimeParseException) {
        throw InvalidCursorException()
    } catch (_: IllegalArgumentException) {
        throw InvalidCursorException()
    }
}
