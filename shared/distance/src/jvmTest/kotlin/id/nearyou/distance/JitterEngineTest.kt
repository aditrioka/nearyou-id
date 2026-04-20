package id.nearyou.distance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pure-Kotlin tests. Lives in jvmTest because [Uuid.toByteArray] + [UuidV7.next] rely on
 * `unixMillis` which only has a JVM `actual` right now. When iOS actuals land, this spec
 * can be promoted to `commonTest`.
 */
@OptIn(ExperimentalUuidApi::class)
class JitterEngineTest : StringSpec({

    val actual = LatLng(lat = -6.2, lng = 106.8) // Jakarta
    val secret = ByteArray(32) { it.toByte() }

    "determinism: same (actual, postId, secret) produces byte-identical outputs" {
        val id = Uuid.fromLongs(0x0123_4567_89AB_CDEFL, 0x0F1E_2D3C_4B5A_6978L)
        val first = JitterEngine.offsetByBearing(actual, id, secret)
        repeat(10) {
            val again = JitterEngine.offsetByBearing(actual, id, secret)
            again.lat.toRawBits() shouldBe first.lat.toRawBits()
            again.lng.toRawBits() shouldBe first.lng.toRawBits()
        }
    }

    "distance bounds: 1000 seeds all land in [50, 500] m" {
        val rng = Random(0xC0FFEEL)
        repeat(1000) {
            val id = UuidV7.next(millis = 1_700_000_000_000L + it, random = rng)
            val display = JitterEngine.offsetByBearing(actual, id, secret)
            val d = haversineMeters(actual, display)
            d shouldBeGreaterThanOrEqual 50.0
            d shouldBeLessThanOrEqual 500.0
        }
    }

    "secret sensitivity: different secret → different output" {
        val id = Uuid.fromLongs(1L, 2L)
        val secretA = ByteArray(32) { it.toByte() }
        val secretB = ByteArray(32) { (it + 1).toByte() }
        val a = JitterEngine.offsetByBearing(actual, id, secretA)
        val b = JitterEngine.offsetByBearing(actual, id, secretB)
        a shouldNotBe b
    }
})

private fun haversineMeters(
    a: LatLng,
    b: LatLng,
): Double {
    val earth = 6_371_008.8
    val dLat = (b.lat - a.lat) * PI / 180.0
    val dLng = (b.lng - a.lng) * PI / 180.0
    val lat1 = a.lat * PI / 180.0
    val lat2 = b.lat * PI / 180.0
    val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
    return 2.0 * earth * asin(sqrt(h))
}
