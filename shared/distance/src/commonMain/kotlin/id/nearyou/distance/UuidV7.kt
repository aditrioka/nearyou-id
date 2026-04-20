package id.nearyou.distance

import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Minimal UUIDv7 factory: 48-bit unix-ms timestamp (big-endian) || 4-bit version ||
 * 12 bits random || 2-bit variant || 62 bits random.
 *
 * Enough for the signup / post-creation needs — monotonic within a millisecond
 * boundary requires extra state we don't need yet. Randomness gives collision
 * resistance comparable to v4.
 */
@OptIn(ExperimentalUuidApi::class)
object UuidV7 {
    fun next(
        millis: Long = unixMillis(),
        random: Random = Random.Default,
    ): Uuid {
        val bytes = ByteArray(16)

        // Bytes 0..5 : 48-bit big-endian millis.
        bytes[0] = (millis ushr 40).toByte()
        bytes[1] = (millis ushr 32).toByte()
        bytes[2] = (millis ushr 24).toByte()
        bytes[3] = (millis ushr 16).toByte()
        bytes[4] = (millis ushr 8).toByte()
        bytes[5] = millis.toByte()

        // Bytes 6..15 : random (we'll overwrite the version/variant bits).
        random.nextBytes(bytes, 6, 16)

        // Byte 6 : top nibble = version 7.
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        // Byte 8 : top two bits = variant 10 (RFC 4122).
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80.toByte().toInt()).toByte()

        return Uuid.fromByteArray(bytes)
    }
}
