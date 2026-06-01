@file:OptIn(ExperimentalForeignApi::class)

package id.nearyou.distance

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS `actual` for [hmacSha256] via the platform CommonCrypto `CCHmac(kCCHmacAlgSHA256, …)` binding
 * (`platform.CoreCrypto`) — the canonical Kotlin/Native iOS HMAC-SHA256 pattern (re-verified
 * 2026-05-31; no third-party crypto dependency added per the `coordinate-jitter` delta). The key,
 * message, and 32-byte digest buffers are pinned for the C call.
 *
 * Empty-array edge: `ByteArray(0).usePinned { it.addressOf(0) }` throws (index 0 is out of bounds
 * on an empty array), so an empty key/message is pinned via a 1-byte placeholder while the REAL
 * length (`0`) is passed to `CCHmac` — the placeholder bytes are never read. In practice
 * `JitterEngine` always passes a 32-byte secret + a 16-byte postId, so this is defensive only.
 *
 * Verified byte-identical to the JVM/Android `javax.crypto` actuals by the commonTest RFC 4231
 * known-answer test, which executes on `iosSimulatorArm64Test`.
 */
actual fun hmacSha256(
    key: ByteArray,
    msg: ByteArray,
): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    val keyPin = if (key.isEmpty()) ByteArray(1) else key
    val msgPin = if (msg.isEmpty()) ByteArray(1) else msg
    digest.usePinned { digestPinned ->
        keyPin.usePinned { keyPinned ->
            msgPin.usePinned { msgPinned ->
                CCHmac(
                    kCCHmacAlgSHA256,
                    keyPinned.addressOf(0),
                    key.size.convert(),
                    msgPinned.addressOf(0),
                    msg.size.convert(),
                    digestPinned.addressOf(0),
                )
            }
        }
    }
    return digest
}

actual fun unixMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
