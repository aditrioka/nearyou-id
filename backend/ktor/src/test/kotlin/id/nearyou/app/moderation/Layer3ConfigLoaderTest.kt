package id.nearyou.app.moderation

import id.nearyou.app.infra.redis.RedisStringCache
import id.nearyou.app.infra.remoteconfig.RemoteConfigClient
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for [CachingLayer3ConfigLoader] per
 * `text-moderation-perspective-api-layer/spec.md`
 * `### Requirement: Layer3ConfigLoader caches kill-switch + thresholds in :backend:ktor`.
 *
 * Covers:
 *  - Tier 1 cache hit short-circuits.
 *  - Tier 1 miss → Tier 2 success caches.
 *  - Tier 2 throw + Tier 3 fallback (kill-switch fails OPEN to true; emits ERROR).
 *  - Threshold clamp on positive out-of-range Tier-2 value.
 *  - Threshold clamp on negative out-of-range Tier-2 value.
 *  - Threshold clamp on cached out-of-range value (cached-bad-value poisoning prevention).
 */
class Layer3ConfigLoaderTest : StringSpec({

    "Tier 1 kill-switch cache hit short-circuits — no Remote Config call" {
        val cache = FakeCache()
        cache.put(CachingLayer3ConfigLoader.KILL_SWITCH_CACHE_KEY, "false")
        val rc = SpyRemoteConfigClient()
        val loader = CachingLayer3ConfigLoader(cache, rc)

        runBlocking { loader.isEnabled() } shouldBe false

        rc.fetchBooleanCalls shouldBe 0
    }

    "Tier 1 cache miss → Tier 2 success caches and returns" {
        val cache = FakeCache()
        val rc = SpyRemoteConfigClient(booleans = mapOf(CachingLayer3ConfigLoader.KILL_SWITCH_PARAM to false))
        val loader = CachingLayer3ConfigLoader(cache, rc, cacheTtl = Duration.ofMinutes(5))

        runBlocking { loader.isEnabled() } shouldBe false

        cache.entries[CachingLayer3ConfigLoader.KILL_SWITCH_CACHE_KEY] shouldBe "false"
        cache.lastTtl shouldBe Duration.ofMinutes(5)
    }

    "Tier 2 throw → fail-OPEN to true (kill-switch ERROR path)" {
        val cache = FakeCache()
        val rc = SpyRemoteConfigClient(throwOnFetchBoolean = true)
        val loader = CachingLayer3ConfigLoader(cache, rc)

        runBlocking { loader.isEnabled() } shouldBe true
        // Cache is NOT populated on the ERROR path — preserves the operator's
        // disable on Remote Config recovery (the next call will retry Tier 2).
        cache.entries[CachingLayer3ConfigLoader.KILL_SWITCH_CACHE_KEY] shouldBe null
    }

    "Tier 2 returns null → static default true; cached for the TTL window" {
        val cache = FakeCache()
        val rc = SpyRemoteConfigClient() // booleans empty → fetchBoolean returns null
        val loader = CachingLayer3ConfigLoader(cache, rc)

        runBlocking { loader.isEnabled() } shouldBe true
        cache.entries[CachingLayer3ConfigLoader.KILL_SWITCH_CACHE_KEY] shouldBe "true"
    }

    "Tier 1 high-score-threshold cache hit returns parsed double" {
        val cache = FakeCache()
        cache.put(
            CachingLayer3ConfigLoader.thresholdCacheKey(
                CachingLayer3ConfigLoader.HIGH_SCORE_PARAM,
            ),
            "0.85",
        )
        val rc = SpyRemoteConfigClient()
        val loader = CachingLayer3ConfigLoader(cache, rc)

        runBlocking { loader.highScoreThreshold() } shouldBe 0.85

        rc.fetchDoubleCalls shouldBe 0
    }

    "Tier 2 high-score-threshold positive out-of-range falls back to default 0.8" {
        val cache = FakeCache()
        val rc =
            SpyRemoteConfigClient(
                doubles = mapOf(CachingLayer3ConfigLoader.HIGH_SCORE_PARAM to 1.5),
            )
        val loader = CachingLayer3ConfigLoader(cache, rc)

        runBlocking { loader.highScoreThreshold() } shouldBe 0.8
        cache.entries[
            CachingLayer3ConfigLoader.thresholdCacheKey(
                CachingLayer3ConfigLoader.HIGH_SCORE_PARAM,
            ),
        ] shouldBe "0.8"
    }

    "Tier 2 high-score-threshold negative falls back to default 0.8" {
        val cache = FakeCache()
        val rc =
            SpyRemoteConfigClient(
                doubles = mapOf(CachingLayer3ConfigLoader.HIGH_SCORE_PARAM to -0.5),
            )
        val loader = CachingLayer3ConfigLoader(cache, rc)

        runBlocking { loader.highScoreThreshold() } shouldBe 0.8
    }

    "Tier 1 cached out-of-range value falls back to Tier 2 (poisoning prevention)" {
        val cache = FakeCache()
        cache.put(
            CachingLayer3ConfigLoader.thresholdCacheKey(
                CachingLayer3ConfigLoader.HIGH_SCORE_PARAM,
            ),
            "-0.5",
        )
        val rc =
            SpyRemoteConfigClient(
                doubles = mapOf(CachingLayer3ConfigLoader.HIGH_SCORE_PARAM to 0.85),
            )
        val loader = CachingLayer3ConfigLoader(cache, rc)

        // Cached -0.5 is rejected (out of [0.0, 1.0]), Tier 2 returns 0.85.
        runBlocking { loader.highScoreThreshold() } shouldBe 0.85
    }

    "Flag threshold defaults to 0.6 when Remote Config returns null" {
        val cache = FakeCache()
        val rc = SpyRemoteConfigClient()
        val loader = CachingLayer3ConfigLoader(cache, rc)

        runBlocking { loader.flagThreshold() } shouldBe 0.6
    }

    "Flag threshold respects in-range Remote Config values" {
        val cache = FakeCache()
        val rc =
            SpyRemoteConfigClient(
                doubles = mapOf(CachingLayer3ConfigLoader.FLAG_PARAM to 0.45),
            )
        val loader = CachingLayer3ConfigLoader(cache, rc)

        runBlocking { loader.flagThreshold() } shouldBe 0.45
    }

    "Cache key uses canonical hash-tag format for cluster safety" {
        val cache = FakeCache()
        val rc =
            SpyRemoteConfigClient(
                doubles = mapOf(CachingLayer3ConfigLoader.HIGH_SCORE_PARAM to 0.85),
            )
        val loader = CachingLayer3ConfigLoader(cache, rc)

        runBlocking { loader.highScoreThreshold() }

        // Verify exact key format per spec scenario "Cache key uses canonical hash-tag format".
        cache.entries.keys.first() shouldBe "{scope:layer3_config}:{flag:perspective_api_high_score_threshold}"
        // And kill-switch
        cache.put(CachingLayer3ConfigLoader.KILL_SWITCH_CACHE_KEY, "true")
        cache.entries[CachingLayer3ConfigLoader.KILL_SWITCH_CACHE_KEY] shouldNotBe null
        CachingLayer3ConfigLoader.KILL_SWITCH_CACHE_KEY shouldBe
            "{scope:layer3_config}:{flag:perspective_api_enabled}"
    }
})

