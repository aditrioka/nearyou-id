package id.nearyou.app.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * Shared keyset cursor for endpoints that paginate by `(timestamp, uuid)` pairs:
 *  - Nearby timeline: `(created_at DESC, post_id DESC)`
 *  - Blocks list: `(created_at DESC, blocked_user_id DESC)`
 *
 * Wire format: base64url-encoded JSON `{"c":"<ISO-8601 instant>","i":"<uuid>"}`. No
 * server signature — the cursor encodes data the viewer could see on the previous page,
 * so HMAC adds key-rotation burden for no security gain.
 *
 * Parsing failures (bad base64, malformed JSON, bad ISO-8601, bad UUID) all raise
 * [InvalidCursorException], which routes catch to return 400 `invalid_cursor`.
 */
data class Cursor(
    val createdAt: Instant,
    val id: UUID,
)

class InvalidCursorException : RuntimeException("invalid cursor")

@Serializable
private data class CursorPayload(val c: String, val i: String)

private val json = Json { ignoreUnknownKeys = true }

fun encodeCursor(cursor: Cursor): String {
    val payload = CursorPayload(c = cursor.createdAt.toString(), i = cursor.id.toString())
    val bytes = json.encodeToString(CursorPayload.serializer(), payload).toByteArray()
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun decodeCursor(encoded: String): Cursor {
    val bytes =
        try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw InvalidCursorException()
        }
    val payload =
        try {
            json.decodeFromString(CursorPayload.serializer(), String(bytes))
        } catch (_: Exception) {
            throw InvalidCursorException()
        }
    return try {
        Cursor(createdAt = Instant.parse(payload.c), id = UUID.fromString(payload.i))
    } catch (_: DateTimeParseException) {
        throw InvalidCursorException()
    } catch (_: IllegalArgumentException) {
        throw InvalidCursorException()
    }
}
