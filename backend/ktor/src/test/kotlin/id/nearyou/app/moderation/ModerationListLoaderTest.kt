package id.nearyou.app.moderation

import id.nearyou.app.config.SecretResolver
import id.nearyou.app.infra.redis.RedisStringCache
import id.nearyou.app.infra.remoteconfig.RemoteConfigClient
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for [CachingModerationListLoader] cascade behavior. Uses in-process fakes
 * for [RedisStringCache], [RemoteConfigClient], [SecretResolver], so the cascade
 * logic is deterministic without needing real Redis / Firebase / Secret Manager
 * infrastructure. The real Lettuce binding is exercised separately by `:infra:redis`
 * integration tests.
 *
 * Covers all 11 cascade scenarios from
 * `### Requirement: ModerationListLoader resolves keyword lists via the canonical 4-tier fallback ladder`
 * + 5 threshold scenarios from
 * `### Requirement: ModerationMatchThresholdLoader resolves the threshold via the same Remote Config + fallback path`.
 */
class ModerationListLoaderTest : StringSpec({

    "Tier 1 cache hit short-circuits — no Tier 2/3/4 calls, no Sentry event" {
        val cache = FakeRedisStringCache()
        cache.put("{scope:mod_list}:{tier:profanity}", """["a","b","c"]""")
        val rc = RecordingRemoteConfigClient()
        val sm = RecordingSecretResolver()
        val loader = newLoader(cache, rc, sm)

        loader.load(ModerationList.ProfanityList) shouldContainExactly listOf("a", "b", "c")

        rc.fetchStringListCalls shouldBe 0
        sm.resolveCalls shouldBe 0
    }

    "Tier 1 cache miss → Tier 2 Remote Config success populates cache" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(stringLists = mapOf("moderation_profanity_list" to listOf("a", "b")))
        val sm = RecordingSecretResolver()
        val loader = newLoader(cache, rc, sm)

        loader.load(ModerationList.ProfanityList) shouldContainExactly listOf("a", "b")
        cache.entries["{scope:mod_list}:{tier:profanity}"] shouldBe """["a","b"]"""
        cache.lastTtl shouldBe Duration.ofMinutes(5)
    }

    "Tier 2 network error → Tier 3 repo file success" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(throwOnFetchStringList = true)
        val sm = RecordingSecretResolver()
        val loader =
            newLoader(cache, rc, sm, classLoader = stubResources(mapOf("moderation/profanity.default.txt" to "a\nb\n# comment\n\nc\n")))

        loader.load(ModerationList.ProfanityList) shouldContainExactly listOf("a", "b", "c")
    }

    "Tier 2 returns empty list → Tier 3 repo file success" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(stringLists = mapOf("moderation_profanity_list" to emptyList()))
        val sm = RecordingSecretResolver()
        val loader =
            newLoader(
                cache,
                rc,
                sm,
                classLoader = stubResources(mapOf("moderation/profanity.default.txt" to "a\nb\n")),
            )

        loader.load(ModerationList.ProfanityList) shouldContainExactly listOf("a", "b")
    }

    "Tier 2 malformed payload (returns null) → Tier 3 repo file success" {
        val cache = FakeRedisStringCache()
        // null fetchStringList result simulates parse error / SDK failure
        val rc = RecordingRemoteConfigClient(stringLists = emptyMap())
        val sm = RecordingSecretResolver()
        val loader =
            newLoader(
                cache,
                rc,
                sm,
                classLoader = stubResources(mapOf("moderation/profanity.default.txt" to "a\nb\n")),
            )

        loader.load(ModerationList.ProfanityList) shouldContainExactly listOf("a", "b")
    }

    "Tier 3 missing → Tier 4 Secret Manager success" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(throwOnFetchStringList = true)
        val sm =
            RecordingSecretResolver(
                values = mapOf("content-moderation-fallback-list" to """{"profanity":["a","b"],"uu_ite":["c"]}"""),
            )
        val loader = newLoader(cache, rc, sm, classLoader = stubResources(emptyMap()))

        loader.load(ModerationList.ProfanityList) shouldContainExactly listOf("a", "b")
    }

    "Tier 3 file present but empty after trim → Tier 4 succeeds" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(throwOnFetchStringList = true)
        val sm =
            RecordingSecretResolver(
                values = mapOf("content-moderation-fallback-list" to """{"profanity":["x"],"uu_ite":[]}"""),
            )
        val loader =
            newLoader(
                cache,
                rc,
                sm,
                classLoader = stubResources(mapOf("moderation/profanity.default.txt" to "# only comment\n\n")),
            )

        loader.load(ModerationList.ProfanityList) shouldContainExactly listOf("x")
    }

    "Tier 4 success populates cache" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(throwOnFetchStringList = true)
        val sm =
            RecordingSecretResolver(
                values = mapOf("content-moderation-fallback-list" to """{"profanity":["a"],"uu_ite":["c"]}"""),
            )
        val loader = newLoader(cache, rc, sm, classLoader = stubResources(emptyMap()))

        loader.load(ModerationList.ProfanityList) shouldContainExactly listOf("a")
        cache.entries["{scope:mod_list}:{tier:profanity}"] shouldBe """["a"]"""
    }

    "Tier 4 malformed JSON → empty list + ERROR" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(throwOnFetchStringList = true)
        val sm = RecordingSecretResolver(values = mapOf("content-moderation-fallback-list" to "not-a-json-document"))
        val loader = newLoader(cache, rc, sm, classLoader = stubResources(emptyMap()))

        loader.load(ModerationList.ProfanityList) shouldContainExactly emptyList()
    }

    "All four tiers fail → empty list + ERROR" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(throwOnFetchStringList = true)
        val sm = RecordingSecretResolver()
        val loader = newLoader(cache, rc, sm, classLoader = stubResources(emptyMap()))

        loader.load(ModerationList.ProfanityList) shouldContainExactly emptyList()
        cache.entries["{scope:mod_list}:{tier:profanity}"] shouldBe null
    }

    "Tier 4 resolve call uses bare slot name (no env-prefix in caller)" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(throwOnFetchStringList = true)
        val sm = RecordingSecretResolver()
        val loader = newLoader(cache, rc, sm, env = "staging", classLoader = stubResources(emptyMap()))
        loader.load(ModerationList.ProfanityList)

        // The loader passes 'content-moderation-fallback-list' (bare slot name) to the
        // SecretResolver — env-prefix derivation is the resolver's responsibility (mirrors
        // Application.kt:388 'secrets.resolve("firebase-admin-sa")' precedent).
        sm.lastResolvedName shouldBe "content-moderation-fallback-list"
    }

    "Cache key matches canonical hash-tag format for both lists" {
        val cache = FakeRedisStringCache()
        val rc =
            RecordingRemoteConfigClient(
                stringLists =
                    mapOf(
                        "moderation_profanity_list" to listOf("a"),
                        "moderation_uu_ite_list" to listOf("b"),
                    ),
            )
        val loader = newLoader(cache, rc, RecordingSecretResolver())
        loader.load(ModerationList.ProfanityList)
        loader.load(ModerationList.UuIteList)

        cache.entries.keys shouldContainExactly setOf("{scope:mod_list}:{tier:profanity}", "{scope:mod_list}:{tier:uu_ite}")
    }

    // ----- Threshold loader scenarios ----------------------------------------

    "Threshold default 3 when Remote Config returns null" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient()
        val loader = newLoader(cache, rc, RecordingSecretResolver())

        loader.loadThreshold() shouldBe 3
    }

    "Threshold clamped on out-of-range value (0 → default 3)" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(ints = mapOf("moderation_match_threshold" to 0))
        val loader = newLoader(cache, rc, RecordingSecretResolver())

        loader.loadThreshold() shouldBe 3
        // Out-of-range value is NOT cached.
        cache.entries["{scope:mod_list}:{tier:threshold}"] shouldBe null
    }

    "Threshold honored when within range" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(ints = mapOf("moderation_match_threshold" to 5))
        val loader = newLoader(cache, rc, RecordingSecretResolver())

        loader.loadThreshold() shouldBe 5
        cache.entries["{scope:mod_list}:{tier:threshold}"] shouldBe "5"
    }

    "Threshold clamped on cached out-of-range value (cached-0 poisoning prevention)" {
        val cache = FakeRedisStringCache()
        cache.put("{scope:mod_list}:{tier:threshold}", "0")
        val rc = RecordingRemoteConfigClient()
        val loader = newLoader(cache, rc, RecordingSecretResolver())

        loader.loadThreshold() shouldBe 3
    }

    "Threshold falls back to default on non-integer Remote Config value" {
        val cache = FakeRedisStringCache()
        // null fetchInt simulates non-integer parse failure (RemoteConfigClient returns null on parse error)
        val rc = RecordingRemoteConfigClient(ints = emptyMap())
        val loader = newLoader(cache, rc, RecordingSecretResolver())

        loader.loadThreshold() shouldBe 3
    }

    "Threshold clamped above max (10001 → default 3)" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(ints = mapOf("moderation_match_threshold" to 10_001))
        val loader = newLoader(cache, rc, RecordingSecretResolver())

        loader.loadThreshold() shouldBe 3
    }

    "Threshold cached value of 999999 NOT honored — clamped to default" {
        val cache = FakeRedisStringCache()
        cache.put("{scope:mod_list}:{tier:threshold}", "999999")
        val rc = RecordingRemoteConfigClient()
        val loader = newLoader(cache, rc, RecordingSecretResolver())

        loader.loadThreshold() shouldBe 3
    }

    "Threshold at boundary value 10000 is honored" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(ints = mapOf("moderation_match_threshold" to 10_000))
        val loader = newLoader(cache, rc, RecordingSecretResolver())

        loader.loadThreshold() shouldBe 10_000
    }

    "Threshold at boundary value 1 is honored" {
        val cache = FakeRedisStringCache()
        val rc = RecordingRemoteConfigClient(ints = mapOf("moderation_match_threshold" to 1))
        val loader = newLoader(cache, rc, RecordingSecretResolver())

        loader.loadThreshold() shouldBe 1
    }
})