private class FakeCache : RedisStringCache {
    val entries: MutableMap<String, String> = ConcurrentHashMap()
    var lastTtl: Duration? = null

    fun put(
        key: String,
        value: String,
    ) {
        entries[key] = value
    }

    override fun get(key: String): String? = entries[key]

    override fun set(
        key: String,
        value: String,
        ttl: Duration,
    ) {
        entries[key] = value
        lastTtl = ttl
    }
}

private class SpyRemoteConfigClient(
    val booleans: Map<String, Boolean> = emptyMap(),
    val doubles: Map<String, Double> = emptyMap(),
    val throwOnFetchBoolean: Boolean = false,
    val throwOnFetchDouble: Boolean = false,
) : RemoteConfigClient {
    var fetchBooleanCalls: Int = 0
    var fetchDoubleCalls: Int = 0

    override fun fetchStringList(parameterName: String): List<String>? = null

    override fun fetchInt(parameterName: String): Int? = null

    override fun fetchDouble(parameterName: String): Double? {
        fetchDoubleCalls += 1
        if (throwOnFetchDouble) error("simulated RC failure")
        return doubles[parameterName]
    }

    override fun fetchBoolean(parameterName: String): Boolean? {
        fetchBooleanCalls += 1
        if (throwOnFetchBoolean) error("simulated RC failure")
        return booleans[parameterName]
    }
}
