package id.nearyou.distance

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual fun hmacSha256(
    key: ByteArray,
    msg: ByteArray,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(msg)
}

actual fun unixMillis(): Long = System.currentTimeMillis()
