package id.nearyou.distance

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Android `actual` for the [hmacSha256] / [unixMillis] `expect`s. Android is JVM-based, so this is
 * byte-identical to the shipped `jvmMain` `CryptoJvm.kt` actual (the ~6 lines are duplicated rather
 * than hoisted into an intermediate source set — the simpler build-structure choice per the
 * `coordinate-jitter` delta design D4; verified byte-identical to the JVM actual by the commonTest
 * RFC 4231 known-answer test). Only the jitter *algorithm* ships to the client; `JITTER_SECRET` is
 * never embedded (it is injected at runtime, backend-only).
 */
actual fun hmacSha256(
    key: ByteArray,
    msg: ByteArray,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(msg)
}

actual fun unixMillis(): Long = System.currentTimeMillis()
