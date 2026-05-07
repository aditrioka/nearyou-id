package id.nearyou.app.moderation

import id.nearyou.app.config.SecretResolver
import id.nearyou.app.config.secretKey
import id.nearyou.app.infra.redis.RedisStringCache
import id.nearyou.app.infra.remoteconfig.RemoteConfigClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * The two moderation lists this loader resolves. The `slug` is used in Redis
 * cache keys (`{scope:mod_list}:{tier:profanity}`, `{tier:uu_ite}`), Remote
 * Config parameter names (`moderation_profanity_list`, `moderation_uu_ite_list`),
 * and Sentry event tags.
 */
enum class ModerationList(val slug: String) {
    ProfanityList("profanity"),
    UuIteList("uu_ite"),
}

/**
 * Loader for moderation keyword lists + the match threshold. Resolves via the
 * canonical 4-tier fallback ladder per
 * [`content-moderation-keyword-lists/spec.md`](../../../../../../openspec/specs/content-moderation-keyword-lists)
 * `### Requirement: ModerationListLoader resolves keyword lists via the canonical 4-tier fallback ladder`:
 *
 *   1. Redis 5-min cache (`{scope:mod_list}:{tier:<slug>}`) — hit returns immediately.
 *   2. Firebase Remote Config (`moderation_<slug>_list`) — caches to Tier 1 + returns.
 *   3. Repo-committed resource file (`/moderation/<slug>.default.txt`).
 *   4. GCP Secret Manager (`content-moderation-fallback-list`).
 *
 * `loadThreshold()` uses a simpler 2-tier ladder (Redis 5-min cache → Remote Config)
 * with a default of 3 when neither resolves. The clamp `[1, 10000]` applies on
 * EVERY read including cached values, so a poisoned cached `0` cannot propagate.
 */
interface ModerationListLoader {
    fun load(list: ModerationList): List<String>

    fun loadThreshold(): Int
}

/**
 * Production binding for [ModerationListLoader]. Fail-soft semantics — every tier
 * failure cascades to the next. If ALL FOUR tiers fail, the loader returns
 * `emptyList()` and emits a single Sentry ERROR per call (`event = "moderation_list_unavailable"`).
 *
 * Sentry-event rate limit per call: at most one event. The most-severe event
 * supersedes any earlier WARNs in the cascade.
 *
 * The `secretResolver.resolve("content-moderation-fallback-list")` call is the bare
 * slot name (the resolver handles env-prefix derivation); a `secretKey(env, "content-moderation-fallback-list")`
 * computation is preserved for the diagnostic log emitted on Tier 4 failure (mirrors the
 * `Application.kt:386` precedent for `firebase-admin-sa`).
 */
