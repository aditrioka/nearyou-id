package id.nearyou.app.image

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.infra.redis.RedisStringCache
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import java.time.Duration

private class MutCache(var value: String? = null) : RedisStringCache {
    val sets = mutableListOf<Pair<String, String>>()

    override fun get(key: String): String? = value

    override fun set(
        key: String,
        value: String,
        ttl: Duration,
    ) {
        sets += key to value
    }
}

private class BoolRemoteConfig(val value: Boolean?, val throws: Boolean = false) : RemoteConfig {
    var calls = 0

    override fun getLong(key: String): Long? = null

    override fun getBoolean(key: String): Boolean? {
        calls++
        if (throws) throw RuntimeException("rc boom")
        return value
    }
}

class ImageUploadFlagGateTest : StringSpec({

    fun gate(
        cache: RedisStringCache,
        rc: RemoteConfig,
    ) = ImageUploadFlagGate(cache, rc, dbDispatcher = Dispatchers.Unconfined)

    "flag TRUE → enabled" {
        gate(MutCache(), BoolRemoteConfig(true)).isEnabled().shouldBeTrue()
    }

    "flag FALSE → disabled" {
        gate(MutCache(), BoolRemoteConfig(false)).isEnabled().shouldBeFalse()
    }

    "flag unset (null) → disabled (fail-closed)" {
        gate(MutCache(), BoolRemoteConfig(null)).isEnabled().shouldBeFalse()
    }

    "Remote Config error → disabled (fail-closed)" {
        gate(MutCache(), BoolRemoteConfig(null, throws = true)).isEnabled().shouldBeFalse()
    }

    "cache hit 'true' short-circuits without a Remote Config read" {
        val rc = BoolRemoteConfig(false) // would say false if consulted
        gate(MutCache("true"), rc).isEnabled().shouldBeTrue()
        rc.calls shouldBe 0
    }

    "a resolved value is cached at the short-TTL key" {
        val cache = MutCache()
        gate(cache, BoolRemoteConfig(true)).isEnabled()
        cache.sets.map { it.first } shouldBe listOf(ImageUploadFlagGate.CACHE_KEY)
        cache.sets.single().second shouldBe "true"
    }
})