// ----- Test fixtures ---------------------------------------------------------

private fun newLoader(
    cache: RedisStringCache,
    rc: RemoteConfigClient,
    sm: SecretResolver,
    env: String = "test",
    classLoader: ClassLoader = stubResources(emptyMap()),
): CachingModerationListLoader =
    CachingModerationListLoader(
        redisCache = cache,
        remoteConfigClient = rc,
        secretResolver = sm,
        env = env,
        classLoader = classLoader,
    )

private class FakeRedisStringCache : RedisStringCache {
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

private class RecordingRemoteConfigClient(
    val stringLists: Map<String, List<String>> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val throwOnFetchStringList: Boolean = false,
    val throwOnFetchInt: Boolean = false,
) : RemoteConfigClient {
    var fetchStringListCalls: Int = 0
    var fetchIntCalls: Int = 0

    override fun fetchStringList(parameterName: String): List<String>? {
        fetchStringListCalls += 1
        if (throwOnFetchStringList) error("simulated network failure")
        return stringLists[parameterName]
    }

    override fun fetchInt(parameterName: String): Int? {
        fetchIntCalls += 1
        if (throwOnFetchInt) error("simulated failure")
        return ints[parameterName]
    }

    override fun fetchBoolean(parameterName: String): Boolean? = null
}

private class RecordingSecretResolver(
    val values: Map<String, String> = emptyMap(),
) : SecretResolver {
    var resolveCalls: Int = 0
    var lastResolvedName: String? = null

    override fun resolve(name: String): String? {
        resolveCalls += 1
        lastResolvedName = name
        return values[name]
    }
}

/**
 * Build a ClassLoader that serves a fixed set of classpath resources from in-memory
 * strings. Used to simulate the repo file (`/moderation/<slug>.default.txt`) tier
 * without writing actual files into the test resource tree (which would also be
 * picked up by the production binding).
 */
private fun stubResources(map: Map<String, String>): ClassLoader =
    object : ClassLoader() {
        override fun getResourceAsStream(name: String): java.io.InputStream? {
            // Try with and without leading slash since the loader normalizes.
            val key = name.removePrefix("/")
            val content = map[key] ?: map[name]
            return content?.byteInputStream(Charsets.UTF_8)
        }
    }
