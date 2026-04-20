package id.nearyou.distance

/**
 * Platform bridge for HMAC-SHA256.
 *
 * JVM backs this with `javax.crypto.Mac`. iOS/Android `actual`s land with the mobile change.
 */
expect fun hmacSha256(
    key: ByteArray,
    msg: ByteArray,
): ByteArray

/**
 * Platform bridge for Unix-epoch milliseconds, used by [UuidV7].
 *
 * JVM backs this with `System.currentTimeMillis()`.
 */
expect fun unixMillis(): Long