class CachingModerationListLoader(
    private val redisCache: RedisStringCache,
    private val remoteConfigClient: RemoteConfigClient,
    private val secretResolver: SecretResolver,
    private val env: String,
    private val classLoader: ClassLoader = CachingModerationListLoader::class.java.classLoader,
    private val cacheTtl: Duration = Duration.ofMinutes(5),
) : ModerationListLoader {
    private val log = LoggerFactory.getLogger(CachingModerationListLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(list: ModerationList): List<String> {
        val redisKey = listCacheKey(list)

        // Tier 1: Redis cache.
        val cached = readListFromRedis(redisKey)
        if (cached != null && cached.isNotEmpty()) {
            return cached
        }

        // Cascade transitions — the most recent WARN-eligible transition is kept;
        // an ERROR at the end of the cascade supersedes it. Spec: at most ONE Sentry
        // event per load(list) call.
        var lastTransition: CascadeWarn? = null

        // Tier 2: Remote Config.
        val rcResult = fetchFromRemoteConfig(list)
        when (rcResult.kind) {
            RcOutcomeKind.Success -> {
                cacheList(redisKey, rcResult.values)
                return rcResult.values
            }
            RcOutcomeKind.Empty -> {
                lastTransition = CascadeWarn("remote_config", "repo_file", "empty", list.slug)
            }
            RcOutcomeKind.ParseOrSdkError -> {
                lastTransition = CascadeWarn("remote_config", "repo_file", "parse", list.slug)
            }
        }

        // Tier 3: Repo file.
        val repoResult = readRepoFile(list)
        when (repoResult.kind) {
            RepoOutcomeKind.Success -> {
                cacheList(redisKey, repoResult.values)
                emitWarn(lastTransition)
                return repoResult.values
            }
            RepoOutcomeKind.Missing -> {
                lastTransition = CascadeWarn("repo_file", "secret_manager", "missing", list.slug)
            }
            RepoOutcomeKind.EmptyAfterTrim -> {
                lastTransition = CascadeWarn("repo_file", "secret_manager", "empty", list.slug)
            }
        }

        // Tier 4: Secret Manager.
        val smResult = readSecretManager(list)
        when (smResult.kind) {
            SmOutcomeKind.Success -> {
                cacheList(redisKey, smResult.values)
                emitWarn(lastTransition)
                return smResult.values
            }
            SmOutcomeKind.MissingOrParseError -> {
                emitListError(list, smResult.errorReason)
                return emptyList()
            }
        }
    }

    override fun loadThreshold(): Int {
        val redisKey = THRESHOLD_CACHE_KEY

        // Tier 1: Redis. Apply clamp on EVERY read — a poisoned cached value (e.g., 0
        // from a stale Remote Config push) MUST NOT propagate. Per spec scenario
        // "Threshold clamped on cached out-of-range value (cached-0 poisoning prevention)".
        val cached = redisCache.get(redisKey)?.trim()?.toIntOrNull()
        if (cached != null && cached in THRESHOLD_MIN..THRESHOLD_MAX) {
            return cached
        }

        // Tier 2: Remote Config.
        val rcInt =
            try {
                remoteConfigClient.fetchInt(THRESHOLD_REMOTE_CONFIG_KEY)
            } catch (t: Throwable) {
                log.warn(
                    "event=remote_config_error key={} reason={}",
                    THRESHOLD_REMOTE_CONFIG_KEY,
                    t.javaClass.simpleName,
                )
                null
            }
        if (rcInt != null) {
            if (rcInt in THRESHOLD_MIN..THRESHOLD_MAX) {
                redisCache.set(redisKey, rcInt.toString(), cacheTtl)
                return rcInt
            }
            log.warn(
                "event=moderation_list_fallback tier=remote_config to=default_3 list=threshold reason=out_of_range value={}",
                rcInt,
            )
            return THRESHOLD_DEFAULT
        }

        // Distinguish parse vs unreachable. fetchInt returns null on both unset AND
        // non-integer; the WARN reason is "parse" if the parameter was set but unparseable.
        // We can't easily distinguish without a separate fetch, so the WARN says
        // tier=remote_config, to=default_3, reason=parse_or_unset (covers both spec scenarios
        // "Threshold default 3 when Remote Config unreachable" + "Threshold falls back on non-integer").
        log.warn(
            "event=moderation_list_fallback tier=remote_config to=default_3 list=threshold reason=parse_or_unset",
        )
        return THRESHOLD_DEFAULT
    }

    /**
     * Tier 1 read: parse the cached JSON-array string.
     * Returns null on cache miss / Redis unavailable / parse error.
     */
    private fun readListFromRedis(redisKey: String): List<String>? {
        val raw = redisCache.get(redisKey) ?: return null
        return try {
            val element = json.parseToJsonElement(raw)
            require(element is JsonArray) { "expected JSON array" }
            element.map { (it as JsonPrimitive).content }
        } catch (t: Throwable) {
            log.warn(
                "event=moderation_redis_cache_parse_failed key={} reason={}",
                redisKey,
                t.javaClass.simpleName,
            )
            null
        }
    }

    private data class RcOutcome(val kind: RcOutcomeKind, val values: List<String>) {
        companion object {
            val Empty = RcOutcome(RcOutcomeKind.Empty, emptyList())
            val Error = RcOutcome(RcOutcomeKind.ParseOrSdkError, emptyList())

            fun success(values: List<String>) = RcOutcome(RcOutcomeKind.Success, values)
        }
    }

    private enum class RcOutcomeKind { Success, Empty, ParseOrSdkError }

    private fun fetchFromRemoteConfig(list: ModerationList): RcOutcome {
        val parameter = "moderation_${list.slug}_list"
        val values =
            try {
                remoteConfigClient.fetchStringList(parameter)
            } catch (t: Throwable) {
                log.warn(
                    "event=remote_config_error key={} reason={}",
                    parameter,
                    t.javaClass.simpleName,
                )
                return RcOutcome.Error
            }
        return when {
            values == null -> RcOutcome.Error
            values.isEmpty() -> RcOutcome.Empty
            else -> RcOutcome.success(values)
        }
    }

    private data class RepoOutcome(val kind: RepoOutcomeKind, val values: List<String>) {
        companion object {
            val Missing = RepoOutcome(RepoOutcomeKind.Missing, emptyList())
            val EmptyAfterTrim = RepoOutcome(RepoOutcomeKind.EmptyAfterTrim, emptyList())

            fun success(values: List<String>) = RepoOutcome(RepoOutcomeKind.Success, values)
        }
    }

    private enum class RepoOutcomeKind { Success, Missing, EmptyAfterTrim }

    private fun readRepoFile(list: ModerationList): RepoOutcome {
        val resourcePath = "/moderation/${list.slug}.default.txt"
        val stream =
            classLoader.getResourceAsStream(resourcePath)
                ?: classLoader.getResourceAsStream(resourcePath.removePrefix("/"))
                ?: return RepoOutcome.Missing
        val items =
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
        return if (items.isEmpty()) RepoOutcome.EmptyAfterTrim else RepoOutcome.success(items)
    }

    private data class SmOutcome(
        val kind: SmOutcomeKind,
        val values: List<String>,
        val errorReason: String? = null,
    ) {
        companion object {
            fun success(values: List<String>) = SmOutcome(SmOutcomeKind.Success, values)

            fun failure(reason: String) = SmOutcome(SmOutcomeKind.MissingOrParseError, emptyList(), reason)
        }
    }

    private enum class SmOutcomeKind { Success, MissingOrParseError }

    private fun readSecretManager(list: ModerationList): SmOutcome {
        // Bare slot name; the SecretResolver handles env-prefix internally per the
        // Application.kt:388 precedent ('secrets.resolve("firebase-admin-sa")').
        val raw = secretResolver.resolve(SECRET_SLOT_NAME)
        if (raw.isNullOrBlank()) {
            return SmOutcome.failure("missing")
        }
        return try {
            val element = json.parseToJsonElement(raw)
            require(element is JsonObject) { "Tier 4 payload must be a JSON object" }
            val arrayElement = element[list.slug]
            require(arrayElement is JsonArray) { "key '${list.slug}' missing or not array" }
            val items =
                arrayElement.map { item ->
                    require(item is JsonPrimitive && item.isString) { "expected string elements" }
                    item.jsonPrimitive.content
                }
            SmOutcome.success(items)
        } catch (t: Throwable) {
            SmOutcome.failure("parse")
        }
    }

    private fun cacheList(
        redisKey: String,
        values: List<String>,
    ) {
        // Serialize values as a JSON array for Tier 1 round-trip parsing.
        val payload = json.encodeToString(JsonArray.serializer(), JsonArray(values.map { JsonPrimitive(it) }))
        redisCache.set(redisKey, payload, cacheTtl)
    }

    private fun emitWarn(transition: CascadeWarn?) {
        if (transition == null) return
        log.warn(
            "event=moderation_list_fallback tier={} to={} list={} reason={}",
            transition.tier,
            transition.to,
            transition.listSlug,
            transition.reason,
        )
    }

    private fun emitListError(
        list: ModerationList,
        reason: String?,
    ) {
        // Compute the env-aware slot for the diagnostic log so operators can see
        // immediately whether `staging-content-moderation-fallback-list` or
        // `content-moderation-fallback-list` was the missing slot. Mirrors the
        // Application.kt:391-393 precedent for fcm-init failures.
        val slot = secretKey(env, SECRET_SLOT_NAME)
        log.error(
            "event=moderation_list_unavailable list={} outcome=fail_open reason={} slot={} env={}",
            list.slug,
            reason ?: "unknown",
            slot,
            env,
        )
    }

    private data class CascadeWarn(
        val tier: String,
        val to: String,
        val reason: String,
        val listSlug: String,
    )

    companion object {
        const val SECRET_SLOT_NAME: String = "content-moderation-fallback-list"
        const val THRESHOLD_REMOTE_CONFIG_KEY: String = "moderation_match_threshold"
        const val THRESHOLD_DEFAULT: Int = 3
        const val THRESHOLD_MIN: Int = 1
        const val THRESHOLD_MAX: Int = 10_000
        const val THRESHOLD_CACHE_KEY: String = "{scope:mod_list}:{tier:threshold}"

        internal fun listCacheKey(list: ModerationList): String = "{scope:mod_list}:{tier:${list.slug}}"
    }
}
